/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.config;

import io.quarkus.test.junit.QuarkusTest;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Verifies the default CORS configuration really admits {@code localhost} origins on any port
 * and rejects other origins.
 */
@QuarkusTest
class CorsConfigTest {

    @ConfigProperty(name = "quarkus.http.test-port")
    int testPort;

    CorsConfigTest() {
    }

    private String preflight(final String origin) throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + testPort + "/mcp"))
                .header("Origin", origin)
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "content-type")
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            return response.headers().firstValue("Access-Control-Allow-Origin").orElse(null);
        }
    }

    /**
     * Verify http localhost origins with an arbitrary port are allowed.
     */
    @Test
    void testHttpLocalhostOriginAllowed() throws Exception {
        assertEquals("http://localhost:6274", preflight("http://localhost:6274"));
    }

    /**
     * Verify https localhost origins with an arbitrary port are allowed.
     */
    @Test
    void testHttpsLocalhostOriginAllowed() throws Exception {
        assertEquals("https://localhost:8443", preflight("https://localhost:8443"));
    }

    /**
     * Verify unrelated origins are not allowed.
     */
    @Test
    void testForeignOriginRejected() throws Exception {
        String allowed = preflight("https://evil.example.com");
        assertFalse("https://evil.example.com".equals(allowed), "Foreign origin must not be echoed");
    }

    /**
     * Verify lookalike hosts that merely start with localhost are not allowed.
     */
    @Test
    void testLocalhostLookalikeRejected() throws Exception {
        String allowed = preflight("http://localhost.evil.example.com:80");
        assertFalse("http://localhost.evil.example.com:80".equals(allowed), "Lookalike origin must not be echoed");
    }
}
