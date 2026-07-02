/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.systemtest.scenarios;

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
import io.streamshub.mcp.systemtest.templates.strimzi.KafkaConnectorTemplates;
import io.streamshub.mcp.systemtest.templates.strimzi.KafkaNodePoolTemplates;
import io.streamshub.mcp.systemtest.templates.strimzi.KafkaTemplates;
import io.streamshub.mcp.systemtest.templates.strimzi.KafkaTopicTemplates;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static io.streamshub.mcp.systemtest.TestTags.ACCEPTANCE;
import static io.streamshub.mcp.systemtest.TestTags.LOGS;
import static io.streamshub.mcp.systemtest.TestTags.METRICS;
import static io.streamshub.mcp.systemtest.TestTags.REGRESSION;
import static io.streamshub.mcp.systemtest.TestTags.TOOLS;
import static io.streamshub.mcp.systemtest.templates.strimzi.KafkaConnectorTemplates.CONNECTOR_NAME;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * System tests for multi-step E2E workflows with data flow between steps.
 * Each workflow discovers resources dynamically and passes data extracted
 * from one tool's response as input to the next tool, verifying that the
 * MCP tools compose correctly in realistic investigation scenarios.
 */
@Epic("Strimzi MCP E2E")
@Feature("Complex Scenarios")
@Tag(ACCEPTANCE)
@Tag(REGRESSION)
@Tag(METRICS)
@Tag(LOGS)
@Tag(TOOLS)
class ComplexScenariosST extends AbstractST {

    private static final Logger LOGGER = LoggerFactory.getLogger(ComplexScenariosST.class);

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
            KafkaTemplates.deployPodMonitors(kafkaNs);
            KafkaBridgeTemplates.deployMetricsConfigMap(kafkaNs);
            KafkaBridgeTemplates.deployPodMonitors(kafkaNs);
            KafkaConnectTemplates.deployMetricsConfigMap(kafkaNs);
            KafkaConnectTemplates.deployPodMonitors(kafkaNs);

            krm.createOrUpdateResourceWithoutWait(
                KafkaNodePoolTemplates.controllerPool(kafkaNs, "controller-np",
                    Constants.KAFKA_CLUSTER_NAME, 1).build(),
                KafkaNodePoolTemplates.brokerPool(kafkaNs, "broker-np",
                    Constants.KAFKA_CLUSTER_NAME, 1).build());

            krm.createOrUpdateResourceWithWait(
                KafkaTemplates.kafkaWithMetrics(kafkaNs, Constants.KAFKA_CLUSTER_NAME, 1).build());

            krm.createOrUpdateResourceWithWait(
                KafkaTopicTemplates.topic(kafkaNs, Constants.TOPIC_NAME,
                    Constants.KAFKA_CLUSTER_NAME, 3, 1).build());

            krm.createOrUpdateResourceWithWait(
                KafkaConnectTemplates.kafkaConnect(
                    kafkaNs, Constants.CONNECT_CLUSTER_NAME, Constants.KAFKA_CLUSTER_NAME, 1).build());

            krm.createOrUpdateResourceWithWait(
                KafkaConnectorTemplates.camelTimerSource(
                    kafkaNs, CONNECTOR_NAME, Constants.CONNECT_CLUSTER_NAME, "test-topic").build());

            krm.createOrUpdateResourceWithWait(
                KafkaBridgeTemplates.kafkaBridge(
                    kafkaNs, Constants.BRIDGE_NAME, Constants.KAFKA_CLUSTER_NAME, 1).build());
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

