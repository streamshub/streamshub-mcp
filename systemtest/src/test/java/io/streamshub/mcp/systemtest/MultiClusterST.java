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
import io.streamshub.mcp.systemtest.templates.strimzi.KafkaNodePoolTemplates;
import io.streamshub.mcp.systemtest.templates.strimzi.KafkaTemplates;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * System tests for admin perspective with multiple Kafka clusters in the
 * same namespace. Verifies fleet-wide visibility, per-cluster isolation,
 * and cluster comparison tools.
 */
@Epic("Strimzi MCP E2E")
@Feature("Multi-Cluster")
class MultiClusterST extends AbstractST {

    private static final Logger LOGGER = LoggerFactory.getLogger(MultiClusterST.class);

    @InjectResourceManager
    KubeResourceManager krm;

    @ClassNamespace(name = Constants.MCP_NAMESPACE)
    static Namespace mcpNamespace;

    @ClassNamespace(name = Constants.STRIMZI_NAMESPACE, labels = {"app=strimzi"})
    static Namespace strimziNamespace;

    @ClassNamespace(name = Constants.KAFKA_NAMESPACE, labels = {"app=strimzi"})
    static Namespace kafkaNamespace;

    private static McpAssured.McpStreamableTestClient mcpClient;

    MultiClusterST() {
    }

