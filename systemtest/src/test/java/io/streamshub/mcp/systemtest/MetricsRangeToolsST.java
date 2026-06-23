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
import io.skodjob.kubetest4j.resources.KubeResourceManager;
import io.streamshub.mcp.systemtest.clients.McpClientFactory;
import io.streamshub.mcp.systemtest.setup.mcp.ConnectivitySetup;
import io.streamshub.mcp.systemtest.setup.mcp.McpServerSetup;
import io.streamshub.mcp.systemtest.setup.strimzi.StrimziSetup;
import io.streamshub.mcp.systemtest.templates.strimzi.KafkaNodePoolTemplates;
import io.streamshub.mcp.systemtest.templates.strimzi.KafkaTemplates;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * System tests for Prometheus range-query metrics MCP tools.
 * These tests use rangeMinutes/startTime/endTime parameters which
 * require a Prometheus server for historical data.
 */
@KubernetesTest(cleanup = CleanupStrategy.AUTOMATIC, collectLogs = true)
@DisplayName("Metrics Range Query MCP Tools")
@Epic("Strimzi MCP E2E")
@Feature("Metrics Range Query Tools")
class MetricsRangeToolsST extends AbstractST {

    private static final Logger LOGGER = LoggerFactory.getLogger(MetricsRangeToolsST.class);

    @InjectResourceManager
    KubeResourceManager krm;

    @ClassNamespace(name = Constants.MCP_NAMESPACE)
    static Namespace mcpNamespace;

    @ClassNamespace(name = Constants.STRIMZI_NAMESPACE, labels = {"app=strimzi"})
    static Namespace strimziNamespace;

    @ClassNamespace(name = Constants.KAFKA_NAMESPACE, labels = {"app=strimzi"})
    static Namespace kafkaNamespace;

    private static McpAssured.McpStreamableTestClient mcpClient;

    MetricsRangeToolsST() {
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

    @Test
    @DisplayName("get_kafka_metrics with throughput category and time range")
    @Story("Get Kafka Metrics")
    void testGetKafkaMetricsTimeRange() {
        Map<String, Object> args = Map.of(
            "clusterName", Constants.KAFKA_CLUSTER_NAME,
            "category", "throughput",
            "rangeMinutes", 30,
            "stepSeconds", 60);
        mcpClient.when()
            .toolsCall("get_kafka_metrics", args, response -> {
                assertFalse(response.isError(), "get_kafka_metrics with time range should not return error");

                String text = response.content().getFirst().asText().text();
                LOGGER.info("get_kafka_metrics time range response (length={})", text.length());

                JsonNode root = parseJson(text);
                assertMetricsResponse(root, "cluster_name", Constants.KAFKA_CLUSTER_NAME);

                boolean hasThroughput = false;
                for (JsonNode cat : root.path("categories")) {
                    if ("throughput".equals(cat.asText())) {
                        hasThroughput = true;
                        break;
                    }
                }
                assertTrue(hasThroughput, "categories should contain 'throughput'");
            })
            .thenAssertResults();
    }

    @Test
    @DisplayName("get_kafka_metrics returns metrics with short range")
    @Story("Get Kafka Metrics")
    void testGetKafkaMetricsShortRange() {
        Map<String, Object> args = Map.of(
            "clusterName", Constants.KAFKA_CLUSTER_NAME,
            "rangeMinutes", 1,
            "stepSeconds", 10);
        mcpClient.when()
            .toolsCall("get_kafka_metrics", args, response -> {
                assertFalse(response.isError(), "get_kafka_metrics should not return error");

                String text = response.content().getFirst().asText().text();
                LOGGER.info("get_kafka_metrics (short range) response (length={})", text.length());

                JsonNode root = parseJson(text);
                assertMetricsResponse(root, "cluster_name", Constants.KAFKA_CLUSTER_NAME);
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