    /**
     * Discovers a cluster from the fleet, then drills down into its pods
     * and fetches logs for a specific pod extracted from the pods response.
     * Data flow: fleet → cluster name → pods → pod name → pod-specific logs
     */
    @Test
    @Story("Cluster discovery to pod-specific logs")
    void testClusterDiscoveryToPodLogs() {
        String ns = kafkaNamespace.getMetadata().getName();

        // Step 1: Discover cluster from fleet overview (no hardcoded name)
        AtomicReference<String> discoveredCluster = new AtomicReference<>();
        mcpClient.when()
            .toolsCall("get_kafka_fleet_overview", Map.of("namespace", ns), response -> {
                JsonNode root = assertToolSuccess(response);
                LOGGER.info("Step 1 - Fleet overview: {} cluster(s)", root.path("total_clusters").asInt());
                JsonNode clusters = root.path("clusters");
                assertTrue(clusters.isArray() && !clusters.isEmpty(),
                    "Should discover at least one cluster");
                String clusterName = clusters.get(0).path("name").asText();
                assertFalse(clusterName.isEmpty(), "Discovered cluster should have a name");
                discoveredCluster.set(clusterName);
                LOGGER.info("Step 1 - Discovered cluster: {}", clusterName);
            })
            .thenAssertResults();

        // Step 2: Use discovered cluster name to get its pods
        AtomicReference<String> discoveredPod = new AtomicReference<>();
        mcpClient.when()
            .toolsCall("get_kafka_cluster_pods",
                Map.of("clusterName", discoveredCluster.get(), "namespace", ns), response -> {
                    JsonNode root = assertToolSuccess(response);
                    assertPodSummaryResponse(root.path("pod_summary"));
                    JsonNode pods = root.path("pod_summary").path("pods");
                    assertTrue(pods.isArray() && !pods.isEmpty(),
                        "Should have at least one pod in summary");
                    String podName = pods.get(0).path("name").asText();
                    assertFalse(podName.isEmpty(), "Discovered pod should have a name");
                    discoveredPod.set(podName);
                    LOGGER.info("Step 2 - Discovered pod: {}", podName);
                })
            .thenAssertResults();

        // Step 3: Use discovered pod name to get its specific logs
        mcpClient.when()
            .toolsCall("get_kafka_cluster_logs",
                Map.of("clusterName", discoveredCluster.get(), "namespace", ns,
                    "podNames", java.util.List.of(discoveredPod.get()), "tailLines", 20), response -> {
                    JsonNode root = assertToolSuccess(response);
                    assertClusterLogsResponse(root, discoveredCluster.get());
                    JsonNode pods = root.path("pods");
                    assertEquals(1, pods.size(), "Should contain exactly the requested pod");
                    assertEquals(discoveredPod.get(), pods.get(0).asText(),
                        "Pod name in logs should match the discovered pod");
                    LOGGER.info("Step 3 - Got {} log lines for pod {}",
                        root.path("log_lines").asInt(), discoveredPod.get());
                })
            .thenAssertResults();
    }

    /**
     * Discovers a topic from the list, extracts its cluster association,
     * then uses that info to diagnose the topic.
     * Data flow: list topics → topic name + cluster → get topic details → diagnose topic
     */
    @Test
    @Story("Topic discovery to diagnosis")
    void testTopicDiscoveryToDiagnosis() {
        String ns = kafkaNamespace.getMetadata().getName();

        // Step 1: List topics and extract the first topic name
        AtomicReference<String> discoveredTopic = new AtomicReference<>();
        AtomicReference<String> discoveredCluster = new AtomicReference<>();
        mcpClient.when()
            .toolsCall("list_kafka_topics",
                Map.of("clusterName", Constants.KAFKA_CLUSTER_NAME, "namespace", ns), response -> {
                    JsonNode root = assertToolSuccess(response);
                    JsonNode items = root.path("items");
                    assertTrue(items.isArray() && items.size() >= 1,
                        "Should have at least one topic");
                    String topicName = items.get(0).path("name").asText();
                    assertFalse(topicName.isEmpty(), "Topic should have a name");
                    discoveredTopic.set(topicName);
                    LOGGER.info("Step 1 - Discovered topic: {}", topicName);
                })
            .thenAssertResults();

        // Step 2: Get topic details and extract its cluster association
        AtomicReference<Integer> discoveredPartitions = new AtomicReference<>();
        mcpClient.when()
            .toolsCall("get_kafka_topic",
                Map.of("clusterName", Constants.KAFKA_CLUSTER_NAME,
                    "topicName", discoveredTopic.get(), "namespace", ns), response -> {
                    JsonNode topic = assertToolSuccess(response);
                    assertEquals(discoveredTopic.get(), topic.path("name").asText(),
                        "Topic name from get should match discovered name");
                    String cluster = topic.path("cluster").asText();
                    assertFalse(cluster.isEmpty(), "Topic should have a cluster association");
                    discoveredCluster.set(cluster);
                    int partitions = topic.path("partitions").asInt();
                    assertTrue(partitions > 0, "Topic should have partitions");
                    discoveredPartitions.set(partitions);
                    LOGGER.info("Step 2 - Topic '{}' has {} partitions on cluster '{}'",
                        discoveredTopic.get(), partitions, cluster);
                })
            .thenAssertResults();

        // Step 3: Diagnose the topic using discovered cluster and topic names
        mcpClient.when()
            .toolsCall("diagnose_kafka_topic",
                Map.of("topicName", discoveredTopic.get(),
                    "clusterName", discoveredCluster.get(), "namespace", ns), response -> {
                    JsonNode root = assertToolSuccess(response);
                    assertDiagnosticReport(root);
                    assertEquals(discoveredTopic.get(),
                        root.path("topic").path("name").asText(),
                        "Diagnostic should be for the discovered topic");
                    LOGGER.info("Step 3 - Diagnosed topic '{}' ({} steps completed)",
                        discoveredTopic.get(), root.path("steps_completed").size());
                })
            .thenAssertResults();
    }

