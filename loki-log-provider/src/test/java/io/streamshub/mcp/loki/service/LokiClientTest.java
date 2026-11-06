/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.loki.service;

import com.sun.net.httpserver.HttpServer;
import io.quarkus.test.junit.QuarkusTest;
import io.streamshub.mcp.loki.LokiResponse;
import jakarta.ws.rs.WebApplicationException;
import org.eclipse.microprofile.rest.client.RestClientBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests that {@link LokiClient} sends the expected request and parses a real Loki response body.
 */
@QuarkusTest
class LokiClientTest {

    private static final String BODY = "{\"status\":\"success\",\"data\":{\"resultType\":\"streams\","
        + "\"stats\":{\"ignored\":true},\"result\":[{\"stream\":{\"pod\":\"p-0\"},"
        + "\"values\":[[\"1700000000000000000\",\"hello\"],[\"1700000001000000000\",\"world\"]]}]}}";

    private HttpServer server;
    private final AtomicReference<String> requestUri = new AtomicReference<>();
    private volatile int statusCode = 200;
    private LokiClient client;

    LokiClientTest() {
    }

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requestUri.set(exchange.getRequestURI().toString());
            byte[] bytes = BODY.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(statusCode, statusCode == 200 ? bytes.length : -1);
            if (statusCode == 200) {
                exchange.getResponseBody().write(bytes);
            }
            exchange.close();
        });
        server.start();
        client = RestClientBuilder.newBuilder()
            .baseUri(URI.create("http://127.0.0.1:" + server.getAddress().getPort()))
            .build(LokiClient.class);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void testQueryRangeSendsPathAndParametersAndParsesStreams() {
        LokiResponse response = client.queryRange("{pod=\"p-0\"} |= \"x\"", 10L, 20L, 5, "backward");

        String uri = requestUri.get();
        assertTrue(uri.startsWith("/loki/api/v1/query_range?"), uri);
        assertTrue(uri.contains("start=10"), uri);
        assertTrue(uri.contains("end=20"), uri);
        assertTrue(uri.contains("limit=5"), uri);
        assertTrue(uri.contains("direction=backward"), uri);
        assertTrue(uri.contains("query=%7Bpod%3D%22p-0%22%7D"), uri);

        assertEquals("success", response.status());
        assertEquals("streams", response.data().resultType());
        assertEquals(1, response.data().result().size());
        assertEquals("p-0", response.data().result().getFirst().stream().get("pod"));
        assertEquals(2, response.data().result().getFirst().values().size());
        assertEquals("hello", response.data().result().getFirst().values().getFirst().get(1));
    }

    @Test
    void testServerErrorSurfacesAsWebApplicationException() {
        statusCode = 500;

        assertThrows(WebApplicationException.class,
            () -> client.queryRange("{a=\"b\"}", 1L, 2L, 1, "forward"));
    }
}
