/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.config;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies health and metrics are served on the management interface only, so a NetworkPolicy
 * rule that admits metrics scraping does not also expose the unauthenticated {@code /mcp} port.
 */
@QuarkusTest
class ManagementInterfaceConfigTest {

    @TestHTTPResource("/q/metrics")
    URI mainPortMetrics;

    @TestHTTPResource("/q/health/ready")
    URI mainPortHealth;

    @TestHTTPResource(value = "/metrics", management = true)
    URI managementMetrics;

    @TestHTTPResource(value = "/health/ready", management = true)
    URI managementHealth;

    ManagementInterfaceConfigTest() {
    }

    private static HttpResponse<String> get(final URI uri) throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    /**
     * Verify metrics and health are served on the management port.
     */
    @Test
    void testManagementPortServesMetricsAndHealth() throws Exception {
        HttpResponse<String> metrics = get(managementMetrics);
        assertEquals(200, metrics.statusCode());
        assertTrue(metrics.body().contains("jvm_"), "expected Prometheus metrics");
        // Health is UP (200) or DOWN (503) depending on the test environment; it must be served here.
        int healthStatus = get(managementHealth).statusCode();
        assertTrue(healthStatus == 200 || healthStatus == 503, "unexpected health status " + healthStatus);
    }

    /**
     * Verify the MCP port no longer serves metrics or health.
     */
    @Test
    void testMcpPortDoesNotServeMetricsOrHealth() throws Exception {
        assertEquals(404, get(mainPortMetrics).statusCode());
        assertEquals(404, get(mainPortHealth).statusCode());
    }
}