    /**
     * Discovers a Connect cluster, extracts its connectors, then dives into
     * a specific connector's details and diagnoses the Connect cluster.
     * Data flow: list connects → connect name → list connectors → connector name
     *            → get connector → connect cluster from connector → diagnose connect
     */
    @Test
    @Story("Connect cluster to connector deep-dive")
    void testConnectToConnectorDeepDive() {
        String ns = kafkaNamespace.getMetadata().getName();

        // Step 1: List Connect clusters and discover the first one
        AtomicReference<String> discoveredConnect = new AtomicReference<>();
        mcpClient.when()
            .toolsCall("list_kafka_connects", Map.of("namespace", ns), response -> {
                assertFalse(response.isError(), "Tool call should not return error");
                assertFalse(response.content().isEmpty(),
                    "Should have at least one Connect cluster");
                JsonNode connect = parseJson(response.content().getFirst().asText().text());
                String connectName = connect.path("name").asText();
                assertFalse(connectName.isEmpty(), "Connect cluster should have a name");
                discoveredConnect.set(connectName);
                LOGGER.info("Step 1 - Discovered Connect cluster: {}", connectName);
            })
            .thenAssertResults();

        // Step 2: List connectors filtered by discovered Connect cluster
        AtomicReference<String> discoveredConnector = new AtomicReference<>();
        mcpClient.when()
            .toolsCall("list_kafka_connectors",
                Map.of("namespace", ns, "connectCluster", discoveredConnect.get()), response -> {
                    assertFalse(response.isError(), "Tool call should not return error");
                    assertFalse(response.content().isEmpty(),
                        "Should have at least one connector in the discovered Connect cluster");
                    JsonNode connector = parseJson(response.content().getFirst().asText().text());
                    String connectorName = connector.path("name").asText();
                    assertFalse(connectorName.isEmpty(), "Connector should have a name");
                    discoveredConnector.set(connectorName);
                    LOGGER.info("Step 2 - Discovered connector: {}", connectorName);
                })
            .thenAssertResults();

        // Step 3: Get connector details and verify it belongs to the discovered Connect cluster
        AtomicReference<String> connectorConnectCluster = new AtomicReference<>();
        mcpClient.when()
            .toolsCall("get_kafka_connector",
                Map.of("connectorName", discoveredConnector.get(), "namespace", ns), response -> {
                    JsonNode connector = assertToolSuccess(response);
                    assertEquals(discoveredConnector.get(), connector.path("name").asText(),
                        "Connector name should match");
                    String connectCluster = connector.path("connect_cluster").asText();
                    assertEquals(discoveredConnect.get(), connectCluster,
                        "Connector should belong to the discovered Connect cluster");
                    connectorConnectCluster.set(connectCluster);
                    LOGGER.info("Step 3 - Connector '{}' belongs to Connect cluster '{}'",
                        discoveredConnector.get(), connectCluster);
                })
            .thenAssertResults();

        // Step 4: Diagnose the Connect cluster using the name extracted from connector
        mcpClient.when()
            .toolsCall("diagnose_kafka_connect",
                Map.of("connectName", connectorConnectCluster.get(), "namespace", ns), response -> {
                    JsonNode root = assertToolSuccess(response);
                    assertDiagnosticReport(root);
                    assertEquals(connectorConnectCluster.get(),
                        root.path("connect_cluster").path("name").asText(),
                        "Diagnostic should target the Connect cluster from connector");
                    LOGGER.info("Step 4 - Diagnosed Connect cluster '{}' ({} steps)",
                        connectorConnectCluster.get(), root.path("steps_completed").size());
                })
            .thenAssertResults();
    }

