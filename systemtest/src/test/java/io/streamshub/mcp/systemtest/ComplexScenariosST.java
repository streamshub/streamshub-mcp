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
import io.skodjob.kubetest4j.resources.KubeResourceManager;
import io.streamshub.mcp.systemtest.clients.McpClientFactory;
import io.streamshub.mcp.systemtest.setup.mcp.ConnectivitySetup;
import io.streamshub.mcp.systemtest.setup.mcp.McpServerSetup;
import io.streamshub.mcp.systemtest.setup.strimzi.StrimziSetup;
import io.streamshub.mcp.systemtest.templates.strimzi.KafkaBridgeTemplates;
import io.streamshub.mcp.systemtest.templates.strimzi.KafkaConnectTemplates;
import io.streamshub.mcp.systemtest.templates.strimzi.KafkaConnectorTemplates;
import io.streamshub.mcp.systemtest.templates.strimzi.KafkaNodePoolTemplates;
import io.streamshub.mcp.systemtest.templates.strimzi.KafkaTemplates;
import io.streamshub.mcp.systemtest.templates.strimzi.KafkaTopicTemplates;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static io.streamshub.mcp.systemtest.templates.strimzi.KafkaConnectorTemplates.CONNECTOR_NAME;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * System tests for complex scenarios and multi-step E2E workflows.
 * Deploys the MCP server with a full Kafka ecosystem (Kafka, KafkaConnect,
 * KafkaConnector, KafkaBridge, KafkaTopic) and verifies diagnostic tools
 * and multi-step investigation workflows.
 */
@Epic("Strimzi MCP E2E")
@Feature("Complex Scenarios")
class ComplexScenariosST extends AbstractST {

    private static final Logger LOGGER = LoggerFactory.getLogger(ComplexScenariosST.class);
    private static final String CONNECT_CLUSTER_NAME = "mcp-connect";
    private static final String BRIDGE_NAME = "mcp-bridge";
    private static final String TOPIC_NAME = "mcp-test-topic";

    @InjectResourceManager
    KubeResourceManager krm;

    @ClassNamespace(name = Constants.MCP_NAMESPACE)
    static Namespace mcpNamespace;

    @ClassNamespace(name = Constants.STRIMZI_NAMESPACE, labels = {"app=strimzi"})
    static Namespace strimziNamespace;

    @ClassNamespace(name = Constants.KAFKA_NAMESPACE, labels = {"app=strimzi"})
    static Namespace kafkaNamespace;

    private static McpAssured.McpStreamableTestClient mcpClient;

