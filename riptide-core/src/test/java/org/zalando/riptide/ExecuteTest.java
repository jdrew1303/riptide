package org.zalando.riptide;

import com.google.common.collect.ImmutableMap;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.springframework.http.MediaType;
import org.springframework.http.converter.xml.Jaxb2RootElementHttpMessageConverter;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClientException;
import org.zalando.riptide.model.Success;

import java.util.Collections;

import static org.springframework.http.HttpStatus.Series.SUCCESSFUL;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.http.MediaType.APPLICATION_XML;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.zalando.riptide.Bindings.on;
import static org.zalando.riptide.Navigators.series;
import static org.zalando.riptide.Route.pass;

public final class ExecuteTest {

    @Rule
    public final ExpectedException exception = ExpectedException.none();

    private final String url = "https://api.example.com";

    private final Rest unit;
    private final MockRestServiceServer server;

    public ExecuteTest() {
        final MockSetup setup =new MockSetup();
        this.server = setup.getServer();
        this.unit = setup.getRest();
    }

    @After
    public void after() {
        server.verify();
    }

    @Test
    public void shouldSendNoBody() {
        server.expect(requestTo(url))
                .andExpect(content().string(""))
                .andRespond(withSuccess());

        unit.trace(url)
                .dispatch(series(),
                        on(SUCCESSFUL).call(pass()));
    }

    @Test
    public void shouldSendHeaders() {
        server.expect(requestTo(url))
                .andExpect(header("X-Foo", "bar"))
                .andRespond(withSuccess());

        unit.head(url)
                .header("X-Foo", "bar")
                .dispatch(series(),
                        on(SUCCESSFUL).call(pass()));
    }

    @Test
    public void shouldSendBody() {
        server.expect(requestTo(url))
                .andExpect(content().string("{\"foo\":\"bar\"}"))
                .andRespond(withSuccess());

        unit.post(url)
                .body(ImmutableMap.of("foo", "bar"));
    }

    @Test
    public void shouldSendHeadersAndBody() {
        server.expect(requestTo(url))
                .andExpect(header("X-Foo", "bar"))
                .andExpect(content().string("{\"foo\":\"bar\"}"))
                .andRespond(withSuccess());

        unit.put(url)
                .header("X-Foo", "bar")
                .body(ImmutableMap.of("foo", "bar"));
    }

    @Test
    public void shouldFailIfNoConverterFoundForBody() {
        // we never actually make the request, but the mock server is doing some magic pre-actively
        server.expect(requestTo(url))
                .andExpect(header("Accept", MediaType.APPLICATION_JSON_VALUE))
                .andRespond(withSuccess());

        exception.expect(RestClientException.class);
        exception.expectMessage("no suitable HttpMessageConverter found ");
        exception.expectMessage("org.zalando.riptide.model.Success");
        exception.expectMessage("application/xml");

        unit.patch(url)
                .accept(APPLICATION_JSON)
                .contentType(APPLICATION_XML)
                .body(new Success(true));
    }

    @Test
    public void shouldFailIfNoConverterFoundForBodyOfUnknownContentType() {
        final MockSetup setup = new MockSetup("https://api.example.com", Collections.emptyList());
        final MockRestServiceServer server = setup.getServer();
        final Rest unit = setup.getRestBuilder()
                .converter(new Jaxb2RootElementHttpMessageConverter()).build();

        // we never actually make the request, but the mock server is doing some magic pre-actively
        server.expect(requestTo(url))
                .andRespond(withSuccess());

        exception.expect(RestClientException.class);
        exception.expectMessage("no suitable HttpMessageConverter found ");
        exception.expectMessage("org.zalando.riptide.model.Success");

        unit.delete(url)
                .body(new Success(true));
    }

    @Test
    public void shouldFailIfNoConverterFoundForBodyOfUnsupportedContentType() {
        // we never actually make the request, but the mock server is doing some magic pre-actively
        server.expect(requestTo(url))
                .andRespond(withSuccess());

        exception.expect(RestClientException.class);
        exception.expectMessage("no suitable HttpMessageConverter found ");
        exception.expectMessage("org.zalando.riptide.model.Success");

        unit.delete(url)
                .contentType(MediaType.parseMediaType("application/x-json-stream"))
                .body(new Success(true));
    }

}
