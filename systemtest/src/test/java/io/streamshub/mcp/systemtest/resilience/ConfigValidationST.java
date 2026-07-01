/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.systemtest.resilience;

import com.fasterxml.jackson.databind.JsonNode;
import io.fabric8.kubernetes.api.model.Namespace;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import io.quarkiverse.mcp.server.test.McpAssured;
import io.skodjob.kubetest4j.annotations.ClassNamespace;
import io.skodjob.kubetest4j.annotations.InjectResourceManager;
import io.skodjob.kubetest4j.resources.KubeResourceManager;
import io.streamshub.mcp.systemtest.AbstractST;
import io.streamshub.mcp.systemtest.Constants;
import io.streamshub.mcp.systemtest.Environment;
import io.streamshub.mcp.systemtest.clients.McpClientFactory;
import io.streamshub.mcp.systemtest.setup.mcp.ConnectivitySetup;
import io.streamshub.mcp.systemtest.setup.mcp.McpServerSetup;
import io.streamshub.mcp.systemtest.setup.strimzi.StrimziSetup;
import io.streamshub.mcp.systemtest.templates.strimzi.KafkaNodePoolTemplates;
import io.streamshub.mcp.systemtest.templates.strimzi.KafkaTemplates;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * System tests for MCP server behavior with invalid provider configuration.
 * Deploys a Kafka cluster and the MCP server with intentionally wrong
 * Prometheus/Loki URLs, then verifies that tools return clean errors
 * instead of stack traces or hangs when the provider endpoints are
 * unreachable.
 */
@Epic("Strimzi MCP E2E")
@Feature("MCP Server Config Validation")
class ConfigValidationST extends AbstractST {

    private static final Logger LOGGER = LoggerFactory.getLogger(ConfigValidationST.class);

    @InjectResourceManager
    KubeResourceManager krm;

    @ClassNamespace(name = Constants.MCP_NAMESPACE)
    static Namespace mcpNamespace;

    @ClassNamespace(name = Constants.STRIMZI_NAMESPACE, labels = {"app=strimzi"})
    static Namespace strimziNamespace;

    @ClassNamespace(name = Constants.KAFKA_NAMESPACE, labels = {"app=strimzi"})
    static Namespace kafkaNamespace;

    private static McpAssured.McpStreamableTestClient mcpClient;

    ConfigValidationST() {
    }

    @BeforeAll
    void setup() {
        if (!Environment.SKIP_STRIMZI_INSTALL) {
            String kafkaNs = kafkaNamespace.getMetadata().getName();
            StrimziSetup.deploy(strimziNamespace.getMetadata().getName());

            krm.createOrUpdateResourceWithoutWait(
                KafkaNodePoolTemplates.controllerPool(kafkaNs, "controller-np",
                    Constants.KAFKA_CLUSTER_NAME, 1).build(),
                KafkaNodePoolTemplates.brokerPool(kafkaNs, "broker-np",
                    Constants.KAFKA_CLUSTER_NAME, 1).build());

            krm.createOrUpdateResourceWithWait(
                KafkaTemplates.kafka(kafkaNs, Constants.KAFKA_CLUSTER_NAME, 1).build());
        }

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
    @Story("Metrics tool returns clean error with invalid Prometheus URL")
    void testInvalidPrometheusUrl() {
        Map<String, Object> args = Map.of(
            "clusterName", Constants.KAFKA_CLUSTER_NAME,
            "namespace", kafkaNamespace.getMetadata().getName());

        mcpClient.when()
            .toolsCall("get_kafka_metrics", args, response -> {
                assertToolError(response, "nonexistent-prometheus");

                String text = response.content().getFirst().asText().text();
                assertTrue(response.isError(),
                    "Metrics should fail with unreachable Prometheus");
                assertTrue(text.contains("nonexistent-prometheus"),
                    "Error should reference the unreachable Prometheus host, got: " + text);
                assertFalse(text.contains("not found"),
                    "Error should be about unreachable Prometheus, not missing cluster");
                assertTrue(text.contains("UnknownHostException"),
                    "Error should mention UnknownHostException for DNS failure");
                assertNoStackTrace(text);
            })
            .thenAssertResults();
    }

    @Test
    @Story("Log tool returns clean error with invalid Loki URL")
    void testInvalidLokiUrl() {
        Map<String, Object> args = Map.of(
            "clusterName", Constants.KAFKA_CLUSTER_NAME,
            "namespace", kafkaNamespace.getMetadata().getName(),
            "tailLines", 10);

        mcpClient.when()
            .toolsCall("get_kafka_cluster_logs", args, response -> {
                JsonNode root = assertToolSuccess(response);

                String text = response.content().getFirst().asText().text();
                assertEquals(0, root.path("log_lines").asInt(),
                    "No log lines should be retrieved with invalid Loki URL");
                assertEquals(Constants.KAFKA_CLUSTER_NAME, root.path("cluster_name").asText(),
                    "cluster_name should match");
                assertEquals(3, root.path("pods").size(),
                    "Should list all 3 pods even though Loki failed");
                assertTrue(text.contains("Loki query failed"),
                    "Response should indicate Loki query failure, got: " + text);
                assertTrue(text.contains("nonexistent-loki"),
                    "Response should reference the unreachable Loki host, got: " + text);
                assertTrue(text.contains("UnknownHostException"),
                    "Response should contain UnknownHostException for the unreachable host");
                assertFalse(root.path("has_errors").asBoolean(),
                    "has_errors should be false (Loki errors are per-pod, not data errors)");
                assertNoStackTrace(text);
            })
            .thenAssertResults();
    }

    @Test
    @Story("Non-provider tools still work despite bad provider config")
    void testNonProviderToolsStillWork() {
        mcpClient.when()
            .toolsList(page -> {
                LOGGER.info("tools/list succeeded with {} tools despite bad provider config",
                    page.size());
                assertTrue(page.size() >= 40,
                    "Tool discovery should return a substantial number of tools despite bad provider config, got: " + page.size());
            })
            .thenAssertResults();
    }
}
