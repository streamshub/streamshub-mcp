/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.systemtest;

import com.fasterxml.jackson.databind.JsonNode;
import io.fabric8.kubernetes.api.model.Namespace;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import io.quarkiverse.mcp.server.test.McpAssured;
import io.skodjob.kubetest4j.annotations.ClassNamespace;
import io.skodjob.kubetest4j.annotations.CleanupStrategy;
import io.skodjob.kubetest4j.annotations.InjectResourceManager;
import io.skodjob.kubetest4j.annotations.KubernetesTest;
import io.skodjob.kubetest4j.annotations.LogCollectionStrategy;
import io.skodjob.kubetest4j.resources.KubeResourceManager;
import io.streamshub.mcp.systemtest.clients.McpClientFactory;
import io.streamshub.mcp.systemtest.setup.mcp.ConnectivitySetup;
import io.streamshub.mcp.systemtest.setup.mcp.McpServerSetup;
import io.streamshub.mcp.systemtest.setup.strimzi.StrimziSetup;
import io.streamshub.mcp.systemtest.templates.strimzi.KafkaBridgeTemplates;
import io.streamshub.mcp.systemtest.templates.strimzi.KafkaConnectTemplates;
import io.streamshub.mcp.systemtest.templates.strimzi.KafkaNodePoolTemplates;
import io.streamshub.mcp.systemtest.templates.strimzi.KafkaTemplates;
import io.strimzi.api.kafka.model.bridge.KafkaBridge;
import io.strimzi.api.kafka.model.connect.KafkaConnect;
import io.strimzi.api.kafka.model.connector.KafkaConnector;
import io.strimzi.api.kafka.model.kafka.Kafka;
import io.strimzi.api.kafka.model.mirrormaker2.KafkaMirrorMaker2;
import io.strimzi.api.kafka.model.nodepool.KafkaNodePool;
import io.strimzi.api.kafka.model.rebalance.KafkaRebalance;
import io.strimzi.api.kafka.model.topic.KafkaTopic;
import io.strimzi.api.kafka.model.user.KafkaUser;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * System tests for pod-scraping metrics MCP tools.
 * Deploys a Kafka cluster with Bridge and Connect, then verifies
 * that instant metrics retrieval tools return well-formed responses.
 */
@KubernetesTest(
    cleanup = CleanupStrategy.AUTOMATIC,
    collectLogs = true,
    logCollectionStrategy = LogCollectionStrategy.ON_FAILURE,
    collectPreviousLogs = true,
    collectNamespacedResources = {
        "pods", "services", "configmaps", "secrets", "deployments",
        Kafka.RESOURCE_SINGULAR,
        KafkaNodePool.RESOURCE_SINGULAR,
        KafkaTopic.RESOURCE_SINGULAR,
        KafkaUser.RESOURCE_SINGULAR,
        KafkaConnect.RESOURCE_SINGULAR,
        KafkaConnector.RESOURCE_SINGULAR,
        KafkaBridge.RESOURCE_SINGULAR,
        KafkaMirrorMaker2.RESOURCE_SINGULAR,
        KafkaRebalance.RESOURCE_SINGULAR
    }
)
@DisplayName("Metrics MCP Tools")
@Epic("Strimzi MCP E2E")
@Feature("Metrics Tools")
class MetricsToolsST extends AbstractST {

    private static final Logger LOGGER = LoggerFactory.getLogger(MetricsToolsST.class);
    private static final String BRIDGE_NAME = "mcp-bridge";
    private static final String CONNECT_CLUSTER_NAME = "mcp-connect";

    @InjectResourceManager
    KubeResourceManager krm;

    @ClassNamespace(name = Constants.MCP_NAMESPACE)
    static Namespace mcpNamespace;

    @ClassNamespace(name = Constants.STRIMZI_NAMESPACE, labels = {"app=strimzi"})
    static Namespace strimziNamespace;

    @ClassNamespace(name = Constants.KAFKA_NAMESPACE, labels = {"app=strimzi"})
    static Namespace kafkaNamespace;

    private static McpAssured.McpStreamableTestClient mcpClient;

    MetricsToolsST() {
    }

