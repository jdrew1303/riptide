package org.zalando.riptide;

import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Multimap;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.AsyncClientHttpRequest;
import org.springframework.http.client.AsyncClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.util.concurrent.ListenableFuture;
import org.zalando.fauxpas.ThrowingUnaryOperator;

import javax.annotation.CheckReturnValue;
import javax.annotation.Nullable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static org.zalando.riptide.CancelableCompletableFuture.preserveCancelability;

public final class Requester extends Dispatcher {

    private final AsyncClientHttpRequestFactory requestFactory;
    private final MessageWorker worker;
    private final RequestArguments arguments;
    private final Plugin plugin;

    private final ImmutableMultimap<String, String> query;
    private final HttpHeaders headers;

    Requester(final AsyncClientHttpRequestFactory requestFactory, final MessageWorker worker,
            final RequestArguments arguments, final Plugin plugin, final ImmutableMultimap<String,String> query, final HttpHeaders headers) {
        this.requestFactory = requestFactory;
        this.worker = worker;
        this.arguments = arguments;
        this.plugin = plugin;
        this.query = query;
        this.headers = headers;
    }

    @CheckReturnValue
    public Requester queryParam(final String name, final String value) {
        return withQuery(ImmutableMultimap.<String, String>builder().putAll(query).put(name, value).build());
    }

    @CheckReturnValue
    public Requester queryParams(final Multimap<String, String> params) {
        return withQuery(ImmutableMultimap.<String, String>builder().putAll(query).putAll(params).build());
    }

    @CheckReturnValue
    private Requester withQuery(final ImmutableMultimap<String, String> query) {
        return new Requester(requestFactory, worker, arguments, plugin, query, headers);
    }

    @CheckReturnValue
    public Requester accept(final MediaType acceptableMediaType, final MediaType... acceptableMediaTypes) {
        return withHeaders(headers -> headers.setAccept(Lists.asList(acceptableMediaType, acceptableMediaTypes)));
    }

    @CheckReturnValue
    public Requester contentType(final MediaType contentType) {
        return withHeaders(headers -> headers.setContentType(contentType));
    }

    @CheckReturnValue
    public Requester ifModifiedSince(final OffsetDateTime since) {
        return withHeaders(headers -> headers.setIfModifiedSince(since.toInstant().toEpochMilli()));
    }

    @CheckReturnValue
    public Requester ifUnmodifiedSince(final OffsetDateTime since) {
        return withHeaders(headers -> headers.setIfUnmodifiedSince(since.toInstant().toEpochMilli()));
    }

    @CheckReturnValue
    public Requester ifNoneMatch(final String... entityTags) {
        return ifNoneMatch(Arrays.asList(entityTags));
    }

    @CheckReturnValue
    public Requester ifMatch(final String... entityTags) {
        return ifMatch(Arrays.asList(entityTags));
    }

    @CheckReturnValue
    public Requester ifMatch(final List<String> entityTags) {
        return withHeaders(headers -> headers.setIfMatch(entityTags));
    }

    @CheckReturnValue
    public Requester ifNoneMatch(final List<String> entityTags) {
        return withHeaders(headers -> headers.setIfNoneMatch(entityTags));
    }

    @CheckReturnValue
    public Requester header(final String name, final String value) {
        return withHeaders(headers -> headers.add(name, value));
    }

    @CheckReturnValue
    public Requester headers(final HttpHeaders additionalHeaders) {
        return withHeaders(headers -> headers.putAll(additionalHeaders));
    }

    @CheckReturnValue
    private Requester withHeaders(final Consumer<HttpHeaders> applier) {
        final HttpHeaders copy = new HttpHeaders();
        copy.putAll(headers);
        applier.accept(copy);
        return new Requester(requestFactory, worker, arguments, plugin, query, copy);
    }

    @CheckReturnValue
    public <T> Dispatcher body(@Nullable final T body) {
        return execute(body);
    }

    @Override
    public CompletableFuture<ClientHttpResponse> call(final Route route) {
        return execute(null).call(route);
    }

    private <T> Dispatcher execute(final @Nullable T body) {
        final ImmutableMultimap.Builder<String, String> builder = ImmutableMultimap.builder();
        headers.forEach(builder::putAll);

        return new ResponseDispatcher(new HttpEntity<>(body, headers), arguments
                .withQueryParams(ImmutableMultimap.copyOf(query))
                .withRequestUri()
                .withHeaders(builder.build())
                .withBody(body)
        );
    }

    private final class ResponseDispatcher extends Dispatcher {

        private final HttpEntity<?> entity;
        private final RequestArguments arguments;

        ResponseDispatcher(final HttpEntity<?> entity, final RequestArguments arguments) {
            this.entity = entity;
            this.arguments = arguments;
        }

        @Override
        public CompletableFuture<ClientHttpResponse> call(final Route route) {
            try {
                final RequestExecution before = plugin.beforeSend(this::send);
                final RequestExecution after = plugin.beforeDispatch(dispatch(before, route));

                return after.execute(arguments);
            } catch (final IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        private CompletableFuture<ClientHttpResponse> send(final RequestArguments arguments) throws IOException {
            final AsyncClientHttpRequest request = createRequest(arguments);
            // TODO do this in the IO thread!
            worker.write(request, entity);
            final ListenableFuture<ClientHttpResponse> original = request.executeAsync();

            final CompletableFuture<ClientHttpResponse> future = preserveCancelability(original);
            original.addCallback(future::complete, future::completeExceptionally);
            return future;
        }

        private AsyncClientHttpRequest createRequest(final RequestArguments arguments) throws IOException {
            final URI requestUri = arguments.getRequestUri();
            final HttpMethod method = arguments.getMethod();
            return requestFactory.createAsyncRequest(requestUri, method);
        }

        private RequestExecution dispatch(final RequestExecution execution, final Route route) {
            return arguments -> execution.execute(arguments).thenApply(dispatch(route));
        }

        private ThrowingUnaryOperator<ClientHttpResponse, Exception> dispatch(final Route route) {
            return response -> {
                try {
                    route.execute(response, worker);
                } catch (final NoWildcardException e) {
                    throw new UnexpectedResponseException(response);
                }

                return response;
            };
        }

    }

}
