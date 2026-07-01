/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.systemtest.tools;

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
import io.streamshub.mcp.systemtest.templates.strimzi.KafkaConnectTemplates;
import io.streamshub.mcp.systemtest.templates.strimzi.KafkaConnectorTemplates;
import io.streamshub.mcp.systemtest.templates.strimzi.KafkaNodePoolTemplates;
import io.streamshub.mcp.systemtest.templates.strimzi.KafkaTemplates;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

import static io.streamshub.mcp.systemtest.TestTags.ACCEPTANCE;
import static io.streamshub.mcp.systemtest.TestTags.LOGS;
import static io.streamshub.mcp.systemtest.TestTags.METRICS;
import static io.streamshub.mcp.systemtest.TestTags.REGRESSION;
import static io.streamshub.mcp.systemtest.TestTags.TOOLS;
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
@Tag(ACCEPTANCE)
@Tag(REGRESSION)
@Tag(TOOLS)
@Tag(METRICS)
@Tag(LOGS)
class DiagnoseToolsST extends AbstractST {

    private static final Logger LOGGER = LoggerFactory.getLogger(DiagnoseToolsST.class);

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
            KafkaConnectTemplates.deployMetricsConfigMap(kafkaNs);

            krm.createOrUpdateResourceWithoutWait(
                KafkaNodePoolTemplates.controllerPool(kafkaNs, "controller-np",
                    Constants.KAFKA_CLUSTER_NAME, 1).build(),
                KafkaNodePoolTemplates.brokerPool(kafkaNs, "broker-np",
                    Constants.KAFKA_CLUSTER_NAME, 1).build());

            krm.createOrUpdateResourceWithWait(
                KafkaTemplates.kafkaWithMetrics(kafkaNs, Constants.KAFKA_CLUSTER_NAME, 1).build());

            krm.createOrUpdateResourceWithWait(
                KafkaConnectTemplates.kafkaConnect(
                    kafkaNs, Constants.CONNECT_CLUSTER_NAME, Constants.KAFKA_CLUSTER_NAME, 1).build());
            