    /**
     * Discovers the operator, extracts a pod name from its logs response,
     * then fetches detailed pod info for the specific discovered pod.
     * Data flow: list operators → operator name → operator logs → pod name → pod details
     */
    @Test
    @Story("Operator discovery to pod details")
    void testOperatorDiscoveryToPodDetails() {
        String strimziNs = strimziNamespace.getMetadata().getName();

        // Step 1: Discover the operator
        AtomicReference<String> discoveredOperator = new AtomicReference<>();
        AtomicReference<String> discoveredOperatorNs = new AtomicReference<>();
        mcpClient.when()
            .toolsCall("list_strimzi_operators", Map.of("namespace", strimziNs), response -> {
                assertFalse(response.isError(), "Tool call should not return error");
                assertFalse(response.content().isEmpty(),
                    "Should discover at least one operator");
                JsonNode operator = parseJson(response.content().getFirst().asText().text());
                String operatorName = operator.path("name").asText();
                String operatorNs = operator.path("namespace").asText();
                assertFalse(operatorName.isEmpty(), "Operator should have a name");
                discoveredOperator.set(operatorName);
                discoveredOperatorNs.set(operatorNs);
                LOGGER.info("Step 1 - Discovered operator: {} in {}", operatorName, operatorNs);
            })
            .thenAssertResults();

        // Step 2: Get operator logs and extract a pod name
        AtomicReference<String> discoveredPod = new AtomicReference<>();
        mcpClient.when()
            .toolsCall("get_strimzi_operator_logs",
                Map.of("namespace", discoveredOperatorNs.get(), "tailLines", 50), response -> {
                    JsonNode root = assertToolSuccess(response);
                    JsonNode operatorPods = root.path("operator_pods");
                    assertTrue(operatorPods.isArray() && !operatorPods.isEmpty(),
                        "Should have at least one operator pod");
                    String podName = operatorPods.get(0).asText();
                    assertFalse(podName.isEmpty(), "Pod name should not be empty");
                    discoveredPod.set(podName);
                    LOGGER.info("Step 2 - Discovered operator pod: {} ({} log lines)",
                        podName, root.path("log_lines").asInt());
                })
            .thenAssertResults();

        // Step 3: Get pod details using the pod name discovered from logs
        mcpClient.when()
            .toolsCall("get_strimzi_operator_pod",
                Map.of("namespace", discoveredOperatorNs.get(),
                    "podName", discoveredPod.get()), response -> {
                    JsonNode root = assertToolSuccess(response);
                    assertTrue(root.path("total_pods").asInt() > 0,
                        "Should have at least one pod");
                    assertEquals("HEALTHY", root.path("health_status").asText(),
                        "Pod health should be HEALTHY");
                    JsonNode pods = root.path("pods");
                    assertTrue(pods.isArray() && !pods.isEmpty(), "Should have pod details");
                    assertEquals("Running", pods.get(0).path("phase").asText(),
                        "Pod should be Running");
                    LOGGER.info("Step 3 - Pod '{}' is {} and {}",
                        discoveredPod.get(),
                        pods.get(0).path("phase").asText(),
                        pods.get(0).path("ready").asBoolean() ? "ready" : "not ready");
                })
            .thenAssertResults();
    }

    /**
     * Gets cluster details, extracts a listener name from the response,
     * then uses that listener name to fetch listener-specific certificates
     * and diagnose connectivity for that listener.
     * Data flow: get cluster → listener name → certificates for listener → diagnose connectivity
     */
    @Test
    @Story("Listener-specific connectivity investigation")
    void testListenerSpecificConnectivity() {
        String ns = kafkaNamespace.getMetadata().getName();

        // Step 1: Get cluster and extract a TLS listener name
        AtomicReference<String> discoveredListener = new AtomicReference<>();
        AtomicReference<String> discoveredCluster = new AtomicReference<>();
        mcpClient.when()
            .toolsCall("get_kafka_cluster",
                Map.of("clusterName", Constants.KAFKA_CLUSTER_NAME, "namespace", ns), response -> {
                    JsonNode cluster = assertToolSuccess(response);
                    discoveredCluster.set(cluster.path("name").asText());
                    JsonNode listeners = cluster.path("listeners");
                    assertTrue(listeners.isArray() && listeners.size() >= 2,
                        "Should have at least 2 listeners");
                    for (JsonNode listener : listeners) {
                        if ("tls".equals(listener.path("name").asText())) {
                            discoveredListener.set(listener.path("name").asText());
                            break;
                        }
                    }
                    assertNotNull(discoveredListener.get(),
                        "Should find a TLS listener in cluster response");
                    LOGGER.info("Step 1 - Cluster '{}' has TLS listener '{}'",
                        discoveredCluster.get(), discoveredListener.get());
                })
            .thenAssertResults();

        // Step 2: Get certificates specifically for the discovered listener
        mcpClient.when()
            .toolsCall("get_kafka_cluster_certificates",
                Map.of("clusterName", discoveredCluster.get(), "namespace", ns,
                    "listenerName", discoveredListener.get()), response -> {
                    JsonNode root = assertToolSuccess(response);
                    assertEquals(discoveredCluster.get(), root.path("cluster_name").asText(),
                        "Certificate cluster should match");
                    JsonNode listenerAuth = root.path("listener_authentication");
                    assertTrue(listenerAuth.isArray() && !listenerAuth.isEmpty(),
                        "Should have listener authentication info");
                    assertFalse(root.toString().contains("PRIVATE KEY"),
                        "Should not expose private key material");
                    LOGGER.info("Step 2 - Listener '{}' has {} certificate(s), {} listener(s)",
                        discoveredListener.get(), root.path("certificates").size(), listenerAuth.size());
                })
            .thenAssertResults();

        // Step 3: Diagnose connectivity using the discovered listener
        mcpClient.when()
            .toolsCall("diagnose_kafka_connectivity",
                Map.of("clusterName", discoveredCluster.get(), "namespace", ns,
                    "listenerName", discoveredListener.get()), response -> {
                    JsonNode root = assertToolSuccess(response);
                    assertDiagnosticReport(root);
                    assertEquals(discoveredCluster.get(),
                        root.path("cluster").path("name").asText(),
                        "Diagnostic should target the discovered cluster");
                    assertFalse(root.path("bootstrap_servers").isMissingNode(),
                        "Should have bootstrap_servers section");
                    assertFalse(root.path("certificates").isMissingNode(),
                        "Should have certificates section for TLS listener");
                    LOGGER.info("Step 3 - Connectivity diagnosis for listener '{}' completed ({} steps)",
                        discoveredListener.get(), root.path("steps_completed").size());
                })
            .thenAssertResults();
    }

