/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.systemtest.security;

import com.fasterxml.jackson.databind.JsonNode;
import io.fabric8.kubernetes.api.model.Namespace;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import io.quarkiverse.mcp.server.test.McpAssured;
import io.skodjob.kubetest4j.annotations.ClassNamespace;
import io.streamshub.mcp.systemtest.AbstractST;
import io.streamshub.mcp.systemtest.Constants;
import io.streamshub.mcp.systemtest.clients.ManagementEndpoint;
import io.streamshub.mcp.systemtest.clients.McpClientFactory;
import io.streamshub.mcp.systemtest.setup.mcp.ConnectivitySetup;
import io.streamshub.mcp.systemtest.setup.mcp.McpServerSetup;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

import static io.streamshub.mcp.systemtest.TestTags.REGRESSION;
import static io.streamshub.mcp.systemtest.TestTags.SECURITY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * System tests for MCP server observability: health endpoints and
 * Prometheus metrics exposure. Deploys only the MCP server (no Kafka)
 * and verifies that operational endpoints work correctly.
 */
@Epic("Strimzi MCP E2E")
@Feature("MCP Observability")
@Tag(REGRESSION)
@Tag(SECURITY)
class ServerObservabilityST extends AbstractST {

    private static final Logger LOGGER = LoggerFactory.getLogger(ServerObservabilityST.class);

    @ClassNamespace(name = Constants.MCP_NAMESPACE)
    static Namespace mcpNamespace;

    private static McpAssured.McpStreamableTestClient mcpClient;
    private static String mcpBaseUrl;
    
    ServerObservabilityST() {
    }

    @BeforeAll
    void setup() {
        McpServerSetup.deploy(mcpNamespace.getMetadata().getName());
        
        mcpBaseUrl = ConnectivitySetup.expose(mcpNamespace.getMetadata().getName());
        mcpClient = McpClientFactory.create(mcpBaseUrl);
    }

    @AfterAll
    static void cleanup() {
        if (mcpClient != null) {
            mcpClient.disconnect();
        }
    }

    // ---- Health Endpoints ----

    @Test
    @Story("Liveness endpoint returns UP")
    void testHealthLiveness() throws Exception {
        String body = managementGet("/q/health/live");

        LOGGER.info("Liveness response: {}", body);
        JsonNode root = parseJson(body);
        assertEquals("UP", root.path("status").asText(),
            "Liveness endpoint should report UP status");
    }

    @Test
    @Story("Readiness endpoint returns UP")
    void testHealthReadiness() throws Exception {
        String body = managementGet("/q/health/ready");

        LOGGER.info("Readiness response: {}", body);
        JsonNode root = parseJson(body);
        assertEquals("UP", root.path("status").asText(),
            "Readiness endpoint should report UP status");
        JsonNode checks = root.path("checks");
        assertTrue(checks.isArray() && checks.size() >= 2, "Should have at least 2 health checks");
    }

    // ---- Prometheus Metrics ----

    @Test
    @Story("Health and metrics are not served on the MCP port")
    void testManagementEndpointsNotServedOnMcpPort() throws Exception {
        // NetworkPolicies select by port: a metrics scrape rule must not also admit /mcp.
        assertEquals(404, mcpPortStatus("/q/metrics"), "/q/metrics must only be on the management port");
        assertEquals(404, mcpPortStatus("/q/health/ready"), "/q/health must only be on the management port");
    }

    @Test
    @Story("MCP tool call metrics are recorded after tool invocation")
    void testMcpToolCallMetrics() throws Exception {
        // Make a tool call to generate metrics
        mcpClient.when()
            .toolsCall("list_kafka_clusters",
                Map.of("namespace", "nonexistent-ns"), response ->
                    LOGGER.info("Tool call completed (isError={})", response.isError()))
            .thenAssertResults();

        String metrics = managementGet("/q/metrics");
        LOGGER.info("Prometheus metrics response length={}", metrics.length());
        assertTrue(metrics.contains("mcp_tool_calls"),
            "Metrics should contain mcp_tool_calls counter");
        assertTrue(metrics.contains("list_kafka_clusters"),
            "Metrics should reference the tool name used");
        assertTrue(metrics.contains("mcp_tool_calls_total"), "Should contain total counter metric");
    }

    @Test
    @Story("MCP tool call duration metrics are recorded")
    void testMcpToolCallDuration() throws Exception {
        // Make a tool call to generate duration metrics
        mcpClient.when()
            .toolsCall("get_kafka_fleet_overview",
                Map.of("namespace", "nonexistent-ns"), response ->
                    LOGGER.info("Tool call completed (isError={})", response.isError()))
            .thenAssertResults();

        String metrics = managementGet("/q/metrics");
        LOGGER.info("Prometheus duration metrics response length={}", metrics.length());
        assertTrue(metrics.contains("mcp_tool_call_duration"),
            "Metrics should contain mcp_tool_call_duration timer");
        assertTrue(metrics.contains("get_kafka_fleet_overview"), "Duration metrics should reference the tool name used");
    }

    private static String managementGet(final String path) throws Exception {
        HttpResponse<String> response = ManagementEndpoint.get(mcpNamespace.getMetadata().getName(), path);
        assertEquals(200, response.statusCode(), "GET " + path + " on the management port should return 200");
        return response.body();
    }

    private static int mcpPortStatus(final String path) throws Exception {
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()) {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(mcpBaseUrl + path))
                .GET()
                .timeout(Duration.ofSeconds(30))
                .build();
            return client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
        }
    }
}