    ComplexScenariosST() {
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

            krm.createOrUpdateResourceWithWait(
                KafkaTopicTemplates.topic(kafkaNs, TOPIC_NAME,
                    Constants.KAFKA_CLUSTER_NAME, 3, 1).build());

            krm.createOrUpdateResourceWithWait(
                KafkaConnectTemplates.kafkaConnect(
                    kafkaNs, CONNECT_CLUSTER_NAME, Constants.KAFKA_CLUSTER_NAME, 1).build());

            krm.createOrUpdateResourceWithWait(
                KafkaConnectorTemplates.camelTimerSource(
                    kafkaNs, CONNECTOR_NAME, CONNECT_CLUSTER_NAME, "test-topic").build());

            krm.createOrUpdateResourceWithWait(
                KafkaBridgeTemplates.kafkaBridge(
                    kafkaNs, BRIDGE_NAME, Constants.KAFKA_CLUSTER_NAME, 1).build());
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

    // ---- Multi-Step E2E Workflows ----

    @Test
    @DisplayName("Cluster health check workflow")
    @Story("Cluster Health Check")
    void testClusterHealthCheck() {
        String ns = kafkaNamespace.getMetadata().getName();

        // Step 1: Fleet overview
        mcpClient.when()
            .toolsCall("get_kafka_fleet_overview", Map.of("namespace", ns), response -> {
                assertFalse(response.isError(), "Step 1: get_kafka_fleet_overview should not return error");
                assertFalse(response.content().isEmpty(), "Fleet overview content should not be empty");
                String text = response.content().getFirst().asText().text();
                LOGGER.info("Step 1 - get_kafka_fleet_overview response (length={})", text.length());
                LOGGER.debug("Step 1 - get_kafka_fleet_overview response:\n{}", text);

                JsonNode root = parseJson(text);
                assertTrue(root.path("total_clusters").asInt() >= 1,
                    "Should have at least 1 cluster");
                JsonNode statusDist = root.path("status_distribution");
                assertFalse(statusDist.isMissingNode(), "Should have status_distribution");
                assertTrue(statusDist.path("ready").asInt() >= 1,
                    "Should have at least 1 ready cluster");
            })
            .thenAssertResults();

        // Step 2: Cluster overview
        mcpClient.when()
            .toolsCall("get_strimzi_kafka_cluster_overview",
                Map.of("clusterName", Constants.KAFKA_CLUSTER_NAME, "namespace", ns), response -> {
                    JsonNode root = assertToolSuccess(response);
                    LOGGER.info("Step 2 - get_strimzi_kafka_cluster_overview response (length={})",
                        response.content().getFirst().asText().text().length());
                    LOGGER.debug("Step 2 - get_strimzi_kafka_cluster_overview response:\n{}",
                        response.content().getFirst().asText().text());
                    JsonNode cluster = root.path("cluster");
                    assertFalse(cluster.isMissingNode(), "Should have cluster section");
                    assertEquals(Constants.KAFKA_CLUSTER_NAME, cluster.path("name").asText(),
                        "Cluster name should match");
                    assertEquals("Ready", cluster.path("readiness").asText(),
                        "Cluster should be Ready");
                    assertTrue(root.path("node_pools").isArray()
                            && !root.path("node_pools").isEmpty(),
                        "node_pools should be a non-empty array");
                })
            .thenAssertResults();

        // Step 3: Cluster details with readiness extraction
        AtomicReference<String> readiness = new AtomicReference<>();
        mcpClient.when()
            .toolsCall("get_kafka_cluster",
                Map.of("clusterName", Constants.KAFKA_CLUSTER_NAME, "namespace", ns), response -> {
                    assertFalse(response.isError(), "Step 3: get_kafka_cluster should not return error");
                    String json = response.content().getFirst().asText().text();
                    LOGGER.info("Step 3 - get_kafka_cluster response:\n{}", json);
                    JsonNode cluster = parseJson(json);
                    readiness.set(cluster.path("readiness").asText());
                })
            .thenAssertResults();
        assertNotNull(readiness.get(), "Readiness should have been extracted from cluster info");
        assertEquals("Ready", readiness.get(), "Cluster should be in Ready state");

        // Step 4: Cluster pods
        mcpClient.when()
            .toolsCall("get_kafka_cluster_pods",
                Map.of("clusterName", Constants.KAFKA_CLUSTER_NAME, "namespace", ns), response -> {
                    JsonNode root = assertToolSuccess(response);
                    LOGGER.info("Step 4 - get_kafka_cluster_pods response (length={})",
                        response.content().getFirst().asText().text().length());
                    LOGGER.debug("Step 4 - get_kafka_cluster_pods response:\n{}",
                        response.content().getFirst().asText().text());
                    assertPodSummaryResponse(root.path("pod_summary"));
                })
            .thenAssertResults();

        // Step 5: Node pools
        mcpClient.when()
            .toolsCall("list_kafka_node_pools",
                Map.of("clusterName", Constants.KAFKA_CLUSTER_NAME, "namespace", ns), response -> {
                    assertFalse(response.isError(), "Step 5: list_kafka_node_pools should not return error");
                    assertFalse(response.content().isEmpty(), "Node pools content should not be empty");
                    LOGGER.info("Step 5 - list_kafka_node_pools ({} items):", response.content().size());
                    response.content().forEach(c -> LOGGER.info("  {}", c.asText().text()));
                    assertEquals(2, response.content().size(),
                        "Should have 2 node pools (controller-np and broker-np)");
                })
            .thenAssertResults();
    }

    @Test
    @DisplayName("Topic investigation workflow")
    @Story("Topic Investigation")
    void testTopicInvestigation() {
        String ns = kafkaNamespace.getMetadata().getName();

        // Step 1: List topics
        mcpClient.when()
            .toolsCall("list_kafka_topics",
                Map.of("clusterName", Constants.KAFKA_CLUSTER_NAME, "namespace", ns), response -> {
                    JsonNode root = assertToolSuccess(response);
                    LOGGER.info("Step 1 - list_kafka_topics response:\n{}",
                        response.content().getFirst().asText().text());
                    assertTrue(root.has("items"), "Response should have 'items' field");
                    assertTrue(root.path("items").isArray()
                            && root.path("items").size() >= 1,
                        "Should return at least 1 topic");
                })
            .thenAssertResults();

        // Step 2: Get specific topic and verify name
        mcpClient.when()
            .toolsCall("get_kafka_topic",
                Map.of("clusterName", Constants.KAFKA_CLUSTER_NAME, "topicName", TOPIC_NAME, "namespace", ns), response -> {
                    JsonNode topic = assertToolSuccess(response);
                    LOGGER.info("Step 2 - get_kafka_topic response:\n{}",
                        response.content().getFirst().asText().text());
                    assertEquals(TOPIC_NAME, topic.path("name").asText(),
                        "Topic name should match");
                    assertFalse(topic.path("status").isMissingNode(),
                        "Topic should have a status field");
                    assertFalse(topic.path("partitions").isMissingNode(),
                        "Topic should have partitions field");
                })
            .thenAssertResults();

        // Step 3: Get events for the Kafka cluster
        mcpClient.when()
            .toolsCall("get_strimzi_events",
                Map.of("resourceName", Constants.KAFKA_CLUSTER_NAME, "resourceKind", "Kafka", "namespace", ns), response -> {
                    JsonNode root = assertToolSuccess(response);
                    LOGGER.info("Step 3 - get_strimzi_events response (length={})",
                        response.content().getFirst().asText().text().length());
                    LOGGER.debug("Step 3 - get_strimzi_events response:\n{}",
                        response.content().getFirst().asText().text());
                    assertEventsResponse(root, Constants.KAFKA_CLUSTER_NAME, ns);
                })
            .thenAssertResults();

        // Step 4: Diagnose the topic
        mcpClient.when()
            .toolsCall("diagnose_kafka_topic",
                Map.of("topicName", TOPIC_NAME, "namespace", ns), response -> {
                    JsonNode root = assertToolSuccess(response);
                    LOGGER.info("Step 4 - diagnose_kafka_topic response (length={})",
                        response.content().getFirst().asText().text().length());
                    LOGGER.debug("Step 4 - diagnose_kafka_topic response:\n{}",
                        response.content().getFirst().asText().text());
                    assertDiagnosticReport(root);
                    assertEquals(TOPIC_NAME, root.path("topic").path("name").asText(),
                        "Diagnostic topic name should match");
                })
            .thenAssertResults();
    }

    @Test
    @DisplayName("Connectivity troubleshooting workflow")
    @Story("Connectivity Troubleshooting")
    void testConnectivityTroubleshooting() {
        String ns = kafkaNamespace.getMetadata().getName();

        // Step 1: Get cluster info
        mcpClient.when()
            .toolsCall("get_kafka_cluster",
                Map.of("clusterName", Constants.KAFKA_CLUSTER_NAME, "namespace", ns), response -> {
                    JsonNode cluster = assertToolSuccess(response);
                    LOGGER.info("Step 1 - get_kafka_cluster response:\n{}",
                        response.content().getFirst().asText().text());
                    assertEquals(Constants.KAFKA_CLUSTER_NAME, cluster.path("name").asText(),
                        "Cluster name should match");
                    assertEquals("Ready", cluster.path("readiness").asText(),
                        "Cluster should be Ready");
                    assertEquals(ns, cluster.path("namespace").asText(),
                        "Namespace should match");
                })
            .thenAssertResults();

        // Step 2: Get bootstrap servers
        mcpClient.when()
            .toolsCall("get_kafka_bootstrap_servers",
                Map.of("clusterName", Constants.KAFKA_CLUSTER_NAME, "namespace", ns), response -> {
                    assertFalse(response.isError(), "Step 2: get_kafka_bootstrap_servers should not return error");
                    String text = response.content().getFirst().asText().text();
                    LOGGER.info("Step 2 - get_kafka_bootstrap_servers response:\n{}", text);
                    assertTrue(text.contains(Constants.KAFKA_CLUSTER_NAME),
                        "Bootstrap servers should contain cluster name");
                })
            .thenAssertResults();

        // Step 3: Get certificates (verify no private keys exposed)
        mcpClient.when()
            .toolsCall("get_kafka_cluster_certificates",
                Map.of("clusterName", Constants.KAFKA_CLUSTER_NAME, "namespace", ns), response -> {
                    assertFalse(response.isError(), "Step 3: get_kafka_cluster_certificates should not return error");
                    String text = response.content().getFirst().asText().text();
                    LOGGER.info("Step 3 - get_kafka_cluster_certificates response (length={})", text.length());
                    assertFalse(text.contains("PRIVATE KEY"),
                        "Certificates response should NOT contain PRIVATE KEY");
                })
            .thenAssertResults();

        // Step 4: List users
        mcpClient.when()
            .toolsCall("list_kafka_users",
                Map.of("namespace", ns), response -> {
                    assertFalse(response.isError(), "Step 4: list_kafka_users should not return error");
                    LOGGER.info("Step 4 - list_kafka_users ({} items):", response.content().size());
                    response.content().forEach(c -> LOGGER.info("  {}", c.asText().text()));
                })
            .thenAssertResults();

        // Step 5: Diagnose connectivity
        mcpClient.when()
            .toolsCall("diagnose_kafka_connectivity",
                Map.of("clusterName", Constants.KAFKA_CLUSTER_NAME, "namespace", ns), response -> {
                    JsonNode root = assertToolSuccess(response);
                    LOGGER.info("Step 5 - diagnose_kafka_connectivity response (length={})",
                        response.content().getFirst().asText().text().length());
                    LOGGER.debug("Step 5 - diagnose_kafka_connectivity response:\n{}",
                        response.content().getFirst().asText().text());
                    assertDiagnosticReport(root);
                    assertEquals(Constants.KAFKA_CLUSTER_NAME,
                        root.path("cluster").path("name").asText(),
                        "Cluster name should match");
                    assertFalse(root.path("bootstrap_servers").isMissingNode(),
                        "Should have bootstrap_servers section");
                })
            .thenAssertResults();
    }

    @Test
    @DisplayName("Operator health workflow")
    @Story("Operator Health")
    void testOperatorHealth() {
        String strimziNs = strimziNamespace.getMetadata().getName();

        // Step 1: List operators
        mcpClient.when()
            .toolsCall("list_strimzi_operators",
                Map.of("namespace", strimziNs), response -> {
                    assertFalse(response.isError(), "Step 1: list_strimzi_operators should not return error");
                    assertFalse(response.content().isEmpty(), "Operators content should not be empty");
                    String text = response.content().getFirst().asText().text();
                    LOGGER.info("Step 1 - list_strimzi_operators response:\n{}", text);
                })
            .thenAssertResults();

        // Step 2: Get specific operator
        mcpClient.when()
            .toolsCall("get_strimzi_operator",
                Map.of("operatorName", "strimzi-cluster-operator", "namespace", strimziNs), response -> {
                    JsonNode operator = assertToolSuccess(response);
                    LOGGER.info("Step 2 - get_strimzi_operator response:\n{}",
                        response.content().getFirst().asText().text());
                    assertEquals("strimzi-cluster-operator", operator.path("name").asText(),
                        "Operator name should match");
                    assertTrue(operator.path("ready").asBoolean(),
                        "Operator should be ready");
                })
            .thenAssertResults();

        // Step 3: Get operator logs
        mcpClient.when()
            .toolsCall("get_strimzi_operator_logs",
                Map.of("namespace", strimziNs, "tailLines", 50), response -> {
                    JsonNode root = assertToolSuccess(response);
                    LOGGER.info("Step 3 - get_strimzi_operator_logs response (length={})",
                        response.content().getFirst().asText().text().length());
                    LOGGER.debug("Step 3 - get_strimzi_operator_logs response:\n{}",
                        response.content().getFirst().asText().text());
                    assertTrue(root.path("operator_pods").isArray()
                            && !root.path("operator_pods").isEmpty(),
                        "operator_pods should be a non-empty array");
                })
            .thenAssertResults();

        // Step 4: Get operator metrics
        mcpClient.when()
            .toolsCall("get_strimzi_operator_metrics",
                Map.of("namespace", strimziNs), response -> {
                    JsonNode root = assertToolSuccess(response);
                    LOGGER.info("Step 4 - get_strimzi_operator_metrics response (length={})",
                        response.content().getFirst().asText().text().length());
                    LOGGER.debug("Step 4 - get_strimzi_operator_metrics response:\n{}",
                        response.content().getFirst().asText().text());
                    assertFalse(root.path("timestamp").isMissingNode(),
                        "Should have timestamp");
                })
            .thenAssertResults();

        // Step 5: Diagnose operator metrics
        mcpClient.when()
            .toolsCall("diagnose_operator_metrics",
                Map.of("namespace", strimziNs), response -> {
                    JsonNode root = assertToolSuccess(response);
                    LOGGER.info("Step 5 - diagnose_operator_metrics response (length={})",
                        response.content().getFirst().asText().text().length());
                    LOGGER.debug("Step 5 - diagnose_operator_metrics response:\n{}",
                        response.content().getFirst().asText().text());
                    assertDiagnosticReport(root);
                    assertFalse(root.path("operator").isMissingNode(),
                        "Should have operator section");
                })
            .thenAssertResults();
    }

    @Test
    @DisplayName("KafkaConnect pipeline workflow")
    @Story("KafkaConnect Pipeline")
    void testKafkaConnectPipeline() {
        String ns = kafkaNamespace.getMetadata().getName();

        // Step 1: List Connect clusters
        mcpClient.when()
            .toolsCall("list_kafka_connects",
                Map.of("namespace", ns), response -> {
                    JsonNode root = assertToolSuccess(response);
                    LOGGER.info("Step 1 - list_kafka_connects response:\n{}",
                        response.content().getFirst().asText().text());
                    JsonNode connect = findByName(root, CONNECT_CLUSTER_NAME);
                    assertNotNull(connect, "Should find Connect cluster '"
                        + CONNECT_CLUSTER_NAME + "'");
                    assertEquals("Ready", connect.path("readiness").asText(),
                        "Connect should be Ready");
                })
            .thenAssertResults();

        // Step 2: Get Connect cluster details
        mcpClient.when()
            .toolsCall("get_kafka_connect",
                Map.of("connectName", CONNECT_CLUSTER_NAME, "namespace", ns), response -> {
                    JsonNode connect = assertToolSuccess(response);
                    LOGGER.info("Step 2 - get_kafka_connect response:\n{}",
                        response.content().getFirst().asText().text());
                    assertEquals(CONNECT_CLUSTER_NAME, connect.path("name").asText(),
                        "Connect cluster name should match");
                    assertEquals("Ready", connect.path("readiness").asText(),
                        "Connect cluster should be Ready");
                })
            .thenAssertResults();

        // Step 3: Get Connect pods
        mcpClient.when()
            .toolsCall("get_kafka_connect_pods",
                Map.of("connectName", CONNECT_CLUSTER_NAME, "namespace", ns), response -> {
                    JsonNode root = assertToolSuccess(response);
                    LOGGER.info("Step 3 - get_kafka_connect_pods response (length={})",
                        response.content().getFirst().asText().text().length());
                    LOGGER.debug("Step 3 - get_kafka_connect_pods response:\n{}",
                        response.content().getFirst().asText().text());
                    assertTrue(root.has("pod_summary"), "Should have pod_summary");
                    assertTrue(root.path("pod_summary").path("total_pods").asInt() > 0,
                        "Should have at least one pod");
                })
            .thenAssertResults();

        // Step 4: List connectors
        mcpClient.when()
            .toolsCall("list_kafka_connectors",
                Map.of("namespace", ns), response -> {
                    JsonNode root = assertToolSuccess(response);
                    LOGGER.info("Step 4 - list_kafka_connectors response:\n{}",
                        response.content().getFirst().asText().text());
                    JsonNode connector = findByName(root, CONNECTOR_NAME);
                    assertNotNull(connector, "Should find connector '"
                        + CONNECTOR_NAME + "'");
                })
            .thenAssertResults();

        // Step 5: Get specific connector
        mcpClient.when()
            .toolsCall("get_kafka_connector",
                Map.of("connectorName", CONNECTOR_NAME, "namespace", ns), response -> {
                    assertFalse(response.isError(), "Step 5: get_kafka_connector should not return error");
                    String text = response.content().getFirst().asText().text();
                    LOGGER.info("Step 5 - get_kafka_connector response:\n{}", text);

                    JsonNode connector = parseJson(text);
                    assertEquals(CONNECTOR_NAME, connector.path("name").asText(),
                        "Connector name should match");
                })
            .thenAssertResults();

        // Step 6: Diagnose Connect cluster
        mcpClient.when()
            .toolsCall("diagnose_kafka_connect",
                Map.of("connectName", CONNECT_CLUSTER_NAME, "namespace", ns), response -> {
                    JsonNode root = assertToolSuccess(response);
                    LOGGER.info("Step 6 - diagnose_kafka_connect response (length={})",
                        response.content().getFirst().asText().text().length());
                    LOGGER.debug("Step 6 - diagnose_kafka_connect response:\n{}",
                        response.content().getFirst().asText().text());
                    assertDiagnosticReport(root);
                    assertEquals(CONNECT_CLUSTER_NAME,
                        root.path("connect_cluster").path("name").asText(),
                        "Connect cluster name should match");
                })
            .thenAssertResults();
    }

    @Test
    @DisplayName("Upgrade readiness workflow")
    @Story("Upgrade Readiness")
    void testUpgradeReadiness() {
        String ns = kafkaNamespace.getMetadata().getName();

        // Step 1: Get cluster info
        mcpClient.when()
            .toolsCall("get_kafka_cluster",
                Map.of("clusterName", Constants.KAFKA_CLUSTER_NAME, "namespace", ns), response -> {
                    assertFalse(response.isError(), "Step 1: get_kafka_cluster should not return error");
                    String text = response.content().getFirst().asText().text();
                    LOGGER.info("Step 1 - get_kafka_cluster response:\n{}", text);

                    JsonNode cluster = parseJson(text);
                    assertEquals("Ready", cluster.path("readiness").asText(),
                        "Cluster should be Ready before upgrade assessment");
                })
            .thenAssertResults();

        // Step 2: Get cluster pods
        mcpClient.when()
            .toolsCall("get_kafka_cluster_pods",
                Map.of("clusterName", Constants.KAFKA_CLUSTER_NAME, "namespace", ns), response -> {
                    JsonNode root = assertToolSuccess(response);
                    LOGGER.info("Step 2 - get_kafka_cluster_pods response (length={})",
                        response.content().getFirst().asText().text().length());
                    LOGGER.debug("Step 2 - get_kafka_cluster_pods response:\n{}",
                        response.content().getFirst().asText().text());
                    assertPodSummaryResponse(root.path("pod_summary"));
                })
            .thenAssertResults();

        // Step 3: Get replication metrics
        mcpClient.when()
            .toolsCall("get_kafka_metrics",
                Map.of("clusterName", Constants.KAFKA_CLUSTER_NAME, "namespace", ns, "category", "replication"), response -> {
                    JsonNode root = assertToolSuccess(response);
                    LOGGER.info("Step 3 - get_kafka_metrics replication response (length={})",
                        response.content().getFirst().asText().text().length());
                    LOGGER.debug("Step 3 - get_kafka_metrics replication response:\n{}",
                        response.content().getFirst().asText().text());
                    assertEquals(Constants.KAFKA_CLUSTER_NAME,
                        root.path("cluster_name").asText(),
                        "cluster_name should match");
                })
            .thenAssertResults();

        // Step 4: Get certificates
        mcpClient.when()
            .toolsCall("get_kafka_cluster_certificates",
                Map.of("clusterName", Constants.KAFKA_CLUSTER_NAME, "namespace", ns), response -> {
                    JsonNode root = assertToolSuccess(response);
                    LOGGER.info("Step 4 - get_kafka_cluster_certificates response (length={})",
                        response.content().getFirst().asText().text().length());
                    LOGGER.debug("Step 4 - get_kafka_cluster_certificates response:\n{}",
                        response.content().getFirst().asText().text());
                    assertEquals(Constants.KAFKA_CLUSTER_NAME,
                        root.path("cluster_name").asText(),
                        "cluster_name should match");
                    assertTrue(root.path("certificates").isArray(),
                        "certificates should be an array");
                })
            .thenAssertResults();

        // Step 5: Assess upgrade readiness
        mcpClient.when()
            .toolsCall("assess_upgrade_readiness",
                Map.of("clusterName", Constants.KAFKA_CLUSTER_NAME, "namespace", ns), response -> {
                    JsonNode root = assertToolSuccess(response);
                    LOGGER.info("Step 5 - assess_upgrade_readiness response (length={})",
                        response.content().getFirst().asText().text().length());
                    LOGGER.debug("Step 5 - assess_upgrade_readiness response:\n{}",
                        response.content().getFirst().asText().text());
                    assertDiagnosticReport(root);
                })
            .thenAssertResults();
    }

    // ---- Additional E2E Workflows ----

    @Test
    @DisplayName("KafkaBridge investigation workflow")
    @Story("KafkaBridge Investigation")
    void testKafkaBridgeInvestigation() {
        String ns = kafkaNamespace.getMetadata().getName();

        // Step 1: List Bridge clusters
        mcpClient.when()
            .toolsCall("list_kafka_bridges",
                Map.of("namespace", ns), response -> {
                    assertFalse(response.isError(), "Step 1: list_kafka_bridges should not return error");
                    assertFalse(response.content().isEmpty(), "Bridge list content should not be empty");
                    LOGGER.info("Step 1 - list_kafka_bridges ({} items):", response.content().size());
                    response.content().forEach(c -> LOGGER.info("  {}", c.asText().text()));
                    assertTrue(response.content().size() >= 1,
                        "Should have at least 1 KafkaBridge");
                })
            .thenAssertResults();

        // Step 2: Get Bridge details
        mcpClient.when()
            .toolsCall("get_kafka_bridge",
                Map.of("bridgeName", BRIDGE_NAME, "namespace", ns), response -> {
                    assertFalse(response.isError(), "Step 2: get_kafka_bridge should not return error");
                    String text = response.content().getFirst().asText().text();
                    LOGGER.info("Step 2 - get_kafka_bridge response:\n{}", text);

                    JsonNode bridge = parseJson(text);
                    assertEquals(BRIDGE_NAME, bridge.path("name").asText(),
                        "Bridge name should match");
                })
            .thenAssertResults();

        // Step 3: Get Bridge pods
        mcpClient.when()
            .toolsCall("get_kafka_bridge_pods",
                Map.of("bridgeName", BRIDGE_NAME, "namespace", ns), response -> {
                    JsonNode root = assertToolSuccess(response);
                    LOGGER.info("Step 3 - get_kafka_bridge_pods response (length={})",
                        response.content().getFirst().asText().text().length());
                    LOGGER.debug("Step 3 - get_kafka_bridge_pods response:\n{}",
                        response.content().getFirst().asText().text());
                    assertTrue(root.has("pod_summary"), "Should have pod_summary");
                    assertTrue(root.path("pod_summary").path("total_pods").asInt() > 0,
                        "Should have at least one pod");
                })
            .thenAssertResults();

        // Step 4: Get Bridge logs
        mcpClient.when()
            .toolsCall("get_kafka_bridge_logs",
                Map.of("bridgeName", BRIDGE_NAME, "namespace", ns, "tailLines", 50), response -> {
                    JsonNode root = assertToolSuccess(response);
                    LOGGER.info("Step 4 - get_kafka_bridge_logs response (length={})",
                        response.content().getFirst().asText().text().length());
                    LOGGER.debug("Step 4 - get_kafka_bridge_logs response:\n{}",
                        response.content().getFirst().asText().text());
                    assertLogsResponse(root, "bridge_name", BRIDGE_NAME);
                })
            .thenAssertResults();
    }

}
