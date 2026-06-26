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
import io.skodjob.kubetest4j.annotations.InjectResourceManager;
import io.skodjob.kubetest4j.annotations.KubernetesTest;
import io.skodjob.kubetest4j.resources.KubeResourceManager;
import io.streamshub.mcp.systemtest.clients.McpClientFactory;
import io.streamshub.mcp.systemtest.setup.mcp.ConnectivitySetup;
import io.streamshub.mcp.systemtest.setup.mcp.McpServerSetup;
import io.streamshub.mcp.systemtest.setup.strimzi.StrimziSetup;
import io.streamshub.mcp.systemtest.templates.strimzi.KafkaConnectTemplates;
import io.streamshub.mcp.systemtest.templates.strimzi.KafkaConnectorTemplates;
import io.streamshub.mcp.systemtest.templates.strimzi.KafkaNodePoolTemplates;
import io.streamshub.mcp.systemtest.templates.strimzi.KafkaTemplates;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

import static io.streamshub.mcp.systemtest.templates.strimzi.KafkaConnectorTemplates.CONNECTOR_NAME;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * System tests for all diagnose-related MCP tools.
 * Deploys a Kafka cluster with Connect and Connector, then verifies
 * that diagnostic tools return well-structured reports.
 */
@Epic("Strimzi MCP E2E")
@Feature("Diagnose Tools")
class DiagnoseToolsST extends AbstractST {

    private static final Logger LOGGER = LoggerFactory.getLogger(DiagnoseToolsST.class);
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

    DiagnoseToolsST() {
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
                KafkaConnectTemplates.kafkaConnect(
                    kafkaNs, CONNECT_CLUSTER_NAME, Constants.KAFKA_CLUSTER_NAME, 1).build());

            krm.createOrUpdateResourceWithWait(
                KafkaConnectorTemplates.camelTimerSource(
                    kafkaNs, CONNECTOR_NAME, CONNECT_CLUSTER_NAME, "test-topic").build());
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

    // ---- Kafka Cluster Diagnostics ----

    @Test
    @DisplayName("diagnose_kafka_cluster returns diagnostic info for cluster")
    @Story("Diagnose Kafka Cluster")
    void testDiagnoseKafkaCluster() {
        Map<String, Object> args = Map.of("clusterName", Constants.KAFKA_CLUSTER_NAME);
        mcpClient.when()
            .toolsCall("diagnose_kafka_cluster", args, response -> {
                assertFalse(response.isError(), "diagnose_kafka_cluster should not return error");

                String text = response.content().getFirst().asText().text();
                LOGGER.info("diagnose_kafka_cluster response (length={})", text.length());

                JsonNode root = parseJson(text);
                assertDiagnosticReport(root);

                JsonNode cluster = root.path("cluster");
                assertFalse(cluster.isMissingNode(), "Should have cluster section");
                assertEquals(Constants.KAFKA_CLUSTER_NAME, cluster.path("name").asText(),
                    "Cluster name should match");
                assertEquals("Ready", cluster.path("readiness").asText(),
                    "Cluster should be Ready");

                assertFalse(root.path("pods").isMissingNode(), "Should have pods section");

                JsonNode nodePools = root.path("node_pools");
                assertTrue(nodePools.isArray(), "node_pools should be an array");
                assertEquals(2, nodePools.size(),
                    "Should have 2 node pools (controller-np + broker-np)");

                assertFalse(root.path("operator").isMissingNode(), "Should have operator section");

                assertFalse(root.path("operator_logs").isMissingNode(),
                    "Should have operator_logs section");
                assertFalse(root.path("cluster_logs").isMissingNode(),
                    "Should have cluster_logs section");
                assertFalse(root.path("events").isMissingNode(),
                    "Should have events section");
                assertFalse(root.path("metrics").isMissingNode(),
                    "Should have metrics section");
            })
            .thenAssertResults();
    }

    @Test
    @DisplayName("diagnose_kafka_connectivity returns connectivity diagnosis")
    @Story("Diagnose Kafka Connectivity")
    void testDiagnoseKafkaConnectivity() {
        Map<String, Object> args = Map.of("clusterName", Constants.KAFKA_CLUSTER_NAME);
        mcpClient.when()
            .toolsCall("diagnose_kafka_connectivity", args, response -> {
                assertFalse(response.isError(), "diagnose_kafka_connectivity should not return error");

                String text = response.content().getFirst().asText().text();
                LOGGER.info("diagnose_kafka_connectivity response (length={})", text.length());

                JsonNode root = parseJson(text);
                assertDiagnosticReport(root);

                JsonNode cluster = root.path("cluster");
                assertFalse(cluster.isMissingNode(), "Should have cluster section");
                assertEquals(Constants.KAFKA_CLUSTER_NAME, cluster.path("name").asText(),
                    "Cluster name should match");
                assertEquals("Ready", cluster.path("readiness").asText(),
                    "Cluster should be Ready");

                assertFalse(root.path("bootstrap_servers").isMissingNode(),
                    "Should have bootstrap_servers section");
                assertFalse(root.path("pods").isMissingNode(), "Should have pods section");

                assertFalse(root.path("certificates").isMissingNode(),
                    "Should have certificates section");
                assertFalse(root.path("cluster_logs").isMissingNode(),
                    "Should have cluster_logs section");
            })
            .thenAssertResults();
    }