    @BeforeAll
    void setup() {
        if (!Environment.SKIP_STRIMZI_INSTALL) {
            String kafkaNs = kafkaNamespace.getMetadata().getName();

            StrimziSetup.deploy(strimziNamespace.getMetadata().getName());

            KafkaTemplates.deployMetricsConfigMap(kafkaNs);

            krm.createOrUpdateResourceWithoutWait(
                KafkaNodePoolTemplates.controllerPool(kafkaNs, "controller-np",
                    Constants.KAFKA_CLUSTER_NAME, 1).build(),
                KafkaNodePoolTemplates.brokerPool(kafkaNs, "broker-np",
                    Constants.KAFKA_CLUSTER_NAME, 1).build());

            krm.createOrUpdateResourceWithWait(
                KafkaTemplates.kafka(kafkaNs, Constants.KAFKA_CLUSTER_NAME, 1).build());

            krm.createOrUpdateResourceWithWait(
                KafkaBridgeTemplates.kafkaBridge(
                    kafkaNs, BRIDGE_NAME, Constants.KAFKA_CLUSTER_NAME, 1).build());

            krm.createOrUpdateResourceWithWait(
                KafkaConnectTemplates.kafkaConnect(
                    kafkaNs, CONNECT_CLUSTER_NAME, Constants.KAFKA_CLUSTER_NAME, 1).build());
        }
        McpServerSetup.deploy(mcpNamespace.getMetadata().getName());

        String mcpUrl = ConnectivitySetup.expose(mcpNamespace.getMetadata().getName());
        mcpClient = McpClientFactory.create(mcpUrl);
    }

    @AfterAll
    static void cleanup() {
        if (mcpClient != null) {
            mcpClient.disconnect();
        }
    }

    // ---- Kafka Cluster Metrics ----

    @Test
    @DisplayName("get_kafka_metrics returns metrics for cluster")
    @Story("Get Kafka Metrics")
    void testGetKafkaMetrics() {
        Map<String, Object> args = Map.of("clusterName", Constants.KAFKA_CLUSTER_NAME);
        mcpClient.when()
            .toolsCall("get_kafka_metrics", args, response -> {
                assertFalse(response.isError(), "get_kafka_metrics should not return error");

                String text = response.content().getFirst().asText().text();
                LOGGER.info("get_kafka_metrics response (length={})", text.length());

                JsonNode root = parseJson(text);
                assertMetricsResponse(root, "cluster_name", Constants.KAFKA_CLUSTER_NAME);
                assertEquals(Environment.KAFKA_NAMESPACE, root.path("namespace").asText(),
                    "namespace should match deployment namespace");
            })
            .thenAssertResults();
    }

    @Test
    @DisplayName("get_kafka_metrics with replication category")
    @Story("Get Kafka Metrics")
    void testGetKafkaMetricsReplication() {
        Map<String, Object> args = Map.of(
            "clusterName", Constants.KAFKA_CLUSTER_NAME,
            "category", "replication");
        mcpClient.when()
            .toolsCall("get_kafka_metrics", args, response -> {
                assertFalse(response.isError(), "get_kafka_metrics replication should not return error");

                String text = response.content().getFirst().asText().text();
                LOGGER.info("get_kafka_metrics replication response (length={})", text.length());

                JsonNode root = parseJson(text);
                assertMetricsResponse(root, "cluster_name", Constants.KAFKA_CLUSTER_NAME);

                boolean hasReplication = false;
                for (JsonNode cat : root.path("categories")) {
                    if ("replication".equals(cat.asText())) {
                        hasReplication = true;
                        break;
                    }
                }
                assertTrue(hasReplication, "categories should contain 'replication'");
            })
            .thenAssertResults();
    }

    @Test
    @DisplayName("get_kafka_metrics returns error for non-existent cluster")
    @Story("Get Kafka Metrics")
    void testGetKafkaMetricsNotFound() {
        Map<String, Object> args = Map.of("clusterName", "nonexistent-cluster-xyz");
        mcpClient.when()
            .toolsCall("get_kafka_metrics", args, response -> {
                assertTrue(response.isError(),
                    "Should return error for non-existent cluster");

                String text = response.content().getFirst().asText().text();
                LOGGER.info("get_kafka_metrics error response: {}", text);

                assertTrue(text.toLowerCase(Locale.ROOT).contains("not found"),
                    "Error should mention 'not found'");
            })
            .thenAssertResults();
    }

    // ---- Kafka Exporter Metrics ----

