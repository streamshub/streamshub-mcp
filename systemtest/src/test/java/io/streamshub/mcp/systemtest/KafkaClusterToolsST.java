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

import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * System tests for Kafka cluster MCP tools.
 * Deploys the MCP server into a cluster and verifies that
 * cluster discovery tools work against real Strimzi resources.
 */
@KubernetesTest
@DisplayName("Kafka Cluster MCP Tools")
@Epic("Strimzi MCP E2E")
@Feature("Kafka Cluster Tools")
class KafkaClusterToolsST extends AbstractST {

    private static final Logger LOGGER = LoggerFactory.getLogger(KafkaClusterToolsST.class);

    @InjectResourceManager
    KubeResourceManager krm;

    @ClassNamespace(name = Constants.MCP_NAMESPACE)
    static Namespace mcpNamespace;

    @ClassNamespace(name = Constants.STRIMZI_NAMESPACE, labels = {"app=strimzi"})
    static Namespace strimziNamespace;

    @ClassNamespace(name = Constants.KAFKA_NAMESPACE, labels = {"app=strimzi"})
    static Namespace kafkaNamespace;

    private static McpAssured.McpStreamableTestClient mcpClient;

    KafkaClusterToolsST() {
    }

    @BeforeAll
    void setup() {
        if (!Environment.SKIP_STRIMZI_INSTALL) {
            String kafkaNs = kafkaNamespace.getMetadata().getName();

            StrimziSetup.deploy(strimziNamespace.getMetadata().getName());

            KafkaTemplates.deployMetricsConfigMap(kafkaNs);

            krm.createOrUpdateResourceWithoutWait(
                KafkaNodePoolTemplates.controllerPool(kafkaNs, "controller-np",
                    Constants.KAFKA_CLUSTER_NAME, 3).build(),
                KafkaNodePoolTemplates.brokerPool(kafkaNs, "broker-np1",
                    Constants.KAFKA_CLUSTER_NAME, 3).build(),
                KafkaNodePoolTemplates.brokerPool(kafkaNs, "broker-np2",
                    Constants.KAFKA_CLUSTER_NAME, 3).build());

            krm.createOrUpdateResourceWithWait(
                KafkaTemplates.kafka(kafkaNs, Constants.KAFKA_CLUSTER_NAME, 3).build());
        }
        McpServerSetup.deploy(mcpNamespace.getMetadata().getName());
        McpServerSetup.deploySensitiveRbac(
            mcpNamespace.getMetadata().getName(),
            kafkaNamespace.getMetadata().getName());

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
    @DisplayName("list_kafka_clusters returns pre-deployed mcp-cluster")
    @Story("List Kafka Clusters")
    void testListKafkaClusters() {
        Map<String, Object> args = Map.of("namespace", Environment.KAFKA_NAMESPACE);
        mcpClient.when()
            .toolsCall("list_kafka_clusters", args, response -> {
                assertFalse(response.isError(), "list_kafka_clusters should not return error");

                // List tools return one content entry with a JSON array
                assertFalse(response.content().isEmpty(), "Should return at least one content entry");

                String json = response.content().getFirst().asText().text();
                LOGGER.info("list_kafka_clusters response (length={}):\n{}", json.length(), json);

                JsonNode root = parseJson(json);

                // May be a single object or an array
                JsonNode cluster = null;
                if (root.isArray()) {
                    for (JsonNode node : root) {
                        if (Environment.KAFKA_CLUSTER_NAME.equals(
                            node.path("name").asText(""))) {
                            cluster = node;
                            break;
                        }
                    }
                } else if (Environment.KAFKA_CLUSTER_NAME.equals(
                    root.path("name").asText(""))) {
                    cluster = root;
                }
                assertNotNull(cluster,
                    "Should find cluster '" + Environment.KAFKA_CLUSTER_NAME + "' in response");

                assertEquals(Environment.KAFKA_NAMESPACE, cluster.path("namespace").asText(),
                    "Namespace should match");
                assertEquals("Kafka", cluster.path("kind").asText(),
                    "Kind should be Kafka");
                assertEquals("Ready", cluster.path("readiness").asText(),
                    "Cluster should be Ready");
                assertFalse(cluster.path("kafka_version").isMissingNode(),
                    "Kafka version should be present");

                // Listeners
                assertTrue(cluster.has("listeners"), "Should have listeners");
                assertTrue(cluster.path("listeners").isArray(), "Listeners should be an array");
                assertTrue(cluster.path("listeners").size() >= 2,
                    "Should have at least 2 listeners (plain + tls)");

                // Replicas
                assertTrue(cluster.has("replicas"), "Should have replicas info");
                assertTrue(cluster.path("replicas").path("expected").asInt() > 0,
                    "Expected replicas should be > 0");
                assertEquals(cluster.path("replicas").path("expected").asInt(),
                    cluster.path("replicas").path("ready").asInt(),
                    "All replicas should be ready");
            })
            .thenAssertResults();
    }

    @Test
    @DisplayName("get_kafka_cluster returns detailed cluster info")
    @Story("Get Kafka Cluster")
    void testGetKafkaCluster() {
        Map<String, Object> args = Map.of(
            "clusterName", Environment.KAFKA_CLUSTER_NAME,
            "namespace", Environment.KAFKA_NAMESPACE);
        mcpClient.when()
            .toolsCall("get_kafka_cluster", args, response -> {
                assertFalse(response.isError(), "get_kafka_cluster should not return error");
                String json = response.content().getFirst().asText().text();
                LOGGER.info("get_kafka_cluster response:\n{}", json);

                JsonNode cluster = parseJson(json);
                assertEquals(Environment.KAFKA_CLUSTER_NAME, cluster.path("name").asText(),
                    "Cluster name should match");
                assertEquals(Environment.KAFKA_NAMESPACE, cluster.path("namespace").asText(),
                    "Namespace should match");
                assertEquals("Ready", cluster.path("readiness").asText(),
                    "Cluster should be Ready");

                // Kafka version
                assertFalse(cluster.path("kafka_version").isMissingNode(),
                    "Should have kafka_version");
                assertTrue(cluster.path("kafka_version").asText().matches("\\d+\\.\\d+\\.\\d+"),
                    "Kafka version should be in x.y.z format");

                // Conditions
                assertTrue(cluster.has("conditions"), "Should have conditions");
                JsonNode conditions = cluster.path("conditions");
                assertTrue(conditions.isArray() && conditions.size() > 0,
                    "Should have at least one condition");
                boolean hasReadyCondition = false;
                for (JsonNode cond : conditions) {
                    if ("Ready".equals(cond.path("type").asText())
                        && "True".equals(cond.path("status").asText())) {
                        hasReadyCondition = true;
                        break;
                    }
                }
                assertTrue(hasReadyCondition, "Should have Ready=True condition");

                // Listeners — check plain and tls exist
                assertTrue(cluster.has("listeners"), "Should have listeners");
                JsonNode listeners = cluster.path("listeners");
                boolean hasPlain = false;
                boolean hasTls = false;
                for (JsonNode listener : listeners) {
                    String name = listener.path("name").asText();
                    if ("plain".equals(name)) {
                        hasPlain = true;
                        assertEquals("internal", listener.path("type").asText(),
                            "Plain listener should be internal");
                        assertTrue(listener.path("bootstrap_address").asText()
                                .contains(Environment.KAFKA_CLUSTER_NAME),
                            "Bootstrap address should contain cluster name");
                    }
                    if ("tls".equals(name)) {
                        hasTls = true;
                        assertEquals("internal", listener.path("type").asText(),
                            "TLS listener should be internal");
                    }
                }
                assertTrue(hasPlain, "Should have 'plain' listener");
                assertTrue(hasTls, "Should have 'tls' listener");

                // Replicas
                assertTrue(cluster.has("replicas"), "Should have replicas info");
                assertEquals(cluster.path("replicas").path("expected").asInt(),
                    cluster.path("replicas").path("ready").asInt(),
                    "All replicas should be ready");

                // Age
                assertFalse(cluster.path("creation_time").isMissingNode(),
                    "Should have creation_time");
                assertFalse(cluster.path("age_minutes").isMissingNode(),
                    "Should have age_minutes");
                assertTrue(cluster.path("age_minutes").asLong() >= 0,
                    "Age should be non-negative");
            })
            .thenAssertResults();
    }

    // ---- Fleet/Cluster Overview ----

    @Test
    @DisplayName("get_kafka_fleet_overview returns fleet overview including mcp-cluster")
    @Story("Get Kafka Fleet Overview")
    void testGetKafkaFleetOverview() {
        Map<String, Object> args = Map.of();
        mcpClient.when()
            .toolsCall("get_kafka_fleet_overview", args, response -> {
                assertFalse(response.isError(), "get_kafka_fleet_overview should not return error");

                String text = response.content().getFirst().asText().text();
                LOGGER.info("get_kafka_fleet_overview response (length={})", text.length());

                JsonNode root = parseJson(text);
                assertTrue(root.path("total_clusters").asInt() >= 1,
                    "Should have at least 1 cluster");

                JsonNode statusDist = root.path("status_distribution");
                assertFalse(statusDist.isMissingNode(), "Should have status_distribution");
                assertTrue(statusDist.path("ready").asInt() >= 1,
                    "At least 1 cluster should be ready");

                JsonNode clusters = root.path("clusters");
                assertTrue(clusters.isArray() && !clusters.isEmpty(),
                    "clusters should be a non-empty array");

                assertEquals(9, root.path("total_brokers").asInt(),
                    "Should have 9 total replicas (3 node pools x 3 replicas)");

                JsonNode cluster = findByName(clusters, Constants.KAFKA_CLUSTER_NAME);
                assertNotNull(cluster, "Should find cluster '" + Constants.KAFKA_CLUSTER_NAME + "'");
                assertEquals("Ready", cluster.path("readiness").asText(),
                    "Cluster should be Ready");
                assertEquals(9, cluster.path("brokers").asInt(),
                    "Cluster should report 9 replicas");
            })
            .thenAssertResults();
    }

    @Test
    @DisplayName("get_kafka_fleet_overview scoped to specific namespace")
    @Story("Get Kafka Fleet Overview")
    void testGetKafkaFleetOverviewNamespaced() {
        String ns = kafkaNamespace.getMetadata().getName();
        Map<String, Object> args = Map.of("namespace", ns);
        mcpClient.when()
            .toolsCall("get_kafka_fleet_overview", args, response -> {
                assertFalse(response.isError(), "get_kafka_fleet_overview should not return error");

                String text = response.content().getFirst().asText().text();
                LOGGER.info("get_kafka_fleet_overview namespaced response (length={})", text.length());

                JsonNode root = parseJson(text);
                assertTrue(root.path("total_clusters").asInt() >= 1,
                    "Should have at least 1 cluster in namespace");
                assertEquals(ns, root.path("namespace_filter").asText(),
                    "namespace_filter should match the requested namespace");

                JsonNode clusters = root.path("clusters");
                assertTrue(clusters.isArray() && !clusters.isEmpty(),
                    "clusters should be a non-empty array");

                JsonNode cluster = findByName(clusters, Constants.KAFKA_CLUSTER_NAME);
                assertNotNull(cluster, "Should find cluster '" + Constants.KAFKA_CLUSTER_NAME + "'");
                assertEquals("Ready", cluster.path("readiness").asText(),
                    "Cluster should be Ready");
            })
            .thenAssertResults();
    }

    @Test
    @DisplayName("get_strimzi_kafka_cluster_overview returns cluster overview")
    @Story("Get Strimzi Kafka Cluster Overview")
    void testGetStrimziKafkaClusterOverview() {
        Map<String, Object> args = Map.of("clusterName", Constants.KAFKA_CLUSTER_NAME);
        mcpClient.when()
            .toolsCall("get_strimzi_kafka_cluster_overview", args, response -> {
                assertFalse(response.isError(), "get_strimzi_kafka_cluster_overview should not return error");

                String text = response.content().getFirst().asText().text();
                LOGGER.info("get_strimzi_kafka_cluster_overview response (length={})", text.length());

                JsonNode root = parseJson(text);
                JsonNode cluster = root.path("cluster");
                assertFalse(cluster.isMissingNode(), "Should have cluster section");
                assertEquals(Constants.KAFKA_CLUSTER_NAME, cluster.path("name").asText(),
                    "Cluster name should match");
                assertEquals("Ready", cluster.path("readiness").asText(),
                    "Cluster should be Ready");

                JsonNode nodePools = root.path("node_pools");
                assertTrue(nodePools.isArray(), "node_pools should be an array");
                assertEquals(3, nodePools.size(),
                    "Should have 3 node pools (controller-np, broker-np1, broker-np2)");

                JsonNode operator = root.path("operator");
                assertFalse(operator.isMissingNode(), "Should have operator section");
                assertFalse(operator.path("name").asText("").isEmpty(),
                    "Operator name should be non-empty");

                assertFalse(root.path("timestamp").isMissingNode(), "Should have timestamp");
            })
            .thenAssertResults();
    }

    @Test
    @DisplayName("get_strimzi_kafka_cluster_overview returns error for non-existent cluster")
    @Story("Get Strimzi Kafka Cluster Overview")
    void testGetStrimziKafkaClusterOverviewNotFound() {
        Map<String, Object> args = Map.of("clusterName", "nonexistent-cluster-xyz");
        mcpClient.when()
            .toolsCall("get_strimzi_kafka_cluster_overview", args, response -> {
                assertTrue(response.isError(),
                    "Should return error for non-existent cluster");

                String text = response.content().getFirst().asText().text();
                LOGGER.info("get_strimzi_kafka_cluster_overview error response: {}", text);
            })
            .thenAssertResults();
    }

    // ---- Cluster Error Cases ----

    @Test
    @DisplayName("get_kafka_cluster returns error for non-existent cluster")
    @Story("Get Kafka Cluster")
    void testGetKafkaClusterNotFound() {
        Map<String, Object> args = Map.of("clusterName", "nonexistent-cluster-xyz");
        mcpClient.when()
            .toolsCall("get_kafka_cluster", args, response -> {
                assertTrue(response.isError(),
                    "Should return error for non-existent cluster");

                String text = response.content().getFirst().asText().text();
                LOGGER.info("get_kafka_cluster error response: {}", text);
                assertTrue(text.toLowerCase(Locale.ROOT).contains("not found"),
                    "Error should mention 'not found'");
            })
            .thenAssertResults();
    }

    @Test
    @DisplayName("get_kafka_cluster returns error for wrong namespace")
    @Story("Get Kafka Cluster")
    void testGetKafkaClusterWrongNamespace() {
        Map<String, Object> args = Map.of(
            "clusterName", Constants.KAFKA_CLUSTER_NAME,
            "namespace", "nonexistent-namespace");
        mcpClient.when()
            .toolsCall("get_kafka_cluster", args, response -> {
                assertTrue(response.isError(),
                    "Should return error for wrong namespace");

                String text = response.content().getFirst().asText().text();
                LOGGER.info("get_kafka_cluster wrong namespace error response: {}", text);
            })
            .thenAssertResults();
    }

    @Test
    @DisplayName("list_kafka_clusters returns empty for default namespace")
    @Story("List Kafka Clusters")
    void testListKafkaClustersEmptyNamespace() {
        Map<String, Object> args = Map.of("namespace", "default");
        mcpClient.when()
            .toolsCall("list_kafka_clusters", args, response -> {
                assertFalse(response.isError(), "list_kafka_clusters should not return error");
                assertTrue(response.content().isEmpty(),
                    "Should return empty content for namespace with no Kafka clusters");
                LOGGER.info("list_kafka_clusters default namespace: empty response (as expected)");
            })
            .thenAssertResults();
    }

    // ---- Cluster Data Tools ----

    @Test
    @DisplayName("get_kafka_bootstrap_servers returns bootstrap servers")
    @Story("Get Kafka Bootstrap Servers")
    void testGetKafkaBootstrapServers() {
        Map<String, Object> args = Map.of("clusterName", Constants.KAFKA_CLUSTER_NAME);
        mcpClient.when()
            .toolsCall("get_kafka_bootstrap_servers", args, response -> {
                assertFalse(response.isError(), "get_kafka_bootstrap_servers should not return error");

                String text = response.content().getFirst().asText().text();
                LOGGER.info("get_kafka_bootstrap_servers response: {}", text);
                assertTrue(text.contains(Constants.KAFKA_CLUSTER_NAME),
                    "Bootstrap servers should contain cluster name");
            })
            .thenAssertResults();
    }

    @Test
    @DisplayName("get_kafka_cluster_certificates does not expose private keys")
    @Story("Get Kafka Cluster Certificates")
    void testGetKafkaClusterCertificates() {
        Map<String, Object> args = Map.of("clusterName", Constants.KAFKA_CLUSTER_NAME);
        mcpClient.when()
            .toolsCall("get_kafka_cluster_certificates", args, response -> {
                assertFalse(response.isError(), "get_kafka_cluster_certificates should not return error");

                String text = response.content().getFirst().asText().text();
                LOGGER.info("get_kafka_cluster_certificates response (length={})", text.length());
                assertFalse(text.contains("PRIVATE KEY"),
                    "Certificates response should NOT contain PRIVATE KEY");
            })
            .thenAssertResults();
    }

    @Test
    @DisplayName("get_kafka_cluster_certificates for specific listener")
    @Story("Get Kafka Cluster Certificates")
    void testGetKafkaClusterCertificatesForListener() {
        Map<String, Object> args = Map.of(
            "clusterName", Constants.KAFKA_CLUSTER_NAME,
            "listenerName", "tls");
        mcpClient.when()
            .toolsCall("get_kafka_cluster_certificates", args, response -> {
                assertFalse(response.isError(), "get_kafka_cluster_certificates should not return error");

                String text = response.content().getFirst().asText().text();
                LOGGER.info("get_kafka_cluster_certificates for tls listener response (length={})", text.length());

                JsonNode root = parseJson(text);
                assertEquals(Constants.KAFKA_CLUSTER_NAME, root.path("cluster_name").asText(),
                    "cluster_name should match");

                JsonNode certs = root.path("certificates");
                assertTrue(certs.isArray() && !certs.isEmpty(),
                    "TLS listener should produce certificates");
                for (JsonNode cert : certs) {
                    assertFalse(cert.path("secret_name").asText("").isEmpty(),
                        "Certificate should have secret_name");
                    assertFalse(cert.path("not_after").isMissingNode(),
                        "Certificate should have not_after");
                    assertTrue(cert.path("days_until_expiry").asLong() > 0,
                        "Certificate should not be expired");
                    assertFalse(cert.path("expired").asBoolean(),
                        "Certificate should not be expired");
                }

                assertEquals(Environment.KAFKA_NAMESPACE, root.path("namespace").asText(),
                    "namespace should match deployment namespace");

                JsonNode listenerAuth = root.path("listener_authentication");
                assertTrue(listenerAuth.isArray(), "listener_authentication should be an array");
                boolean hasTlsListener = false;
                for (JsonNode auth : listenerAuth) {
                    if ("tls".equals(auth.path("listener_name").asText())) {
                        hasTlsListener = true;
                        assertTrue(auth.path("tls_enabled").asBoolean(),
                            "TLS listener should have tls_enabled=true");
                    }
                }
                assertTrue(hasTlsListener, "Should have 'tls' listener in authentication list");
            })
            .thenAssertResults();
    }

    @Test
    @DisplayName("get_kafka_cluster_pods returns pod entries")
    @Story("Get Kafka Cluster Pods")
    void testGetKafkaClusterPods() {
        Map<String, Object> args = Map.of("clusterName", Constants.KAFKA_CLUSTER_NAME);
        mcpClient.when()
            .toolsCall("get_kafka_cluster_pods", args, response -> {
                assertFalse(response.isError(), "get_kafka_cluster_pods should not return error");

                String json = response.content().getFirst().asText().text();
                LOGGER.info("get_kafka_cluster_pods response (length={}):\n{}", json.length(), json);

                JsonNode root = parseJson(json);
                if (root.isArray()) {
                    assertTrue(root.size() > 0, "Should have at least one pod entry");
                }
            })
            .thenAssertResults();
    }

    // ---- Cluster Logs ----

    @Test
    @DisplayName("get_kafka_cluster_logs returns log lines")
    @Story("Get Kafka Cluster Logs")
    void testGetKafkaClusterLogs() {
        Map<String, Object> args = Map.of(
            "clusterName", Constants.KAFKA_CLUSTER_NAME,
            "tailLines", 20);
        mcpClient.when()
            .toolsCall("get_kafka_cluster_logs", args, response -> {
                assertFalse(response.isError(), "get_kafka_cluster_logs should not return error");

                String text = response.content().getFirst().asText().text();
                LOGGER.info("get_kafka_cluster_logs response (length={})", text.length());
                assertFalse(text.isEmpty(), "Logs response should not be empty");
            })
            .thenAssertResults();
    }

    @Test
    @DisplayName("get_kafka_cluster_logs with ERROR filter")
    @Story("Get Kafka Cluster Logs")
    void testGetKafkaClusterLogsErrorFilter() {
        Map<String, Object> args = Map.of(
            "clusterName", Constants.KAFKA_CLUSTER_NAME,
            "filter", "ERROR",
            "tailLines", 100,
            "sinceMinutes", 60);
        mcpClient.when()
            .toolsCall("get_kafka_cluster_logs", args, response -> {
                assertFalse(response.isError(), "get_kafka_cluster_logs with ERROR filter should not return error");

                String text = response.content().getFirst().asText().text();
                LOGGER.info("get_kafka_cluster_logs ERROR filter response (length={})", text.length());

                JsonNode root = parseJson(text);
                assertClusterLogsResponse(root, Constants.KAFKA_CLUSTER_NAME);
            })
            .thenAssertResults();
    }

    @Test
    @DisplayName("get_kafka_cluster_logs with keywords filter")
    @Story("Get Kafka Cluster Logs")
    void testGetKafkaClusterLogsKeywords() {
        Map<String, Object> args = Map.of(
            "clusterName", Constants.KAFKA_CLUSTER_NAME,
            "keywords", List.of("partition", "leader"),
            "tailLines", 50);
        mcpClient.when()
            .toolsCall("get_kafka_cluster_logs", args, response -> {
                assertFalse(response.isError(), "get_kafka_cluster_logs with keywords should not return error");

                String text = response.content().getFirst().asText().text();
                LOGGER.info("get_kafka_cluster_logs keywords response (length={})", text.length());

                JsonNode root = parseJson(text);
                assertClusterLogsResponse(root, Constants.KAFKA_CLUSTER_NAME);
            })
            .thenAssertResults();
    }

    @Test
    @DisplayName("get_kafka_cluster_logs for specific pod")
    @Story("Get Kafka Cluster Logs")
    void testGetKafkaClusterLogsSpecificPod() {
        String podName = Constants.KAFKA_CLUSTER_NAME + "-broker-np1-0";
        Map<String, Object> args = Map.of(
            "clusterName", Constants.KAFKA_CLUSTER_NAME,
            "podNames", List.of(podName),
            "tailLines", 20);
        mcpClient.when()
            .toolsCall("get_kafka_cluster_logs", args, response -> {
                assertFalse(response.isError(), "get_kafka_cluster_logs for specific pod should not return error");

                String text = response.content().getFirst().asText().text();
                LOGGER.info("get_kafka_cluster_logs specific pod response (length={})", text.length());

                JsonNode root = parseJson(text);
                assertClusterLogsResponse(root, Constants.KAFKA_CLUSTER_NAME);

                JsonNode pods = root.path("pods");
                assertEquals(1, pods.size(), "Should contain exactly one pod");
                assertEquals(podName, pods.get(0).asText(), "Pod name should match requested pod");
            })
            .thenAssertResults();
    }

    @Test
    @DisplayName("get_kafka_cluster_logs with no-match filter returns without error")
    @Story("Get Kafka Cluster Logs")
    void testGetKafkaClusterLogsNoMatch() {
        Map<String, Object> args = Map.of(
            "clusterName", Constants.KAFKA_CLUSTER_NAME,
            "filter", "ZZZZNONEXISTENTZZZZ",
            "tailLines", 100);
        mcpClient.when()
            .toolsCall("get_kafka_cluster_logs", args, response -> {
                assertFalse(response.isError(), "get_kafka_cluster_logs with no-match filter should not return error");

                String text = response.content().getFirst().asText().text();
                LOGGER.info("get_kafka_cluster_logs no-match filter response (length={})", text.length());

                JsonNode root = parseJson(text);
                assertClusterLogsResponse(root, Constants.KAFKA_CLUSTER_NAME);
                assertEquals(0, root.path("log_lines").asInt(),
                    "Non-matching filter should return zero log lines");
            })
            .thenAssertResults();
    }

    @Test
    @DisplayName("get_kafka_cluster_logs with large tailLines completes without timeout")
    @Story("Get Kafka Cluster Logs")
    void testGetKafkaClusterLogsLargeRequest() {
        Map<String, Object> args = Map.of(
            "clusterName", Constants.KAFKA_CLUSTER_NAME,
            "tailLines", 1000);
        mcpClient.when()
            .toolsCall("get_kafka_cluster_logs", args, response -> {
                assertFalse(response.isError(), "get_kafka_cluster_logs with large tailLines should not return error");

                String text = response.content().getFirst().asText().text();
                LOGGER.info("get_kafka_cluster_logs large request response (length={})", text.length());

                JsonNode root = parseJson(text);
                assertClusterLogsResponse(root, Constants.KAFKA_CLUSTER_NAME);
                assertTrue(root.path("log_lines").asInt() >= 0,
                    "log_lines should be non-negative");
            })
            .thenAssertResults();
    }

    // ---- Node Pool Tools ----

    @Test
    @DisplayName("list_kafka_node_pools returns non-empty list")
    @Story("List Kafka Node Pools")
    void testListKafkaNodePools() {
        Map<String, Object> args = Map.of(
            "clusterName", Constants.KAFKA_CLUSTER_NAME,
            "namespace", kafkaNamespace.getMetadata().getName());
        mcpClient.when()
            .toolsCall("list_kafka_node_pools", args, response -> {
                assertFalse(response.isError(), "list_kafka_node_pools should not return error");
                assertFalse(response.content().isEmpty(), "Should return at least one node pool");

                for (var entry : response.content()) {
                    String json = entry.asText().text();
                    LOGGER.info("list_kafka_node_pools entry:\n{}", json);
                    JsonNode pool = parseJson(json);
                    assertFalse(pool.path("name").isMissingNode(), "Pool should have a name");
                }
            })
            .thenAssertResults();
    }

    @Test
    @DisplayName("get_kafka_node_pool returns controller pool with controller role")
    @Story("Get Kafka Node Pool")
    void testGetKafkaNodePoolController() {
        Map<String, Object> args = Map.of(
            "clusterName", Constants.KAFKA_CLUSTER_NAME,
            "nodePoolName", "controller-np");
        mcpClient.when()
            .toolsCall("get_kafka_node_pool", args, response -> {
                assertFalse(response.isError(), "get_kafka_node_pool should not return error");

                String json = response.content().getFirst().asText().text();
                LOGGER.info("get_kafka_node_pool controller response:\n{}", json);

                JsonNode pool = parseJson(json);
                assertTrue(pool.path("roles").toString().contains("controller"),
                    "Controller pool should have controller role");
            })
            .thenAssertResults();
    }

    @Test
    @DisplayName("get_kafka_node_pool returns broker pool with broker role")
    @Story("Get Kafka Node Pool")
    void testGetKafkaNodePoolBroker() {
        Map<String, Object> args = Map.of(
            "clusterName", Constants.KAFKA_CLUSTER_NAME,
            "nodePoolName", "broker-np1");
        mcpClient.when()
            .toolsCall("get_kafka_node_pool", args, response -> {
                assertFalse(response.isError(), "get_kafka_node_pool should not return error");

                String json = response.content().getFirst().asText().text();
                LOGGER.info("get_kafka_node_pool broker response:\n{}", json);

                JsonNode pool = parseJson(json);
                assertTrue(pool.path("roles").toString().contains("broker"),
                    "Broker pool should have broker role");
            })
            .thenAssertResults();
    }

    @Test
    @DisplayName("get_kafka_node_pool_pods returns pods for broker pool")
    @Story("Get Kafka Node Pool Pods")
    void testGetKafkaNodePoolPods() {
        Map<String, Object> args = Map.of(
            "clusterName", Constants.KAFKA_CLUSTER_NAME,
            "nodePoolName", "broker-np1");
        mcpClient.when()
            .toolsCall("get_kafka_node_pool_pods", args, response -> {
                assertFalse(response.isError(), "get_kafka_node_pool_pods should not return error");

                String json = response.content().getFirst().asText().text();
                LOGGER.info("get_kafka_node_pool_pods response (length={}):\n{}", json.length(), json);

                JsonNode root = parseJson(json);
                assertNotNull(root, "Response should be valid JSON");
            })
            .thenAssertResults();
    }

    @Test
    @DisplayName("get_kafka_node_pool returns error for non-existent pool")
    @Story("Get Kafka Node Pool")
    void testGetKafkaNodePoolNotFound() {
        Map<String, Object> args = Map.of(
            "clusterName", Constants.KAFKA_CLUSTER_NAME,
            "nodePoolName", "nonexistent-pool");
        mcpClient.when()
            .toolsCall("get_kafka_node_pool", args, response -> {
                assertTrue(response.isError(),
                    "Should return error for non-existent node pool");

                String text = response.content().getFirst().asText().text();
                LOGGER.info("get_kafka_node_pool error response: {}", text);
            })
            .thenAssertResults();
    }

    private static JsonNode findByName(final JsonNode root, final String name) {
        if (root.isArray()) {
            for (JsonNode node : root) {
                if (name.equals(node.path("name").asText(""))) {
                    return node;
                }
            }
        } else if (name.equals(root.path("name").asText(""))) {
            return root;
        }
        return null;
    }

    private static void assertClusterLogsResponse(JsonNode root, String expectedClusterName) {
        assertEquals(expectedClusterName, root.path("cluster_name").asText(),
            "cluster_name should match");
        assertTrue(root.path("pods").isArray() && !root.path("pods").isEmpty(),
            "pods should be a non-empty array");
        assertTrue(root.path("log_lines").isNumber(), "log_lines should be a number");
        assertFalse(root.path("timestamp").isMissingNode(), "Should have timestamp");
        assertFalse(root.path("message").isMissingNode(), "Should have message");
    }
}
