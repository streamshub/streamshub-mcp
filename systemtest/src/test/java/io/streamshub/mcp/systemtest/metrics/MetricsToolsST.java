/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.systemtest.metrics;

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
import io.streamshub.mcp.systemtest.templates.strimzi.KafkaBridgeTemplates;
import io.streamshub.mcp.systemtest.templates.strimzi.KafkaConnectTemplates;
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
 * System tests for pod-scraping metrics MCP tools.
 * Deploys a Kafka cluster with Bridge and Connect, then verifies
 * that instant metrics retrieval tools return well-formed responses.
 */
@Epic("Strimzi MCP E2E")
@Feature("Metrics Tools")
class MetricsToolsST extends AbstractST {

    private static final Logger LOGGER = LoggerFactory.getLogger(MetricsToolsST.class);

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
            KafkaTemplates.deployPodMonitors(kafkaNs);

            krm.createOrUpdateResourceWithoutWait(
                KafkaNodePoolTemplates.controllerPool(kafkaNs, "controller-np",
                    Constants.KAFKA_CLUSTER_NAME, 1).build(),
                KafkaNodePoolTemplates.brokerPool(kafkaNs, "broker-np",
                    Constants.KAFKA_CLUSTER_NAME, 1).build());

            krm.createOrUpdateResourceWithWait(
                KafkaTemplates.kafkaWithMetrics(kafkaNs, Constants.KAFKA_CLUSTER_NAME, 1).build());

            krm.createOrUpdateResourceWithWait(
                KafkaBridgeTemplates.kafkaBridge(
                    kafkaNs, Constants.BRIDGE_NAME, Constants.KAFKA_CLUSTER_NAME, 1).build());

            krm.createOrUpdateResourceWithWait(
                KafkaConnectTemplates.kafkaConnect(
                    kafkaNs, Constants.CONNECT_CLUSTER_NAME, Constants.KAFKA_CLUSTER_NAME, 1).build());
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
    @Story("get_kafka_metrics returns metrics for cluster")
    void testGetKafkaMetrics() {
        Map<String, Object> args = Map.of("clusterName", Constants.KAFKA_CLUSTER_NAME);
        mcpClient.when()
            .toolsCall("get_kafka_metrics", args, response -> {
                JsonNode root = assertToolSuccess(response);
                String text = response.content().getFirst().asText().text();

                LOGGER.info("get_kafka_metrics response (length={})", text.length());
                LOGGER.debug("get_kafka_metrics response:\n{}", text);

                assertEquals(Constants.KAFKA_CLUSTER_NAME, root.path("cluster_name").asText(), "cluster_name should match");
                assertEquals(Environment.KAFKA_NAMESPACE, root.path("namespace").asText(), "namespace should match");
                assertEquals("streamshub-pod-scraping", root.path("provider").asText());
                assertFalse(root.path("interpretation").isMissingNode(), "Should have interpretation text");
                assertFalse(root.path("timestamp").isMissingNode(), "Should have timestamp");
            })
            .thenAssertResults();
    }

