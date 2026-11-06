/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.opensearch.service;

import com.sun.net.httpserver.HttpServer;
import io.quarkus.test.junit.QuarkusTest;
import io.streamshub.mcp.opensearch.OpenSearchResponse;
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

/**
 * Tests that {@link OpenSearchClient} sends the expected request and parses a real OpenSearch response body.
 */
@QuarkusTest
class OpenSearchClientTest {

    private static final String BODY = "{\"took\":3,\"hits\":{\"total\":{\"value\":2,\"relation\":\"eq\"},"
        + "\"hits\":[{\"_index\":\"logs\",\"_source\":{\"message\":\"hello\"},\"sort\":[1700000000000]},"
        + "{\"_source\":{\"message\":\"world\"},\"sort\":[1700000001000]}]}}";

    private HttpServer server;
    private final AtomicReference<String> requestLine = new AtomicReference<>();
    private final AtomicReference<String> requestBody = new AtomicReference<>();
    private final AtomicReference<String> contentType = new AtomicReference<>();
    private volatile int statusCode = 200;
    private OpenSearchClient client;

    OpenSearchClientTest() {
    }

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requestLine.set(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
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
            .build(OpenSearchClient.class);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void testSearchPostsQueryToIndexAndParsesHits() {
        String query = "{\"size\":2,\"query\":{\"match_all\":{}}}";

        OpenSearchResponse response = client.search("kafka-logs-*", query);

        assertEquals("POST /kafka-logs-*/_search", requestLine.get());
        assertEquals("application/json", contentType.get());
        assertEquals(query, requestBody.get());
        assertEquals(2, response.hits().total().value());
        assertEquals("eq", response.hits().total().relation());
        assertEquals(2, response.hits().hits().size());
        assertEquals("hello", response.hits().hits().getFirst().source().get("message"));
    }

    @Test
    void testServerErrorSurfacesAsWebApplicationException() {
        statusCode = 503;

        assertThrows(WebApplicationException.class, () -> client.search("logs", "{}"));
    }
}
