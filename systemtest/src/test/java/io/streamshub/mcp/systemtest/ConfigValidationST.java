/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.systemtest;

import io.fabric8.kubernetes.api.model.Namespace;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import io.quarkiverse.mcp.server.test.McpAssured;
import io.skodjob.kubetest4j.annotations.ClassNamespace;
import io.skodjob.kubetest4j.annotations.KubernetesTest;
import io.streamshub.mcp.systemtest.clients.McpClientFactory;
import io.streamshub.mcp.systemtest.setup.mcp.ConnectivitySetup;
import io.streamshub.mcp.systemtest.setup.mcp.McpServerSetup;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * System tests for MCP server behavior with invalid provider configuration.
 * Deploys the MCP server with intentionally wrong Prometheus/Loki URLs and
 * verifies that tools return clean errors instead of stack traces or hangs.
 * No Strimzi or Kafka cluster needed.
 */
@KubernetesTest
@DisplayName("Config Validation")
@Epic("Strimzi MCP E2E")
@Feature("Config Validation")
class ConfigValidationST extends AbstractST {

    private static final Logger LOGGER = LoggerFactory.getLogger(ConfigValidationST.class);

    @ClassNamespace(name = Constants.MCP_NAMESPACE)
    static Namespace mcpNamespace;

    private static McpAssured.McpStreamableTestClient mcpClient;

    ConfigValidationST() {
    }

    @BeforeAll
    void setup() {
        McpServerSetup.builder(mcpNamespace.getMetadata().getName())
            .withEnv("MCP_METRICS_PROVIDER", "streamshub-prometheus")
            .withEnv("QUARKUS_REST_CLIENT_PROMETHEUS_URL", "http://nonexistent-prometheus:9090")
            .withEnv("MCP_LOG_PROVIDER", "streamshub-loki")
            .withEnv("QUARKUS_REST_CLIENT_LOKI_URL", "http://nonexistent-loki:3100")
            .deploy();

        String mcpUrl = ConnectivitySetup.expose(mcpNamespace.getMetadata().getName());
        mcpClient = McpClientFactory.create(mcpUrl);
    }

    @AfterAll
    static void cleanup() {
        if (mcpClient != null) {
            mcpClient.disconnect();
        }
    }

    @Test
    @DisplayName("Metrics tool returns clean error with invalid Prometheus URL")
    @Story("Invalid Prometheus Config")
    void testInvalidPrometheusUrl() {
        Map<String, Object> args = Map.of(
            "clusterName", "any-cluster",
            "namespace", "any-namespace");
        mcpClient.when()
            .toolsCall("get_kafka_metrics", args, response -> {
                assertTrue(response.isError(),
                    "Metrics should fail with unreachable Prometheus");

                String text = response.content().getFirst().asText().text();
                LOGGER.info("Invalid Prometheus URL response: {}", text);

                assertFalse(text.contains("NullPointerException"),
                    "Should not leak NullPointerException");
                assertFalse(text.contains("\tat "),
                    "Should not leak stack trace frames");
            })
            .thenAssertResults();
    }

    @Test
    @DisplayName("Log tool returns clean error with invalid Loki URL")
    @Story("Invalid Loki Config")
    void testInvalidLokiUrl() {
        Map<String, Object> args = Map.of(
            "clusterName", "any-cluster",
            "namespace", "any-namespace",
            "tailLines", 10);
        mcpClient.when()
            .toolsCall("get_kafka_cluster_logs", args, response -> {
                assertTrue(response.isError(),
                    "Logs should fail with unreachable Loki");

                String text = response.content().getFirst().asText().text();
                LOGGER.info("Invalid Loki URL response: {}", text);

                assertFalse(text.contains("NullPointerException"),
                    "Should not leak NullPointerException");
                assertFalse(text.contains("\tat "),
                    "Should not leak stack trace frames");
            })
            .thenAssertResults();
    }

    @Test
    @DisplayName("Non-provider tools still work despite bad provider config")
    @Story("Invalid Provider Config Isolation")
    void testNonProviderToolsStillWork() {
        mcpClient.when()
            .toolsList(page -> {
                LOGGER.info("tools/list succeeded with {} tools despite bad provider config",
                    page.size());
                assertTrue(page.size() > 0,
                    "Tool discovery should still work regardless of provider config");
            })
            .thenAssertResults();
    }
}