    @Test
    @DisplayName("diagnose_kafka_cluster with symptom returns diagnostic info")
    @Story("Diagnose Kafka Cluster")
    void testDiagnoseKafkaClusterWithSymptom() {
        Map<String, Object> args = Map.of(
            "clusterName", Constants.KAFKA_CLUSTER_NAME,
            "symptom", "high latency",
            "sinceMinutes", 60);
        mcpClient.when()
            .toolsCall("diagnose_kafka_cluster", args, response -> {
                assertFalse(response.isError(),
                    "diagnose_kafka_cluster with symptom should not return error");

                String text = response.content().getFirst().asText().text();
                LOGGER.info("diagnose_kafka_cluster with symptom response (length={})", text.length());

                JsonNode root = parseJson(text);
                assertDiagnosticReport(root);
            })
            .thenAssertResults();
    }

    @Test
    @DisplayName("diagnose_kafka_connectivity with listener filter")
    @Story("Diagnose Kafka Connectivity")
    void testDiagnoseKafkaConnectivityListener() {
        Map<String, Object> args = Map.of(
            "clusterName", Constants.KAFKA_CLUSTER_NAME,
            "listenerName", "tls");
        mcpClient.when()
            .toolsCall("diagnose_kafka_connectivity", args, response -> {
                assertFalse(response.isError(),
                    "diagnose_kafka_connectivity with listener should not return error");

                String text = response.content().getFirst().asText().text();
                LOGGER.info("diagnose_kafka_connectivity with listener response (length={})", text.length());

                JsonNode root = parseJson(text);
                assertDiagnosticReport(root);
            })
            .thenAssertResults();
    }

    // ---- Kafka Metrics Diagnostics ----

    @Test
    @DisplayName("diagnose_kafka_metrics returns metrics diagnosis for cluster")
    @Story("Diagnose Kafka Metrics")
    void testDiagnoseKafkaMetrics() {
        Map<String, Object> args = Map.of("clusterName", Constants.KAFKA_CLUSTER_NAME);
        mcpClient.when()
            .toolsCall("diagnose_kafka_metrics", args, response -> {
                assertFalse(response.isError(), "diagnose_kafka_metrics should not return error");

                String text = response.content().getFirst().asText().text();
                LOGGER.info("diagnose_kafka_metrics response (length={})", text.length());

                JsonNode root = parseJson(text);
                assertDiagnosticReport(root);

                assertFalse(root.path("cluster").isMissingNode(), "Should have cluster section");
                assertEquals(Constants.KAFKA_CLUSTER_NAME,
                    root.path("cluster").path("name").asText(),
                    "Cluster name should match");
                assertFalse(root.path("pods").isMissingNode(), "Should have pods section");

                assertFalse(root.path("replication_metrics").isMissingNode(),
                    "Should have replication_metrics section");
                assertFalse(root.path("performance_metrics").isMissingNode(),
                    "Should have performance_metrics section");
                assertFalse(root.path("resource_metrics").isMissingNode(),
                    "Should have resource_metrics section");
                assertFalse(root.path("throughput_metrics").isMissingNode(),
                    "Should have throughput_metrics section");
            })
            .thenAssertResults();
    }

    // ---- KafkaConnect Diagnostics ----

    @Test
    @DisplayName("diagnose_kafka_connect returns diagnostic info")
    @Story("Diagnose KafkaConnect")
    void testDiagnoseKafkaConnect() {
        Map<String, Object> args = Map.of(
            "connectName", CONNECT_CLUSTER_NAME);
        mcpClient.when()
            .toolsCall("diagnose_kafka_connect", args, response -> {
                assertFalse(response.isError(), "diagnose_kafka_connect should not return error");

                String text = response.content().getFirst().asText().text();
                LOGGER.info("diagnose_kafka_connect response (length={})", text.length());

                JsonNode root = parseJson(text);
                assertDiagnosticReport(root);

                JsonNode connectCluster = root.path("connect_cluster");
                assertFalse(connectCluster.isMissingNode(),
                    "Should have connect_cluster section");
                assertEquals(CONNECT_CLUSTER_NAME, connectCluster.path("name").asText(),
                    "Connect cluster name should match");
                assertEquals("Ready", connectCluster.path("readiness").asText(),
                    "Connect cluster should be Ready");

                assertTrue(root.path("connectors").isArray(),
                    "connectors should be an array");

                assertFalse(root.path("pods").isMissingNode(), "Should have pods section");

                assertFalse(root.path("logs").isMissingNode(),
                    "Should have logs section");
                assertFalse(root.path("connect_metrics").isMissingNode(),
                    "Should have connect_metrics section");
                assertFalse(root.path("events").isMissingNode(),
                    "Should have events section");
            })
            .thenAssertResults();
    }