    /**
     * Discovers a bridge, extracts its pod info, then fetches logs
     * for the specific discovered pod.
     * Data flow: list bridges → bridge name → bridge pods → bridge logs
     */
    @Test
    @Story("Bridge discovery to logs")
    void testBridgeDiscoveryToLogs() {
        String ns = kafkaNamespace.getMetadata().getName();
        // Step 1: Discover bridge from list (no hardcoded name)
        AtomicReference<String> discoveredBridge = new AtomicReference<>();
        mcpClient.when()
            .toolsCall("list_kafka_bridges", Map.of("namespace", ns), response -> {
                assertFalse(response.isError(), "Tool call should not return error");
                assertFalse(response.content().isEmpty(),
                    "Should discover at least one bridge");
                JsonNode bridge = parseJson(response.content().getFirst().asText().text());
                String bridgeName = bridge.path("name").asText();
                assertFalse(bridgeName.isEmpty(), "Bridge should have a name");
                discoveredBridge.set(bridgeName);
                LOGGER.info("Step 1 - Discovered bridge: {}", bridgeName);
            })
            .thenAssertResults();

        // Step 2: Get bridge details using discovered name
        AtomicReference<Integer> expectedReplicas = new AtomicReference<>();
        mcpClient.when()
            .toolsCall("get_kafka_bridge",
                Map.of("bridgeName", discoveredBridge.get(), "namespace", ns), response -> {
                    JsonNode bridge = assertToolSuccess(response);
                    assertEquals(discoveredBridge.get(), bridge.path("name").asText(),
                        "Bridge name should match discovered name");
                    int replicas = bridge.path("replicas").path("expected").asInt();
                    assertTrue(replicas > 0, "Bridge should have at least 1 replica");
                    expectedReplicas.set(replicas);
                    LOGGER.info("Step 2 - Bridge '{}' has {} replica(s)",
                        discoveredBridge.get(), replicas);
                })
            .thenAssertResults();

        // Step 3: Get pods and verify count matches the replicas from step 2
        mcpClient.when()
            .toolsCall("get_kafka_bridge_pods",
                Map.of("bridgeName", discoveredBridge.get(), "namespace", ns), response -> {
                    JsonNode root = assertToolSuccess(response);
                    int totalPods = root.path("pod_summary").path("total_pods").asInt();
                    assertEquals(expectedReplicas.get().intValue(), totalPods,
                        "Pod count should match the replica count from bridge details");
                    LOGGER.info("Step 3 - Bridge '{}' has {} pod(s) matching {} replica(s)",
                        discoveredBridge.get(), totalPods, expectedReplicas.get());
                })
            .thenAssertResults();

        // Step 4: Get logs using discovered bridge name
        mcpClient.when()
            .toolsCall("get_kafka_bridge_logs",
                Map.of("bridgeName", discoveredBridge.get(), "namespace", ns,
                    "tailLines", 50), response -> {
                    JsonNode root = assertToolSuccess(response);
                    assertLogsResponse(root, "bridge_name", discoveredBridge.get());
                    LOGGER.info("Step 4 - Got {} log lines for bridge '{}'",
                        root.path("log_lines").asInt(), discoveredBridge.get());
                })
            .thenAssertResults();
    }
}
