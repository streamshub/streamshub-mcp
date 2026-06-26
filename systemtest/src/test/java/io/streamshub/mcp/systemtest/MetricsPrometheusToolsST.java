/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.systemtest;

import com.fasterxml.jackson.databind.JsonNode;
import io.fabric8.kubernetes.api.model.Namespace;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import io.quarkiverse.mcp.server.test.McpAssured;
import io.skodjob.kubetest4j.annotations.ClassNamespace;
import io.skodjob.kubetest4j.annotations.InjectResourceManager;
import io.skodjob.kubetest4j.annotations.KubernetesTest;
import io.skodjob.kubetest4j.resources.KubeResourceManager;
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
 * System tests for Prometheus-backed metrics MCP tools.
 * Deploys a Kafka cluster with Bridge and Connect, configures the MCP server
 * to use the Prometheus metrics provider, then verifies that range-query
 * metrics retrieval tools return well-formed responses.
 */
@Epic("Strimzi MCP E2E")
@Feature("Prometheus Metrics Tools")
class MetricsPrometheusToolsST extends AbstractST {

    private static final Logger LOGGER = LoggerFactory.getLogger(MetricsPrometheusToolsST.class);
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

    private record PrometheusConfig(String url, String authMode, boolean trustAll) {
    }

    MetricsPrometheusToolsST() {
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

        PrometheusConfig promConfig = discoverPrometheus();
        LOGGER.info("Using Prometheus: url={}, auth={}, trustAll={}", promConfig.url(), promConfig.authMode(), promConfig.trustAll());

        McpServerSetup.Builder builder = McpServerSetup.builder(mcpNamespace.getMetadata().getName())
            .withEnv("MCP_METRICS_PROVIDER", "streamshub-prometheus")
            .withEnv("QUARKUS_REST_CLIENT_PROMETHEUS_URL", promConfig.url())
            .withEnv("MCP_METRICS_PROMETHEUS_AUTH_MODE", promConfig.authMode());

        if (promConfig.trustAll()) {
            builder.withEnv("QUARKUS_TLS_TRUST_ALL", "true");
        }

        builder.deploy();

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

    @Test
    @DisplayName("get_kafka_metrics returns error for non-existent cluster with range")
    @Story("Get Kafka Metrics")
    void testGetKafkaMetricsNotFoundRange() {
        Map<String, Object> args = Map.of(
            "clusterName", "nonexistent-cluster-xyz",
            "rangeMinutes", 30,
            "stepSeconds", 60);
        mcpClient.when()
            .toolsCall("get_kafka_metrics", args, response -> {
                assertTrue(response.isError(),
                    "Should return error for non-existent cluster");

                String text = response.content().getFirst().asText().text();
                LOGGER.info("get_kafka_metrics error range response: {}", text);

                assertTrue(text.toLowerCase(Locale.ROOT).contains("not found"),
                    "Error should mention 'not found'");
            })
            .thenAssertResults();
    }

    // ---- Kafka Exporter Metrics ----

    @Test
    @DisplayName("get_kafka_exporter_metrics returns exporter metrics with range")
    @Story("Get Kafka Exporter Metrics")
    void testGetKafkaExporterMetricsRange() {
        Map<String, Object> args = Map.of(
            "clusterName", Constants.KAFKA_CLUSTER_NAME,
            "rangeMinutes", 30,
            "stepSeconds", 60);
        mcpClient.when()
            .toolsCall("get_kafka_exporter_metrics", args, response -> {
                assertFalse(response.isError(), "get_kafka_exporter_metrics should not return error");

                String text = response.content().getFirst().asText().text();
                LOGGER.info("get_kafka_exporter_metrics range response (length={})", text.length());

                JsonNode root = parseJson(text);
                assertMetricsResponse(root, "cluster_name", Constants.KAFKA_CLUSTER_NAME);
            })
            .thenAssertResults();
    }

    // ---- KafkaBridge Metrics ----

    @Test
    @DisplayName("get_kafka_bridge_metrics returns metrics for Bridge with range")
    @Story("Get KafkaBridge Metrics")
    void testGetKafkaBridgeMetricsRange() {
        Map<String, Object> args = Map.of(
            "bridgeName", BRIDGE_NAME,
            "rangeMinutes", 30,
            "stepSeconds", 60);
        mcpClient.when()
            .toolsCall("get_kafka_bridge_metrics", args, response -> {
                assertFalse(response.isError(), "get_kafka_bridge_metrics should not return error");

                String text = response.content().getFirst().asText().text();
                LOGGER.info("get_kafka_bridge_metrics range response (length={})", text.length());

                JsonNode root = parseJson(text);
                assertMetricsResponse(root, "bridge_name", BRIDGE_NAME);
                assertEquals(Environment.KAFKA_NAMESPACE, root.path("namespace").asText(),
                    "namespace should match deployment namespace");
            })
            .thenAssertResults();
    }

    // ---- KafkaConnect Metrics ----

    @Test
    @DisplayName("get_kafka_connect_metrics returns metrics for Connect with range")
    @Story("Get KafkaConnect Metrics")
    void testGetKafkaConnectMetricsRange() {
        Map<String, Object> args = Map.of(
            "connectName", CONNECT_CLUSTER_NAME,
            "rangeMinutes", 30,
            "stepSeconds", 60);
        mcpClient.when()
            .toolsCall("get_kafka_connect_metrics", args, response -> {
                assertFalse(response.isError(), "get_kafka_connect_metrics should not return error");

                String text = response.content().getFirst().asText().text();
                LOGGER.info("get_kafka_connect_metrics range response (length={})", text.length());

                JsonNode root = parseJson(text);
                assertMetricsResponse(root, "connect_name", CONNECT_CLUSTER_NAME);
                assertEquals(Environment.KAFKA_NAMESPACE, root.path("namespace").asText(),
                    "namespace should match deployment namespace");
            })
            .thenAssertResults();
    }

    // ---- Strimzi Operator Metrics ----

    @Test
    @DisplayName("get_strimzi_operator_metrics returns operator metrics with range")
    @Story("Get Strimzi Operator Metrics")
    void testGetStrimziOperatorMetricsRange() {
        Map<String, Object> args = Map.of(
            "rangeMinutes", 30,
            "stepSeconds", 60);
        mcpClient.when()
            .toolsCall("get_strimzi_operator_metrics", args, response -> {
                assertFalse(response.isError(),
                    "get_strimzi_operator_metrics should not return error");

                String text = response.content().getFirst().asText().text();
                LOGGER.info("get_strimzi_operator_metrics range response (length={})", text.length());

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

    // ---- Prometheus Discovery ----

    private static PrometheusConfig discoverPrometheus() {
        if (Environment.PROMETHEUS_URL != null && !Environment.PROMETHEUS_URL.isBlank()) {
            String authMode = Environment.PROMETHEUS_AUTH_MODE != null ? Environment.PROMETHEUS_AUTH_MODE : "none";
            LOGGER.info("Using PROMETHEUS_URL override: {}", Environment.PROMETHEUS_URL);
            return new PrometheusConfig(Environment.PROMETHEUS_URL, authMode, false);
        }

        KubernetesClient client = KubeResourceManager.get().kubeClient().getClient();

        Service thanos = client.services()
            .inNamespace("openshift-monitoring")
            .withName("thanos-querier")
            .get();
        if (thanos != null) {
            String authMode = Environment.PROMETHEUS_AUTH_MODE != null ? Environment.PROMETHEUS_AUTH_MODE : "sa-token";
            LOGGER.info("Discovered OpenShift Thanos querier in openshift-monitoring namespace");
            return new PrometheusConfig(
                "https://thanos-querier.openshift-monitoring.svc:9091", authMode, true);
        }

        Service prometheus = client.services()
            .inNamespace("monitoring")
            .withName("prometheus-operated")
            .get();
        if (prometheus != null) {
            String authMode = Environment.PROMETHEUS_AUTH_MODE != null ? Environment.PROMETHEUS_AUTH_MODE : "none";
            LOGGER.info("Discovered Prometheus Operator service in monitoring namespace");
            return new PrometheusConfig(
                "http://prometheus-operated.monitoring.svc.cluster.local:9090", authMode, false);
        }

        throw new IllegalStateException(
            "No Prometheus service found. Deploy Prometheus (dev/scripts/setup-prometheus.sh) "
                + "or set PROMETHEUS_URL environment variable.");
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