            krm.createOrUpdateResourceWithWait(
                KafkaConnectorTemplates.camelTimerSource(
                    kafkaNs, CONNECTOR_NAME, Constants.CONNECT_CLUSTER_NAME, "test-topic").build());
        }

        McpServerSetup.deploy(mcpNamespace.getMetadata().getName());
        McpServerSetup.deploySensitiveRbac(
            mcpNamespace.getMetadata().getName(),
            kafkaNamespace.getMetadata().getName());
        McpServerSetup.deploySensitiveRbac(
            mcpNamespace.getMetadata().getName(),
            strimziNamespace.getMetadata().getName());

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
    @Story("diagnose_kafka_cluster returns diagnostic info for cluster")
    void testDiagnoseKafkaCluster() {
        Map<String, Object> args = Map.of("clusterName", Constants.KAFKA_CLUSTER_NAME);

        mcpClient.when()
            .toolsCall("diagnose_kafka_cluster", args, response -> {
                JsonNode root = assertToolSuccess(response);

                String text = response.content().getFirst().asText().text();
                LOGGER.info("diagnose_kafka_cluster response (length={})", text.length());
                LOGGER.debug("diagnose_kafka_cluster response:\n{}", text);
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
                assertEquals("4.2.0", root.path("cluster").path("kafka_version").asText(), "Kafka version should be 4.2.0");
                assertEquals(4, root.path("pods").path("pod_summary").path("total_pods").asInt(), "Should have 4 total pods");
                assertEquals("HEALTHY", root.path("pods").path("pod_summary").path("health_status").asText(), "Pod health should be HEALTHY");
                assertEquals("strimzi-cluster-operator", root.path("operator").path("name").asText(), "Operator name should match");
                assertEquals("HEALTHY", root.path("operator").path("status").asText(), "Operator status should be HEALTHY");
                assertEquals(11, root.path("steps_completed").size(), "Should have 11 completed steps");
            })
            .thenAssertResults();
    }

    @Test
    @Story("diagnose_kafka_connectivity returns connectivity diagnosis")
    void testDiagnoseKafkaConnectivity() {
        Map<String, Object> args = Map.of("clusterName", Constants.KAFKA_CLUSTER_NAME);

        mcpClient.when()
            .toolsCall("diagnose_kafka_connectivity", args, response -> {
                JsonNode root = assertToolSuccess(response);

                String text = response.content().getFirst().asText().text();
                LOGGER.info("diagnose_kafka_connectivity response (length={})", text.length());
                LOGGER.debug("diagnose_kafka_connectivity response:\n{}", text);
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
                assertEquals("success", root.path("bootstrap_servers").path("status").asText(), "Bootstrap servers status should be success");
                assertEquals(2, root.path("bootstrap_servers").path("bootstrap_servers").size(), "Should have 2 bootstrap servers");
                assertEquals(6, root.path("steps_completed").size(), "Should have 6 completed steps");
            })
            .thenAssertResults();
    }

    @Test
    @Story("diagnose_kafka_cluster with symptom returns diagnostic info")
    void testDiagnoseKafkaClusterWithSymptom() {
        Map<String, Object> args = Map.of(
            "clusterName", Constants.KAFKA_CLUSTER_NAME,
            "symptom", "high latency",
            "sinceMinutes", 60);

        mcpClient.when()
            .toolsCall("diagnose_kafka_cluster", args, response -> {
                JsonNode root = assertToolSuccess(response);

                LOGGER.info("diagnose_kafka_cluster with symptom response (length={})",
                    response.content().getFirst().asText().text().length());
                LOGGER.debug("diagnose_kafka_cluster with symptom response:\n{}", response.content().getFirst().asText().text());
                assertDiagnosticReport(root);
                assertFalse(root.path("cluster").isMissingNode(),
                    "Should have cluster section");
                assertEquals(Constants.KAFKA_CLUSTER_NAME,
                    root.path("cluster").path("name").asText(),
                    "Cluster name should match");
                assertEquals("Ready", root.path("cluster").path("readiness").asText(), "Cluster should be Ready");
                assertEquals(2, root.path("node_pools").size(), "Should have 2 node pools");
                assertEquals(4, root.path("pods").path("pod_summary").path("total_pods").asInt(), "Should have 4 total pods");
                assertEquals("HEALTHY", root.path("operator").path("status").asText(), "Operator should be HEALTHY");
                assertEquals(11, root.path("steps_completed").size(), "Should have 11 completed steps");
            })
            .thenAssertResults();
    }

    @Test
    @Story("diagnose_kafka_connectivity with listener filter")
    void testDiagnoseKafkaConnectivityListener() {
        Map<String, Object> args = Map.of(
            "clusterName", Constants.KAFKA_CLUSTER_NAME,
            "listenerName", "tls");

        mcpClient.when()
            .toolsCall("diagnose_kafka_connectivity", args, response -> {
                JsonNode root = assertToolSuccess(response);

                LOGGER.info("diagnose_kafka_connectivity with listener response (length={})",
                    response.content().getFirst().asText().text().length());
                LOGGER.debug("diagnose_kafka_connectivity with listener response:\n{}", response.content().getFirst().asText().text());
                assertDiagnosticReport(root);
                assertFalse(root.path("cluster").isMissingNode(),
                    "Should have cluster section");
                assertFalse(root.path("bootstrap_servers").isMissingNode(),
                    "Should have bootstrap_servers section");
                assertFalse(root.path("certificates").isMissingNode(),
                    "Should have certificates section");
                assertEquals(Constants.KAFKA_CLUSTER_NAME, root.path("cluster").path("name").asText(), "Cluster name should match");
                assertEquals("Ready", root.path("cluster").path("readiness").asText(), "Cluster should be Ready");
                assertEquals(2, root.path("bootstrap_servers").path("bootstrap_servers").size(), "Should have 2 bootstrap servers");
                assertEquals(6, root.path("steps_completed").size(), "Should have 6 completed steps");
            })
            .thenAssertResults();
    }

    // ---- Kafka Metrics Diagnostics ----

    @Test
    @Story("diagnose_kafka_metrics returns metrics diagnosis for cluster")
    void testDiagnoseKafkaMetrics() {
        Map<String, Object> args = Map.of("clusterName", Constants.KAFKA_CLUSTER_NAME);

        mcpClient.when()
            .toolsCall("diagnose_kafka_metrics", args, response -> {
                JsonNode root = assertToolSuccess(response);

                String text = response.content().getFirst().asText().text();
                LOGGER.info("diagnose_kafka_metrics response (length={})", text.length());
                LOGGER.debug("diagnose_kafka_metrics response:\n{}", text);
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
                assertEquals("streamshub-pod-scraping", root.path("replication_metrics").path("provider").asText(), "Provider should be streamshub-pod-scraping");
                assertEquals(6, root.path("steps_completed").size(), "Should have 6 completed steps");
            })
            .thenAssertResults();
    }

    // ---- KafkaConnect Diagnostics ----

    @Test
    @Story("diagnose_kafka_connect returns diagnostic info")
    void testDiagnoseKafkaConnect() {
        Map<String, Object> args = Map.of(
            "connectName", Constants.CONNECT_CLUSTER_NAME);

        mcpClient.when()
            .toolsCall("diagnose_kafka_connect", args, response -> {
                JsonNode root = assertToolSuccess(response);

                String text = response.content().getFirst().asText().text();
                LOGGER.info("diagnose_kafka_connect response (length={})", text.length());
                LOGGER.debug("diagnose_kafka_connect response:\n{}", text);
                assertDiagnosticReport(root);
                JsonNode connectCluster = root.path("connect_cluster");
                assertFalse(connectCluster.isMissingNode(),
                    "Should have connect_cluster section");
                assertEquals(Constants.CONNECT_CLUSTER_NAME, connectCluster.path("name").asText(),
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
                assertEquals(1, root.path("connectors").size(), "Should have 1 connector");
                assertEquals(1, root.path("pods").path("pod_summary").path("total_pods").asInt(), "Should have 1 connect pod");
                assertEquals("HEALTHY", root.path("pods").path("pod_summary").path("health_status").asText(), "Pod health should be HEALTHY");
                assertEquals(6, root.path("steps_completed").size(), "Should have 6 completed steps");
            })
            .thenAssertResults();
    }

    @Test
    @Story("diagnose_kafka_connector returns diagnostic info")
    void testDiagnoseKafkaConnector() {
        Map<String, Object> args = Map.of(
            "connectorName", CONNECTOR_NAME);

        mcpClient.when()
            .toolsCall("diagnose_kafka_connector", args, response -> {
                JsonNode root = assertToolSuccess(response);

                String text = response.content().getFirst().asText().text();
                LOGGER.info("diagnose_kafka_connector response (length={})", text.length());
                LOGGER.debug("diagnose_kafka_connector response:\n{}", text);
                assertDiagnosticReport(root);
                assertFalse(root.path("connector").isMissingNode(),
                    "Should have connector section");
                assertEquals(CONNECTOR_NAME,
                    root.path("connector").path("name").asText(),
                    "Connector name should match");
                JsonNode connectCluster = root.path("connect_cluster");
                assertFalse(connectCluster.isMissingNode(),
                    "Should have connect_cluster section");
                assertEquals(Constants.CONNECT_CLUSTER_NAME, connectCluster.path("name").asText(),
                    "Connect cluster name should match");
                assertFalse(root.path("connect_pods").isMissingNode(),
                    "Should have connect_pods section");
                assertFalse(root.path("connect_logs").isMissingNode(),
                    "Should have connect_logs section");
                assertFalse(root.path("events").isMissingNode(),
                    "Should have events section");
                assertEquals("running", root.path("connector").path("state").asText(), "Connector state should be running");
                assertEquals("Ready", root.path("connector").path("readiness").asText(), "Connector readiness should be Ready");
                assertFalse(root.path("connect_logs").path("has_errors").asBoolean(), "Connect logs should have no errors");
                assertEquals(5, root.path("steps_completed").size(), "Should have 5 completed steps");
            })
            .thenAssertResults();
    }

    // ---- Strimzi Operator Diagnostics ----

    @Test
    @Story("diagnose_operator_metrics returns operator metrics diagnosis")
    void testDiagnoseOperatorMetrics() {
        mcpClient.when()
            .toolsCall("diagnose_operator_metrics", Map.of(), response -> {
                JsonNode root = assertToolSuccess(response);

                String text = response.content().getFirst().asText().text();
                LOGGER.info("diagnose_operator_metrics response (length={})", text.length());
                LOGGER.debug("diagnose_operator_metrics response:\n{}", text);
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
                assertEquals("strimzi-cluster-operator", root.path("operator").path("name").asText(), "Operator name should match");
                assertTrue(root.path("operator").path("ready").asBoolean(), "Operator should be ready");
                assertEquals("HEALTHY", root.path("operator").path("status").asText(), "Operator status should be HEALTHY");
                assertEquals(5, root.path("steps_completed").size(), "Should have 5 completed steps");
            })
            .thenAssertResults();
    }

    // ---- Upgrade Readiness ----

    @Test
    @Story("assess_upgrade_readiness returns upgrade assessment")
    void testAssessUpgradeReadiness() {
        Map<String, Object> args = Map.of(
            "clusterName", Constants.KAFKA_CLUSTER_NAME);

        mcpClient.when()
            .toolsCall("assess_upgrade_readiness", args, response -> {
                JsonNode root = assertToolSuccess(response);

                LOGGER.info("assess_upgrade_readiness response (length={})",
                    response.content().getFirst().asText().text().length());
                LOGGER.debug("assess_upgrade_readiness response:\n{}", response.content().getFirst().asText().text());
                assertDiagnosticReport(root);
                assertFalse(root.path("cluster").isMissingNode(),
                    "Should have cluster section");
                assertEquals(Constants.KAFKA_CLUSTER_NAME,
                    root.path("cluster").path("name").asText(),
                    "Cluster name should match");
                assertEquals("Ready", root.path("cluster").path("readiness").asText(), "Cluster should be Ready");
                assertTrue(root.path("operator").path("ready").asBoolean(), "Operator should be ready");
                assertEquals("HEALTHY", root.path("operator").path("status").asText(), "Operator should be HEALTHY");
                assertEquals(2, root.path("node_pools").size(), "Should have 2 node pools");
                assertEquals(4, root.path("pods").path("pod_summary").path("total_pods").asInt(), "Should have 4 total pods");
                assertEquals(10, root.path("steps_completed").size(), "Should have 10 completed steps");
            })
            .thenAssertResults();
    }
}