    @Test
    @DisplayName("diagnose_kafka_connector returns diagnostic info")
    @Story("Diagnose KafkaConnector")
    void testDiagnoseKafkaConnector() {
        Map<String, Object> args = Map.of(
            "connectorName", CONNECTOR_NAME);
        mcpClient.when()
            .toolsCall("diagnose_kafka_connector", args, response -> {
                assertFalse(response.isError(), "diagnose_kafka_connector should not return error");

                String text = response.content().getFirst().asText().text();
                LOGGER.info("diagnose_kafka_connector response (length={})", text.length());

                JsonNode root = parseJson(text);
                assertDiagnosticReport(root);

                assertFalse(root.path("connector").isMissingNode(),
                    "Should have connector section");
                assertEquals(CONNECTOR_NAME,
                    root.path("connector").path("name").asText(),
                    "Connector name should match");

                JsonNode connectCluster = root.path("connect_cluster");
                assertFalse(connectCluster.isMissingNode(),
                    "Should have connect_cluster section");
                assertEquals(CONNECT_CLUSTER_NAME, connectCluster.path("name").asText(),
                    "Connect cluster name should match");

                assertFalse(root.path("connect_pods").isMissingNode(),
                    "Should have connect_pods section");
                assertFalse(root.path("connect_logs").isMissingNode(),
                    "Should have connect_logs section");
                assertFalse(root.path("events").isMissingNode(),
                    "Should have events section");
            })
            .thenAssertResults();
    }

    // ---- Strimzi Operator Diagnostics ----

    @Test
    @DisplayName("diagnose_operator_metrics returns operator metrics diagnosis")
    @Story("Diagnose Operator Metrics")
    void testDiagnoseOperatorMetrics() {
        Map<String, Object> args = Map.of();
        mcpClient.when()
            .toolsCall("diagnose_operator_metrics", args, response -> {
                assertFalse(response.isError(),
                    "diagnose_operator_metrics should not return error");

                String text = response.content().getFirst().asText().text();
                LOGGER.info("diagnose_operator_metrics response (length={})", text.length());

                JsonNode root = parseJson(text);
                assertDiagnosticReport(root);

                assertFalse(root.path("operator").isMissingNode(),
                    "Should have operator section");
                assertFalse(root.path("operator").path("name").asText("").isEmpty(),
                    "Operator name should be non-empty");
                assertFalse(root.path("operator_logs").isMissingNode(),
                    "Should have operator_logs section");

                assertFalse(root.path("reconciliation_metrics").isMissingNode(),
                    "Should have reconciliation_metrics section");
                assertFalse(root.path("resource_metrics").isMissingNode(),
                    "Should have resource_metrics section");
                assertFalse(root.path("jvm_metrics").isMissingNode(),
                    "Should have jvm_metrics section");
            })
            .thenAssertResults();
    }

    // ---- Upgrade Readiness ----

    @Test
    @DisplayName("assess_upgrade_readiness returns upgrade assessment")
    @Story("Assess Upgrade Readiness")
    void testAssessUpgradeReadiness() {
        Map<String, Object> args = Map.of(
            "clusterName", Constants.KAFKA_CLUSTER_NAME);
        mcpClient.when()
            .toolsCall("assess_upgrade_readiness", args, response -> {
                assertFalse(response.isError(), "assess_upgrade_readiness should not return error");

                String text = response.content().getFirst().asText().text();
                LOGGER.info("assess_upgrade_readiness response (length={})", text.length());

                JsonNode root = parseJson(text);
                assertDiagnosticReport(root);
            })
            .thenAssertResults();
    }

    private static void assertDiagnosticReport(JsonNode root) {
        JsonNode steps = root.path("steps_completed");
        assertTrue(steps.isArray() && !steps.isEmpty(),
            "steps_completed should be a non-empty array");
        assertFalse(root.path("timestamp").isMissingNode(), "Should have timestamp");
        assertFalse(root.path("message").isMissingNode(), "Should have message");
    }
}