    @Test
    @DisplayName("get_kafka_exporter_metrics returns exporter metrics")
    @Story("Get Kafka Exporter Metrics")
    void testGetKafkaExporterMetrics() {
        Map<String, Object> args = Map.of("clusterName", Constants.KAFKA_CLUSTER_NAME);
        mcpClient.when()
            .toolsCall("get_kafka_exporter_metrics", args, response -> {
                assertFalse(response.isError(), "get_kafka_exporter_metrics should not return error");

                String text = response.content().getFirst().asText().text();
                LOGGER.info("get_kafka_exporter_metrics response (length={})", text.length());

                JsonNode root = parseJson(text);
                assertMetricsResponse(root, "cluster_name", Constants.KAFKA_CLUSTER_NAME);
            })
            .thenAssertResults();
    }

    // ---- KafkaBridge Metrics ----

    @Test
    @DisplayName("get_kafka_bridge_metrics returns metrics for Bridge")
    @Story("Get KafkaBridge Metrics")
    void testGetKafkaBridgeMetrics() {
        Map<String, Object> args = Map.of(
            "bridgeName", BRIDGE_NAME);
        mcpClient.when()
            .toolsCall("get_kafka_bridge_metrics", args, response -> {
                assertFalse(response.isError(), "get_kafka_bridge_metrics should not return error");

                String text = response.content().getFirst().asText().text();
                LOGGER.info("get_kafka_bridge_metrics response (length={})", text.length());

                JsonNode root = parseJson(text);
                assertMetricsResponse(root, "bridge_name", BRIDGE_NAME);
                assertEquals(Environment.KAFKA_NAMESPACE, root.path("namespace").asText(),
                    "namespace should match deployment namespace");
            })
            .thenAssertResults();
    }

    // ---- KafkaConnect Metrics ----

    @Test
    @DisplayName("get_kafka_connect_metrics returns metrics for Connect cluster")
    @Story("Get KafkaConnect Metrics")
    void testGetKafkaConnectMetrics() {
        Map<String, Object> args = Map.of(
            "connectName", CONNECT_CLUSTER_NAME);
        mcpClient.when()
            .toolsCall("get_kafka_connect_metrics", args, response -> {
                assertFalse(response.isError(), "get_kafka_connect_metrics should not return error");

                String text = response.content().getFirst().asText().text();
                LOGGER.info("get_kafka_connect_metrics response (length={})", text.length());

                JsonNode root = parseJson(text);
                assertMetricsResponse(root, "connect_name", CONNECT_CLUSTER_NAME);
                assertEquals(Environment.KAFKA_NAMESPACE, root.path("namespace").asText(),
                    "namespace should match deployment namespace");
            })
            .thenAssertResults();
    }

    // ---- Strimzi Operator Metrics ----

    @Test
    @DisplayName("get_strimzi_operator_metrics returns operator metrics")
    @Story("Get Strimzi Operator Metrics")
    void testGetStrimziOperatorMetrics() {
        Map<String, Object> args = Map.of();
        mcpClient.when()
            .toolsCall("get_strimzi_operator_metrics", args, response -> {
                assertFalse(response.isError(),
                    "get_strimzi_operator_metrics should not return error");

                String text = response.content().getFirst().asText().text();
                LOGGER.info("get_strimzi_operator_metrics response (length={})", text.length());

                JsonNode root = parseJson(text);
                assertFalse(root.path("operator_name").asText("").isEmpty(),
                    "operator_name should be present and non-empty");
                assertFalse(root.path("namespace").isMissingNode(), "Should have namespace");
                assertTrue(root.path("categories").isArray(), "categories should be an array");
                assertTrue(root.path("time_series").isArray(), "time_series should be an array");
                assertTrue(root.path("metric_count").isNumber(), "metric_count should be a number");
                assertTrue(root.path("sample_count").isNumber(), "sample_count should be a number");
                assertFalse(root.path("timestamp").isMissingNode(), "Should have timestamp");
                assertFalse(root.path("message").isMissingNode(), "Should have message");
            })
            .thenAssertResults();
    }

    private static void assertMetricsResponse(JsonNode root, String nameField, String expectedName) {
        assertEquals(expectedName, root.path(nameField).asText(), nameField + " should match");
        assertFalse(root.path("namespace").isMissingNode(), "Should have namespace");
        assertTrue(root.path("categories").isArray(), "categories should be an array");
        assertTrue(root.path("time_series").isArray(), "time_series should be an array");
        assertTrue(root.path("metric_count").isNumber(), "metric_count should be a number");
        assertTrue(root.path("sample_count").isNumber(), "sample_count should be a number");
        assertFalse(root.path("timestamp").isMissingNode(), "Should have timestamp");
        assertFalse(root.path("message").isMissingNode(), "Should have message");
    }
}