    @BeforeAll
    void setup() {
        if (!Environment.SKIP_STRIMZI_INSTALL) {
            String kafkaNs = kafkaNamespace.getMetadata().getName();

            StrimziSetup.deploy(strimziNamespace.getMetadata().getName());

            // Cluster 1
            krm.createOrUpdateResourceWithoutWait(
                KafkaNodePoolTemplates.controllerPool(kafkaNs, "controller-np",
                    Constants.KAFKA_CLUSTER_NAME, 1).build(),
                KafkaNodePoolTemplates.brokerPool(kafkaNs, "broker-np",
                    Constants.KAFKA_CLUSTER_NAME, 1).build());
            krm.createOrUpdateResourceWithWait(
                KafkaTemplates.kafka(kafkaNs, Constants.KAFKA_CLUSTER_NAME, 1).build());

            // Cluster 2 in same namespace
            krm.createOrUpdateResourceWithoutWait(
                KafkaNodePoolTemplates.controllerPool(kafkaNs, "controller-np-2",
                    Constants.KAFKA_CLUSTER_NAME_2, 1).build(),
                KafkaNodePoolTemplates.brokerPool(kafkaNs, "broker-np-2",
                    Constants.KAFKA_CLUSTER_NAME_2, 1).build());
            krm.createOrUpdateResourceWithWait(
                KafkaTemplates.kafka(kafkaNs, Constants.KAFKA_CLUSTER_NAME_2, 1).build());
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

    // ---- Fleet Overview ----

    @Test
    @DisplayName("get_kafka_fleet_overview shows both clusters")
    @Story("Fleet Overview")
    void testFleetOverviewBothClusters() {
        Map<String, Object> args = Map.of();
        mcpClient.when()
            .toolsCall("get_kafka_fleet_overview", args, response -> {
                JsonNode root = assertToolSuccess(response);
                LOGGER.info("Fleet overview: total_clusters={}, total_brokers={}",
                    root.path("total_clusters").asInt(), root.path("total_brokers").asInt());

                assertTrue(root.path("total_clusters").asInt() >= 2,
                    "Should have at least 2 clusters");
                assertTrue(root.path("total_brokers").asInt() >= 2,
                    "Should have at least 2 total brokers (1 per cluster)");

                JsonNode clusters = root.path("clusters");
                assertTrue(clusters.isArray(), "clusters should be an array");
                assertNotNull(findByName(clusters, Constants.KAFKA_CLUSTER_NAME),
                    "Should find " + Constants.KAFKA_CLUSTER_NAME);
                assertNotNull(findByName(clusters, Constants.KAFKA_CLUSTER_NAME_2),
                    "Should find " + Constants.KAFKA_CLUSTER_NAME_2);
            })
            .thenAssertResults();
    }

    @Test
    @DisplayName("get_kafka_fleet_overview with namespace filter shows both clusters")
    @Story("Fleet Overview")
    void testFleetOverviewNamespaceFilter() {
        Map<String, Object> args = Map.of("namespace", Constants.KAFKA_NAMESPACE);
        mcpClient.when()
            .toolsCall("get_kafka_fleet_overview", args, response -> {
                JsonNode root = assertToolSuccess(response);
                LOGGER.info("Fleet overview (ns filter): total_clusters={}",
                    root.path("total_clusters").asInt());

                assertTrue(root.path("total_clusters").asInt() >= 2,
                    "Both clusters are in same namespace — should see at least 2");
            })
            .thenAssertResults();
    }

    // ---- List / Get Clusters ----

    @Test
    @DisplayName("list_kafka_clusters returns both clusters in same namespace")
    @Story("List Clusters")
    void testListClustersReturnsBoth() {
        Map<String, Object> args = Map.of("namespace", Constants.KAFKA_NAMESPACE);
        mcpClient.when()
            .toolsCall("list_kafka_clusters", args, response -> {
                assertFalse(response.isError(), "list_kafka_clusters should not return error");

                // MCP framework returns one content block per list element
                LOGGER.info("list_kafka_clusters: {} content entries", response.content().size());
                assertTrue(response.content().size() >= 2,
                    "Should have at least 2 content entries (one per cluster)");

                List<JsonNode> clusters = response.content().stream()
                    .map(c -> parseJson(c.asText().text()))
                    .toList();

                assertNotNull(
                    clusters.stream()
                        .filter(c -> Constants.KAFKA_CLUSTER_NAME.equals(c.path("name").asText()))
                        .findFirst().orElse(null),
                    "Should find " + Constants.KAFKA_CLUSTER_NAME);
                assertNotNull(
                    clusters.stream()
                        .filter(c -> Constants.KAFKA_CLUSTER_NAME_2.equals(c.path("name").asText()))
                        .findFirst().orElse(null),
                    "Should find " + Constants.KAFKA_CLUSTER_NAME_2);
            })
            .thenAssertResults();
    }

    @Test
    @DisplayName("get_kafka_cluster returns correct cluster by name (cluster 1)")
    @Story("Get Cluster")
    void testGetCluster1() {
        Map<String, Object> args = Map.of("clusterName", Constants.KAFKA_CLUSTER_NAME);
        mcpClient.when()
            .toolsCall("get_kafka_cluster", args, response -> {
                JsonNode cluster = assertToolSuccess(response);
                assertEquals(Constants.KAFKA_CLUSTER_NAME, cluster.path("name").asText());
                assertEquals("Ready", cluster.path("readiness").asText());
            })
            .thenAssertResults();
    }

    @Test
    @DisplayName("get_kafka_cluster returns correct cluster by name (cluster 2)")
    @Story("Get Cluster")
    void testGetCluster2() {
        Map<String, Object> args = Map.of("clusterName", Constants.KAFKA_CLUSTER_NAME_2);
        mcpClient.when()
            .toolsCall("get_kafka_cluster", args, response -> {
                JsonNode cluster = assertToolSuccess(response);
                assertEquals(Constants.KAFKA_CLUSTER_NAME_2, cluster.path("name").asText());
                assertEquals("Ready", cluster.path("readiness").asText());
            })
            .thenAssertResults();
    }

    // ---- Bootstrap & Node Pools ----

    @Test
    @DisplayName("get_kafka_bootstrap_servers returns different addresses per cluster")
    @Story("Bootstrap Servers")
    void testBootstrapServersDiffer() {
        StringBuilder bootstrap1 = new StringBuilder();
        StringBuilder bootstrap2 = new StringBuilder();

        mcpClient.when()
            .toolsCall("get_kafka_bootstrap_servers",
                Map.of("clusterName", Constants.KAFKA_CLUSTER_NAME), response -> {
                    JsonNode root = assertToolSuccess(response);
                    bootstrap1.append(root.toString());
                    LOGGER.info("Bootstrap servers (cluster 1): {}", bootstrap1);
                })
            .thenAssertResults();

        mcpClient.when()
            .toolsCall("get_kafka_bootstrap_servers",
                Map.of("clusterName", Constants.KAFKA_CLUSTER_NAME_2), response -> {
                    JsonNode root = assertToolSuccess(response);
                    bootstrap2.append(root.toString());
                    LOGGER.info("Bootstrap servers (cluster 2): {}", bootstrap2);
                })
            .thenAssertResults();

        assertNotEquals(bootstrap1.toString(), bootstrap2.toString(),
            "Bootstrap servers should differ between clusters");
    }

    @Test
    @DisplayName("list_kafka_node_pools returns per-cluster pools")
    @Story("Node Pools")
    void testNodePoolsPerCluster() {
        mcpClient.when()
            .toolsCall("list_kafka_node_pools",
                Map.of("clusterName", Constants.KAFKA_CLUSTER_NAME,
                    "namespace", Constants.KAFKA_NAMESPACE), response -> {
                    assertFalse(response.isError());
                    assertFalse(response.content().isEmpty(), "Cluster 1 should have node pools");
                    LOGGER.info("Node pools (cluster 1): {} entries", response.content().size());
                })
            .thenAssertResults();

        mcpClient.when()
            .toolsCall("list_kafka_node_pools",
                Map.of("clusterName", Constants.KAFKA_CLUSTER_NAME_2,
                    "namespace", Constants.KAFKA_NAMESPACE), response -> {
                    assertFalse(response.isError());
                    assertFalse(response.content().isEmpty(), "Cluster 2 should have node pools");
                    LOGGER.info("Node pools (cluster 2): {} entries", response.content().size());
                })
            .thenAssertResults();
    }

    // ---- Cluster Overview & Comparison ----

    @Test
    @DisplayName("get_strimzi_kafka_cluster_overview returns per-cluster details")
    @Story("Cluster Overview")
    void testClusterOverviewPerCluster() {
        mcpClient.when()
            .toolsCall("get_strimzi_kafka_cluster_overview",
                Map.of("clusterName", Constants.KAFKA_CLUSTER_NAME), response -> {
                    JsonNode root = assertToolSuccess(response);
                    assertEquals(Constants.KAFKA_CLUSTER_NAME,
                        root.path("cluster").path("name").asText());
                    LOGGER.info("Cluster overview (1): pools={}",
                        root.path("node_pools").size());
                })
            .thenAssertResults();

        mcpClient.when()
            .toolsCall("get_strimzi_kafka_cluster_overview",
                Map.of("clusterName", Constants.KAFKA_CLUSTER_NAME_2), response -> {
                    JsonNode root = assertToolSuccess(response);
                    assertEquals(Constants.KAFKA_CLUSTER_NAME_2,
                        root.path("cluster").path("name").asText());
                    LOGGER.info("Cluster overview (2): pools={}",
                        root.path("node_pools").size());
                })
            .thenAssertResults();
    }

    @Test
    @DisplayName("compare_kafka_clusters compares both clusters in same namespace")
    @Story("Cluster Comparison")
    void testCompareClusters() {
        Map<String, Object> args = Map.of(
            "clusterName1", Constants.KAFKA_CLUSTER_NAME,
            "clusterName2", Constants.KAFKA_CLUSTER_NAME_2);
        mcpClient.when()
            .toolsCall("compare_kafka_clusters", args, response -> {
                JsonNode root = assertToolSuccess(response);
                LOGGER.info("compare_kafka_clusters response (length={})",
                    root.toString().length());
            })
            .thenAssertResults();
    }
}
