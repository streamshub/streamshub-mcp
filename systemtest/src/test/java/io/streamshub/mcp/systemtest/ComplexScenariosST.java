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
import io.streamshub.mcp.systemtest.templates.strimzi.KafkaConnectorTemplates;
import io.streamshub.mcp.systemtest.templates.strimzi.KafkaNodePoolTemplates;
import io.streamshub.mcp.systemtest.templates.strimzi.KafkaTemplates;
import io.streamshub.mcp.systemtest.templates.strimzi.KafkaTopicTemplates;
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
@DisplayName("Complex Scenarios MCP Tools")
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

            KafkaTemplates.deployMetricsConfigMap(kafkaNs);

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

    // ---- Diagnostic Tools ----

    @Test
    @DisplayName("T15.1 - diagnose_kafka_cluster returns diagnostic info")
    @Story("Diagnose Kafka Cluster")
    void testDiagnoseKafkaCluster() {
        Map<String, Object> args = Map.of(
            "clusterName", Constants.KAFKA_CLUSTER_NAME,
            "namespace", kafkaNamespace.getMetadata().getName());
        mcpClient.when()
            .toolsCall("diagnose_kafka_cluster", args, response -> {
                assertFalse(response.isError(), "diagnose_kafka_cluster should not return error");
                String text = response.content().getFirst().asText().text();
                LOGGER.info("diagnose_kafka_cluster response:\n{}", text);
            })
            .thenAssertResults();
    }

    @Test
    @DisplayName("T15.2 - diagnose_kafka_cluster with symptom returns diagnostic info")
    @Story("Diagnose Kafka Cluster")
    void testDiagnoseKafkaClusterWithSymptom() {
        Map<String, Object> args = Map.of(
            "clusterName", Constants.KAFKA_CLUSTER_NAME,
            "namespace", kafkaNamespace.getMetadata().getName(),
            "symptom", "high latency",
            "sinceMinutes", 60);
        mcpClient.when()
            .toolsCall("diagnose_kafka_cluster", args, response -> {
                assertFalse(response.isError(), "diagnose_kafka_cluster with symptom should not return error");
                String text = response.content().getFirst().asText().text();
                LOGGER.info("diagnose_kafka_cluster with symptom response:\n{}", text);
            })
            .thenAssertResults();
    }

    @Test
    @DisplayName("T15.3 - diagnose_kafka_connectivity returns connectivity diagnostic info")
    @Story("Diagnose Kafka Connectivity")
    void testDiagnoseKafkaConnectivity() {
        Map<String, Object> args = Map.of(
            "clusterName", Constants.KAFKA_CLUSTER_NAME,
            "namespace", kafkaNamespace.getMetadata().getName());
        mcpClient.when()
            .toolsCall("diagnose_kafka_connectivity", args, response -> {
                assertFalse(response.isError(), "diagnose_kafka_connectivity should not return error");
                String text = response.content().getFirst().asText().text();
                LOGGER.info("diagnose_kafka_connectivity response:\n{}", text);
            })
            .thenAssertResults();
    }

    @Test
    @DisplayName("T15.4 - diagnose_kafka_connectivity with listener filter")
    @Story("Diagnose Kafka Connectivity")
    void testDiagnoseKafkaConnectivityListener() {
        Map<String, Object> args = Map.of(
            "clusterName", Constants.KAFKA_CLUSTER_NAME,
            "namespace", kafkaNamespace.getMetadata().getName(),
            "listenerName", "tls");
        mcpClient.when()
            .toolsCall("diagnose_kafka_connectivity", args, response -> {
                assertFalse(response.isError(), "diagnose_kafka_connectivity with listener should not return error");
                String text = response.content().getFirst().asText().text();
                LOGGER.info("diagnose_kafka_connectivity with listener response:\n{}", text);
            })
            .thenAssertResults();
    }

    @Test
    @DisplayName("T15.12 - assess_upgrade_readiness returns upgrade assessment")
    @Story("Assess Upgrade Readiness")
    void testAssessUpgradeReadiness() {
        Map<String, Object> args = Map.of(
            "clusterName", Constants.KAFKA_CLUSTER_NAME,
            "namespace", kafkaNamespace.getMetadata().getName());
        mcpClient.when()
            .toolsCall("assess_upgrade_readiness", args, response -> {
                assertFalse(response.isError(), "assess_upgrade_readiness should not return error");
                String text = response.content().getFirst().asText().text();
                LOGGER.info("assess_upgrade_readiness response:\n{}", text);
            })
            .thenAssertResults();
    }

    // ---- Multi-Step E2E Workflows ----

    @Test
    @DisplayName("E2E-1 - Cluster health check workflow")
    @Story("Cluster Health Check")
    void testClusterHealthCheck() {
        String ns = kafkaNamespace.getMetadata().getName();

        // Step 1: Fleet overview
        mcpClient.when()
            .toolsCall("get_kafka_fleet_overview", Map.of("namespace", ns), response -> {
                assertFalse(response.isError(), "Step 1: get_kafka_fleet_overview should not return error");
                assertFalse(response.content().isEmpty(), "Fleet overview content should not be empty");
                String text = response.content().getFirst().asText().text();
                LOGGER.info("Step 1 - get_kafka_fleet_overview response:\n{}", text);

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
                    assertFalse(response.isError(), "Step 2: get_strimzi_kafka_cluster_overview should not return error");
                    String text = response.content().getFirst().asText().text();
                    LOGGER.info("Step 2 - get_strimzi_kafka_cluster_overview response:\n{}", text);
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
                    assertFalse(response.isError(), "Step 4: get_kafka_cluster_pods should not return error");
                    String text = response.content().getFirst().asText().text();
                    LOGGER.info("Step 4 - get_kafka_cluster_pods response:\n{}", text);
                })
            .thenAssertResults();

        // Step 5: Node pools
        mcpClient.when()
            .toolsCall("list_kafka_node_pools",
                Map.of("clusterName", Constants.KAFKA_CLUSTER_NAME, "namespace", ns), response -> {
                    assertFalse(response.isError(), "Step 5: list_kafka_node_pools should not return error");
                    assertFalse(response.content().isEmpty(), "Node pools content should not be empty");
                    String text = response.content().getFirst().asText().text();
                    LOGGER.info("Step 5 - list_kafka_node_pools response:\n{}", text);

                    JsonNode root = parseJson(text);
                    assertTrue(root.isArray(), "Node pools should be a JSON array");
                    assertEquals(2, root.size(),
                        "Should have 2 node pools (controller-np and broker-np)");
                })
            .thenAssertResults();
    }

    @Test
    @DisplayName("E2E-2 - Topic investigation workflow")
    @Story("Topic Investigation")
    void testTopicInvestigation() {
        String ns = kafkaNamespace.getMetadata().getName();

        // Step 1: List topics
        mcpClient.when()
            .toolsCall("list_kafka_topics",
                Map.of("clusterName", Constants.KAFKA_CLUSTER_NAME, "namespace", ns), response -> {
                    assertFalse(response.isError(), "Step 1: list_kafka_topics should not return error");
                    assertFalse(response.content().isEmpty(), "Topics content should not be empty");
                    String text = response.content().getFirst().asText().text();
                    LOGGER.info("Step 1 - list_kafka_topics response:\n{}", text);
                })
            .thenAssertResults();

        // Step 2: Get specific topic and verify name
        mcpClient.when()
            .toolsCall("get_kafka_topic",
                Map.of("clusterName", Constants.KAFKA_CLUSTER_NAME, "topicName", TOPIC_NAME, "namespace", ns), response -> {
                    assertFalse(response.isError(), "Step 2: get_kafka_topic should not return error");
                    String json = response.content().getFirst().asText().text();
                    LOGGER.info("Step 2 - get_kafka_topic response:\n{}", json);
                    JsonNode topic = parseJson(json);
                    assertTrue(topic.path("name").asText().contains(TOPIC_NAME),
                        "Topic name should contain '" + TOPIC_NAME + "'");
                })
            .thenAssertResults();

        // Step 3: Get events for the Kafka cluster
        mcpClient.when()
            .toolsCall("get_strimzi_events",
                Map.of("resourceName", Constants.KAFKA_CLUSTER_NAME, "resourceKind", "Kafka", "namespace", ns), response -> {
                    assertFalse(response.isError(), "Step 3: get_strimzi_events should not return error");
                    String text = response.content().getFirst().asText().text();
                    LOGGER.info("Step 3 - get_strimzi_events response:\n{}", text);
                })
            .thenAssertResults();

        // Step 4: Diagnose the topic
        mcpClient.when()
            .toolsCall("diagnose_kafka_topic",
                Map.of("topicName", TOPIC_NAME, "namespace", ns), response -> {
                    assertFalse(response.isError(), "Step 4: diagnose_kafka_topic should not return error");
                    assertFalse(response.content().isEmpty(),
                        "diagnose_kafka_topic should return content");
                    String text = response.content().getFirst().asText().text();
                    LOGGER.info("Step 4 - diagnose_kafka_topic response:\n{}", text);
                    assertTrue(text.contains(TOPIC_NAME),
                        "Diagnostic response should reference the topic name");
                })
            .thenAssertResults();
    }

    @Test
    @DisplayName("E2E-3 - Connectivity troubleshooting workflow")
    @Story("Connectivity Troubleshooting")
    void testConnectivityTroubleshooting() {
        String ns = kafkaNamespace.getMetadata().getName();

        // Step 1: Get cluster info
        mcpClient.when()
            .toolsCall("get_kafka_cluster",
                Map.of("clusterName", Constants.KAFKA_CLUSTER_NAME, "namespace", ns), response -> {
                    assertFalse(response.isError(), "Step 1: get_kafka_cluster should not return error");
                    String text = response.content().getFirst().asText().text();
                    LOGGER.info("Step 1 - get_kafka_cluster response:\n{}", text);
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
                    String text = response.content().getFirst().asText().text();
                    LOGGER.info("Step 4 - list_kafka_users response:\n{}", text);
                })
            .thenAssertResults();

        // Step 5: Diagnose connectivity
        mcpClient.when()
            .toolsCall("diagnose_kafka_connectivity",
                Map.of("clusterName", Constants.KAFKA_CLUSTER_NAME, "namespace", ns), response -> {
                    assertFalse(response.isError(), "Step 5: diagnose_kafka_connectivity should not return error");
                    String text = response.content().getFirst().asText().text();
                    LOGGER.info("Step 5 - diagnose_kafka_connectivity response:\n{}", text);
                })
            .thenAssertResults();
    }

    @Test
    @DisplayName("E2E-4 - Operator health workflow")
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
                    assertFalse(response.isError(), "Step 2: get_strimzi_operator should not return error");
                    String text = response.content().getFirst().asText().text();
                    LOGGER.info("Step 2 - get_strimzi_operator response:\n{}", text);

                    JsonNode operator = parseJson(text);
                    assertTrue(operator.path("name").asText().contains("strimzi-cluster-operator"),
                        "Operator name should contain 'strimzi-cluster-operator'");
                })
            .thenAssertResults();

        // Step 3: Get operator logs
        mcpClient.when()
            .toolsCall("get_strimzi_operator_logs",
                Map.of("namespace", strimziNs, "tailLines", 50), response -> {
                    assertFalse(response.isError(), "Step 3: get_strimzi_operator_logs should not return error");
                    String text = response.content().getFirst().asText().text();
                    LOGGER.info("Step 3 - get_strimzi_operator_logs response (length={})", text.length());
                })
            .thenAssertResults();

        // Step 4: Get operator metrics
        mcpClient.when()
            .toolsCall("get_strimzi_operator_metrics",
                Map.of("namespace", strimziNs), response -> {
                    assertFalse(response.isError(), "Step 4: get_strimzi_operator_metrics should not return error");
                    String text = response.content().getFirst().asText().text();
                    LOGGER.info("Step 4 - get_strimzi_operator_metrics response:\n{}", text);
                })
            .thenAssertResults();

        // Step 5: Diagnose operator metrics
        mcpClient.when()
            .toolsCall("diagnose_operator_metrics",
                Map.of("namespace", strimziNs), response -> {
                    assertFalse(response.isError(), "Step 5: diagnose_operator_metrics should not return error");
                    String text = response.content().getFirst().asText().text();
                    LOGGER.info("Step 5 - diagnose_operator_metrics response:\n{}", text);
                })
            .thenAssertResults();
    }

    @Test
    @DisplayName("E2E-5 - KafkaConnect pipeline workflow")
    @Story("KafkaConnect Pipeline")
    void testKafkaConnectPipeline() {
        String ns = kafkaNamespace.getMetadata().getName();

        // Step 1: List Connect clusters
        mcpClient.when()
            .toolsCall("list_kafka_connects",
                Map.of("namespace", ns), response -> {
                    assertFalse(response.isError(), "Step 1: list_kafka_connects should not return error");
                    assertFalse(response.content().isEmpty(), "Connect clusters content should not be empty");
                    String text = response.content().getFirst().asText().text();
                    LOGGER.info("Step 1 - list_kafka_connects response:\n{}", text);
                })
            .thenAssertResults();

        // Step 2: Get Connect cluster details
        mcpClient.when()
            .toolsCall("get_kafka_connect",
                Map.of("connectName", CONNECT_CLUSTER_NAME, "namespace", ns), response -> {
                    assertFalse(response.isError(), "Step 2: get_kafka_connect should not return error");
                    String text = response.content().getFirst().asText().text();
                    LOGGER.info("Step 2 - get_kafka_connect response:\n{}", text);

                    JsonNode connect = parseJson(text);
                    assertEquals(CONNECT_CLUSTER_NAME, connect.path("name").asText(),
                        "Connect cluster name should match");
                })
            .thenAssertResults();

        // Step 3: Get Connect pods
        mcpClient.when()
            .toolsCall("get_kafka_connect_pods",
                Map.of("connectName", CONNECT_CLUSTER_NAME, "namespace", ns), response -> {
                    assertFalse(response.isError(), "Step 3: get_kafka_connect_pods should not return error");
                    String text = response.content().getFirst().asText().text();
                    LOGGER.info("Step 3 - get_kafka_connect_pods response:\n{}", text);
                })
            .thenAssertResults();

        // Step 4: List connectors
        mcpClient.when()
            .toolsCall("list_kafka_connectors",
                Map.of("namespace", ns), response -> {
                    assertFalse(response.isError(), "Step 4: list_kafka_connectors should not return error");
                    assertFalse(response.content().isEmpty(), "Connectors content should not be empty");
                    String text = response.content().getFirst().asText().text();
                    LOGGER.info("Step 4 - list_kafka_connectors response:\n{}", text);
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
                    assertFalse(response.isError(), "Step 6: diagnose_kafka_connect should not return error");
                    String text = response.content().getFirst().asText().text();
                    LOGGER.info("Step 6 - diagnose_kafka_connect response:\n{}", text);
                })
            .thenAssertResults();
    }

    @Test
    @DisplayName("E2E-6 - Upgrade readiness workflow")
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
                    assertFalse(response.isError(), "Step 2: get_kafka_cluster_pods should not return error");
                    String text = response.content().getFirst().asText().text();
                    LOGGER.info("Step 2 - get_kafka_cluster_pods response:\n{}", text);
                })
            .thenAssertResults();

        // Step 3: Get replication metrics
        mcpClient.when()
            .toolsCall("get_kafka_metrics",
                Map.of("clusterName", Constants.KAFKA_CLUSTER_NAME, "namespace", ns, "category", "replication"), response -> {
                    assertFalse(response.isError(), "Step 3: get_kafka_metrics replication should not return error");
                    String text = response.content().getFirst().asText().text();
                    LOGGER.info("Step 3 - get_kafka_metrics replication response:\n{}", text);
                })
            .thenAssertResults();

        // Step 4: Get certificates
        mcpClient.when()
            .toolsCall("get_kafka_cluster_certificates",
                Map.of("clusterName", Constants.KAFKA_CLUSTER_NAME, "namespace", ns), response -> {
                    assertFalse(response.isError(), "Step 4: get_kafka_cluster_certificates should not return error");
                    String text = response.content().getFirst().asText().text();
                    LOGGER.info("Step 4 - get_kafka_cluster_certificates response (length={})", text.length());
                })
            .thenAssertResults();

        // Step 5: Assess upgrade readiness
        mcpClient.when()
            .toolsCall("assess_upgrade_readiness",
                Map.of("clusterName", Constants.KAFKA_CLUSTER_NAME, "namespace", ns), response -> {
                    assertFalse(response.isError(), "Step 5: assess_upgrade_readiness should not return error");
                    assertFalse(response.content().isEmpty(),
                        "assess_upgrade_readiness should return content");
                    String text = response.content().getFirst().asText().text();
                    LOGGER.info("Step 5 - assess_upgrade_readiness response:\n{}", text);
                    assertFalse(text.isEmpty(), "Upgrade readiness response should not be empty");
                })
            .thenAssertResults();
    }

    // ---- Additional E2E Workflows ----

    @Test
    @DisplayName("E2E-7 - KafkaBridge investigation workflow")
    @Story("KafkaBridge Investigation")
    void testKafkaBridgeInvestigation() {
        String ns = kafkaNamespace.getMetadata().getName();

        // Step 1: List Bridge clusters
        mcpClient.when()
            .toolsCall("list_kafka_bridges",
                Map.of("namespace", ns), response -> {
                    assertFalse(response.isError(), "Step 1: list_kafka_bridges should not return error");
                    assertFalse(response.content().isEmpty(), "Bridge list content should not be empty");
                    String text = response.content().getFirst().asText().text();
                    LOGGER.info("Step 1 - list_kafka_bridges response:\n{}", text);

                    JsonNode root = parseJson(text);
                    assertTrue(root.isArray() && root.size() >= 1,
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
                    assertFalse(response.isError(), "Step 3: get_kafka_bridge_pods should not return error");
                    String text = response.content().getFirst().asText().text();
                    LOGGER.info("Step 3 - get_kafka_bridge_pods response:\n{}", text);
                })
            .thenAssertResults();

        // Step 4: Get Bridge logs
        mcpClient.when()
            .toolsCall("get_kafka_bridge_logs",
                Map.of("bridgeName", BRIDGE_NAME, "namespace", ns, "tailLines", 50), response -> {
                    assertFalse(response.isError(), "Step 4: get_kafka_bridge_logs should not return error");
                    assertFalse(response.content().isEmpty(),
                        "Bridge logs content should not be empty");
                    String text = response.content().getFirst().asText().text();
                    LOGGER.info("Step 4 - get_kafka_bridge_logs response (length={})", text.length());
                })
            .thenAssertResults();
    }

    // ---- Additional Diagnostic Tests ----

    @Test
    @DisplayName("T15.5 - diagnose_kafka_connector returns diagnostic info")
    @Story("Diagnose Kafka Connector")
    void testDiagnoseKafkaConnector() {
        Map<String, Object> args = Map.of(
            "connectorName", CONNECTOR_NAME,
            "namespace", kafkaNamespace.getMetadata().getName());
        mcpClient.when()
            .toolsCall("diagnose_kafka_connector", args, response -> {
                assertFalse(response.isError(), "diagnose_kafka_connector should not return error");
                assertFalse(response.content().isEmpty(),
                    "diagnose_kafka_connector should return content");
                String text = response.content().getFirst().asText().text();
                LOGGER.info("diagnose_kafka_connector response:\n{}", text);
                assertTrue(text.contains(CONNECTOR_NAME),
                    "Diagnostic response should reference the connector name");
            })
            .thenAssertResults();
    }

    @Test
    @DisplayName("T15.6 - diagnose_kafka_metrics returns metrics diagnostic info")
    @Story("Diagnose Kafka Metrics")
    void testDiagnoseKafkaMetrics() {
        Map<String, Object> args = Map.of(
            "clusterName", Constants.KAFKA_CLUSTER_NAME,
            "namespace", kafkaNamespace.getMetadata().getName(),
            "concern", "replication");
        mcpClient.when()
            .toolsCall("diagnose_kafka_metrics", args, response -> {
                assertFalse(response.isError(), "diagnose_kafka_metrics should not return error");
                assertFalse(response.content().isEmpty(),
                    "diagnose_kafka_metrics should return content");
                String text = response.content().getFirst().asText().text();
                LOGGER.info("diagnose_kafka_metrics response:\n{}", text);
            })
            .thenAssertResults();
    }
}