    @Test
    @Story("get_kafka_metrics with replication category")
    void testGetKafkaMetricsReplication() {
        Map<String, Object> args = Map.of(
            "clusterName", Constants.KAFKA_CLUSTER_NAME,
            "category", "replication");
        mcpClient.when()
            .toolsCall("get_kafka_metrics", args, response -> {
                JsonNode root = assertToolSuccess(response);
                String text = response.content().getFirst().asText().text();

                LOGGER.info("get_kafka_metrics replication response (length={})", text.length());
                LOGGER.debug("get_kafka_metrics replication response:\n{}", text);

                assertEquals(Constants.KAFKA_CLUSTER_NAME, root.path("cluster_name").asText(), "cluster_name should match");
                assertEquals("streamshub-pod-scraping", root.path("provider").asText());
                assertTrue(root.path("categories").isArray(), "categories should be an array");
                assertFalse(root.path("interpretation").isMissingNode(), "Should have interpretation text");
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
    @Story("get_kafka_metrics returns error for non-existent cluster")
    void testGetKafkaMetricsNotFound() {
        Map<String, Object> args = Map.of("clusterName", "nonexistent-cluster-xyz");
        mcpClient.when()
            .toolsCall("get_kafka_metrics", args, response -> {
                LOGGER.info("get_kafka_metrics error response: {}",
                    response.content().getFirst().asText().text());

                assertToolError(response, "not found");
            })
            .thenAssertResults();
    }

    // ---- Kafka Exporter Metrics ----

    @Test
    @Story("get_kafka_exporter_metrics returns exporter metrics")
    void testGetKafkaExporterMetrics() {
        Map<String, Object> args = Map.of("clusterName", Constants.KAFKA_CLUSTER_NAME);
        mcpClient.when()
            .toolsCall("get_kafka_exporter_metrics", args, response -> {
                JsonNode root = assertToolSuccess(response);
                String text = response.content().getFirst().asText().text();

                LOGGER.info("get_kafka_exporter_metrics response (length={})", text.length());
                LOGGER.debug("get_kafka_exporter_metrics response:\n{}", text);

                assertEquals(Constants.KAFKA_CLUSTER_NAME, root.path("cluster_name").asText(), "cluster_name should match");
                assertEquals(Environment.KAFKA_NAMESPACE, root.path("namespace").asText());
                assertEquals(0, root.path("metric_count").asInt(), "metric_count should be 0 when no exporter pods");
                assertTrue(root.path("time_series").isArray() && root.path("time_series").isEmpty(), "time_series should be empty when no exporter pods");
                assertTrue(root.path("message").asText().contains("No Kafka Exporter pods found"), "message should indicate no exporter pods found");
            })
            .thenAssertResults();
    }

    // ---- KafkaBridge Metrics ----

    @Test
    @Story("get_kafka_bridge_metrics returns metrics for Bridge")
    void testGetKafkaBridgeMetrics() {
        Map<String, Object> args = Map.of(
            "bridgeName", Constants.BRIDGE_NAME);
        mcpClient.when()
            .toolsCall("get_kafka_bridge_metrics", args, response -> {
                JsonNode root = assertToolSuccess(response);
                String text = response.content().getFirst().asText().text();

                LOGGER.info("get_kafka_bridge_metrics response (length={})", text.length());
                LOGGER.debug("get_kafka_bridge_metrics response:\n{}", text);

                assertEquals(Constants.BRIDGE_NAME, root.path("bridge_name").asText(), "bridge_name should match");
                assertEquals(Environment.KAFKA_NAMESPACE, root.path("namespace").asText());
                assertEquals("streamshub-pod-scraping", root.path("provider").asText());
                assertFalse(root.path("interpretation").isMissingNode(), "Should have interpretation text");
            })
            .thenAssertResults();
    }

    // ---- KafkaConnect Metrics ----

    @Test
    @Story("get_kafka_connect_metrics returns metrics for Connect cluster")
    void testGetKafkaConnectMetrics() {
        Map<String, Object> args = Map.of(
            "connectName", Constants.CONNECT_CLUSTER_NAME);
        mcpClient.when()
            .toolsCall("get_kafka_connect_metrics", args, response -> {
                JsonNode root = assertToolSuccess(response);
                String text = response.content().getFirst().asText().text();

                LOGGER.info("get_kafka_connect_metrics response (length={})", text.length());
                LOGGER.debug("get_kafka_connect_metrics response:\n{}", text);

                assertEquals(Constants.CONNECT_CLUSTER_NAME, root.path("connect_name").asText(), "connect_name should match");
                assertEquals(Environment.KAFKA_NAMESPACE, root.path("namespace").asText());
                assertEquals("streamshub-pod-scraping", root.path("provider").asText());
                assertFalse(root.path("interpretation").isMissingNode(), "Should have interpretation text");
            })
            .thenAssertResults();
    }

    // ---- Strimzi Operator Metrics ----

    @Test
    @Story("get_strimzi_operator_metrics returns operator metrics")
    void testGetStrimziOperatorMetrics() {
        Map<String, Object> args = Map.of();
        mcpClient.when()
            .toolsCall("get_strimzi_operator_metrics", args, response -> {
                JsonNode root = assertToolSuccess(response);
                String text = response.content().getFirst().asText().text();

                LOGGER.info("get_strimzi_operator_metrics response (length={})", text.length());
                LOGGER.debug("get_strimzi_operator_metrics response:\n{}", text);

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

    // ---- Aggregation Levels ----

    @Test
    @Story("get_kafka_metrics with broker aggregation returns per-broker breakdown")
    void testGetKafkaMetricsBrokerAggregation() {
        Map<String, Object> args = Map.of(
            "clusterName", Constants.KAFKA_CLUSTER_NAME,
            "aggregation", "broker");
        mcpClient.when()
            .toolsCall("get_kafka_metrics", args, response -> {
                JsonNode root = assertToolSuccess(response);
                String text = response.content().getFirst().asText().text();

                LOGGER.info("get_kafka_metrics broker aggregation response (length={})", text.length());
                LOGGER.debug("get_kafka_metrics broker aggregation response:\n{}", text);

                assertEquals(Constants.KAFKA_CLUSTER_NAME, root.path("cluster_name").asText(), "cluster_name should match");
                assertEquals("broker", root.path("aggregation").asText(), "aggregation should be broker");
                assertEquals("streamshub-pod-scraping", root.path("provider").asText());
                assertFalse(root.path("interpretation").isMissingNode(), "Should have interpretation text");
            })
            .thenAssertResults();
    }

    @Test
    @Story("get_kafka_exporter_metrics with topic aggregation returns per-topic data")
    void testGetExporterMetricsTopicAggregation() {
        Map<String, Object> args = Map.of(
            "clusterName", Constants.KAFKA_CLUSTER_NAME,
            "category", "consumer_lag",
            "aggregation", "topic");
        mcpClient.when()
            .toolsCall("get_kafka_exporter_metrics", args, response -> {
                JsonNode root = assertToolSuccess(response);
                String text = response.content().getFirst().asText().text();

                LOGGER.info("get_kafka_exporter_metrics topic aggregation response (length={})",
                    text.length());
                LOGGER.debug("get_kafka_exporter_metrics topic aggregation response:\n{}", text);

                assertEquals(Constants.KAFKA_CLUSTER_NAME, root.path("cluster_name").asText(), "cluster_name should match");
                assertEquals(0, root.path("metric_count").asInt(), "metric_count should be 0 when no exporter pods");
                assertTrue(root.path("time_series").isArray() && root.path("time_series").isEmpty(), "time_series should be empty when no exporter pods");
                assertTrue(root.path("message").asText().contains("No Kafka Exporter pods found"), "message should indicate no exporter pods found");
            })
            .thenAssertResults();
    }

    @Test
    @Story("get_kafka_metrics with performance category and requestTypes filter")
    void testGetKafkaMetricsPerformanceWithRequestTypes() {
        Map<String, Object> args = Map.of(
            "clusterName", Constants.KAFKA_CLUSTER_NAME,
            "category", "performance",
            "requestTypes", "Produce,Fetch");
        mcpClient.when()
            .toolsCall("get_kafka_metrics", args, response -> {
                JsonNode root = assertToolSuccess(response);
                String text = response.content().getFirst().asText().text();

                LOGGER.info("get_kafka_metrics performance+requestTypes response (length={})",
                    text.length());
                LOGGER.debug("get_kafka_metrics performance+requestTypes response:\n{}", text);

                assertEquals(Constants.KAFKA_CLUSTER_NAME, root.path("cluster_name").asText(), "cluster_name should match");
                assertEquals("streamshub-pod-scraping", root.path("provider").asText());
                assertTrue(root.path("categories").isArray(), "categories should be an array");
                assertFalse(root.path("interpretation").isMissingNode(), "Should have interpretation text");
            })
            .thenAssertResults();
    }

    // ---- Time Range Validation ----

    @Test
    @Story("get_kafka_metrics rejects conflicting rangeMinutes and startTime")
    void testGetKafkaMetricsConflictingTimeParams() {
        Map<String, Object> args = Map.of(
            "clusterName", Constants.KAFKA_CLUSTER_NAME,
            "rangeMinutes", 30,
            "startTime", "2025-01-01T00:00:00Z");
        mcpClient.when()
            .toolsCall("get_kafka_metrics", args, response -> {

                assertToolError(response, "Cannot specify both");
            })
            .thenAssertResults();
    }

    // ---- Helpers ----
    private static boolean hasLabelWithKey(JsonNode timeSeries, String key) {
        for (JsonNode series : timeSeries) {
            JsonNode labels = series.path("labels");
            if (labels.isObject() && !labels.path(key).isMissingNode()) {
                return true;
            }
        }
        return false;
    }
}
