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
import io.streamshub.mcp.systemtest.templates.strimzi.KafkaNodePoolTemplates;
import io.streamshub.mcp.systemtest.templates.strimzi.KafkaTemplates;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
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
    @Story("get_kafka_fleet_overview shows both clusters")
    @Story("Fleet Overview")
    void testFleetOverviewBothClusters() {
        Map<String, Object> args = Map.of();
        mcpClient.when()
            .toolsCall("get_kafka_fleet_overview", args, response -> {
                JsonNode root = assertToolSuccess(response);
                LOGGER.info("Fleet overview: total_clusters={}, total_brokers={}",
                    root.path("total_clusters").asInt(), root.path("total_brokers").asInt());
                LOGGER.debug("get_kafka_fleet_overview response:\n{}", response.content().getFirst().asText().text());
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
    @Story("get_kafka_fleet_overview with namespace filter shows both clusters")
    void testFleetOverviewNamespaceFilter() {
        Map<String, Object> args = Map.of("namespace", Constants.KAFKA_NAMESPACE);

        mcpClient.when()
            .toolsCall("get_kafka_fleet_overview", args, response -> {
                JsonNode root = assertToolSuccess(response);
                LOGGER.info("Fleet overview (ns filter): total_clusters={}",
                    root.path("total_clusters").asInt());
                LOGGER.debug("get_kafka_fleet_overview (ns filter) response:\n{}", response.content().getFirst().asText().text());
                assertTrue(root.path("total_clusters").asInt() >= 2,
                    "Both clusters are in same namespace — should see at least 2");
            })
            .thenAssertResults();
    }

    // ---- List / Get Clusters ----

    @Test
    @Story("list_kafka_clusters returns both clusters in same namespace")
    void testListClustersReturnsBoth() {
        Map<String, Object> args = Map.of("namespace", Constants.KAFKA_NAMESPACE);

        mcpClient.when()
            .toolsCall("list_kafka_clusters", args, response -> {
                assertToolSuccess(response);
                // MCP framework returns one content block per list element
                LOGGER.info("list_kafka_clusters response ({} content entries)", response.content().size());
                LOGGER.debug("list_kafka_clusters response:\n{}", response.content().getFirst().asText().text());
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
    @Story("get_kafka_cluster returns correct cluster by name (cluster 1)")
    void testGetCluster1() {
        Map<String, Object> args = Map.of("clusterName", Constants.KAFKA_CLUSTER_NAME);

        mcpClient.when()
            .toolsCall("get_kafka_cluster", args, response -> {
                JsonNode cluster = assertToolSuccess(response);
                LOGGER.info("get_kafka_cluster (cluster 1) response (length={})",
                    response.content().getFirst().asText().text().length());
                LOGGER.debug("get_kafka_cluster (cluster 1) response:\n{}", response.content().getFirst().asText().text());
                assertEquals(Constants.KAFKA_CLUSTER_NAME, cluster.path("name").asText());
                assertEquals("Ready", cluster.path("readiness").asText());
            })
            .thenAssertResults();
    }

    @Test
    @Story("get_kafka_cluster returns correct cluster by name (cluster 2)")
    void testGetCluster2() {
        Map<String, Object> args = Map.of("clusterName", Constants.KAFKA_CLUSTER_NAME_2);

        mcpClient.when()
            .toolsCall("get_kafka_cluster", args, response -> {
                JsonNode cluster = assertToolSuccess(response);
                LOGGER.info("get_kafka_cluster (cluster 2) response (length={})",
                    response.content().getFirst().asText().text().length());
                LOGGER.debug("get_kafka_cluster (cluster 2) response:\n{}", response.content().getFirst().asText().text());
                assertEquals(Constants.KAFKA_CLUSTER_NAME_2, cluster.path("name").asText());
                assertEquals("Ready", cluster.path("readiness").asText());
            })
            .thenAssertResults();
    }

    // ---- Bootstrap & Node Pools ----

    @Test
    @Story("get_kafka_bootstrap_servers returns different addresses per cluster")
    void testBootstrapServersDiffer() {
        StringBuilder bootstrap1 = new StringBuilder();
        StringBuilder bootstrap2 = new StringBuilder();

        mcpClient.when()
            .toolsCall("get_kafka_bootstrap_servers",
                Map.of("clusterName", Constants.KAFKA_CLUSTER_NAME), response -> {
                    JsonNode root = assertToolSuccess(response);
                    LOGGER.info("Bootstrap servers (cluster 1) response (length={})",
                        response.content().getFirst().asText().text().length());
                    LOGGER.debug("Bootstrap servers (cluster 1) response:\n{}", response.content().getFirst().asText().text());
                    bootstrap1.append(root.toString());
                })
            .thenAssertResults();

        mcpClient.when()
            .toolsCall("get_kafka_bootstrap_servers",
                Map.of("clusterName", Constants.KAFKA_CLUSTER_NAME_2), response -> {
                    JsonNode root = assertToolSuccess(response);
                    LOGGER.info("Bootstrap servers (cluster 2) response (length={})",
                        response.content().getFirst().asText().text().length());
                    LOGGER.debug("Bootstrap servers (cluster 2) response:\n{}", response.content().getFirst().asText().text());
                    bootstrap2.append(root.toString());
                })
            .thenAssertResults();

        assertNotEquals(bootstrap1.toString(), bootstrap2.toString(),
            "Bootstrap servers should differ between clusters");
    }

    @Test
    @Story("list_kafka_node_pools returns per-cluster pools")
    void testNodePoolsPerCluster() {
        // TODO - assert that nodepools are to different clusters

        mcpClient.when()
            .toolsCall("list_kafka_node_pools",
                Map.of("clusterName", Constants.KAFKA_CLUSTER_NAME,
                    "namespace", Constants.KAFKA_NAMESPACE), response -> {
                    assertToolSuccess(response);
                    assertFalse(response.content().isEmpty(), "Cluster 1 should have node pools");
                    LOGGER.info("Node pools (cluster 1): {} entries", response.content().size());
                    LOGGER.debug("Node pools (cluster 1) response:\n{}", response.content().getFirst().asText().text());
                })
            .thenAssertResults();

        mcpClient.when()
            .toolsCall("list_kafka_node_pools",
                Map.of("clusterName", Constants.KAFKA_CLUSTER_NAME_2,
                    "namespace", Constants.KAFKA_NAMESPACE), response -> {
                    assertToolSuccess(response);
                    assertFalse(response.content().isEmpty(), "Cluster 2 should have node pools");
                    LOGGER.info("Node pools (cluster 2): {} entries", response.content().size());
                    LOGGER.debug("Node pools (cluster 2) response:\n{}", response.content().getFirst().asText().text());
                })
            .thenAssertResults();
    }

    // ---- Cluster Overview & Comparison ----

    @Test
    @Story("get_strimzi_kafka_cluster_overview returns per-cluster details")
    void testClusterOverviewPerCluster() {
        // TODO - assert that clusters are different and also assert specific configs
        mcpClient.when()
            .toolsCall("get_strimzi_kafka_cluster_overview",
                Map.of("clusterName", Constants.KAFKA_CLUSTER_NAME), response -> {
                    JsonNode root = assertToolSuccess(response);
                    LOGGER.info("Cluster overview (1): pools={}",
                        root.path("node_pools").size());
                    LOGGER.debug("get_strimzi_kafka_cluster_overview (1) response:\n{}", response.content().getFirst().asText().text());
                    assertEquals(Constants.KAFKA_CLUSTER_NAME,
                        root.path("cluster").path("name").asText());
                })
            .thenAssertResults();

        mcpClient.when()
            .toolsCall("get_strimzi_kafka_cluster_overview",
                Map.of("clusterName", Constants.KAFKA_CLUSTER_NAME_2), response -> {
                    JsonNode root = assertToolSuccess(response);
                    LOGGER.info("Cluster overview (2): pools={}",
                        root.path("node_pools").size());
                    LOGGER.debug("get_strimzi_kafka_cluster_overview (2) response:\n{}", response.content().getFirst().asText().text());
                    assertEquals(Constants.KAFKA_CLUSTER_NAME_2,
                        root.path("cluster").path("name").asText());
                })
            .thenAssertResults();
    }

    @Test
    @Story("compare_kafka_clusters compares both clusters in same namespace")
    void testCompareClusters() {
        Map<String, Object> args = Map.of(
            "clusterName1", Constants.KAFKA_CLUSTER_NAME,
            "clusterName2", Constants.KAFKA_CLUSTER_NAME_2);

        mcpClient.when()
            .toolsCall("compare_kafka_clusters", args, response -> {
                JsonNode root = assertToolSuccess(response);
                LOGGER.info("compare_kafka_clusters response (length={})",
                    response.content().getFirst().asText().text().length());
                LOGGER.debug("compare_kafka_clusters response:\n{}", response.content().getFirst().asText().text());
                assertEquals(Constants.KAFKA_CLUSTER_NAME,
                    root.path("cluster1_config").path("name").asText(),
                    "cluster1_config.name should match first cluster");
                assertEquals(Constants.KAFKA_CLUSTER_NAME_2,
                    root.path("cluster2_config").path("name").asText(),
                    "cluster2_config.name should match second cluster");
                assertFalse(root.path("steps_completed").isMissingNode(),
                    "Should have steps_completed");
                assertTrue(root.path("steps_completed").isArray()
                        && !root.path("steps_completed").isEmpty(),
                    "steps_completed should be a non-empty array");
                assertFalse(root.path("timestamp").isMissingNode(),
                    "Should have timestamp");
                assertFalse(root.path("message").isMissingNode(),
                    "Should have message");
            })
            .thenAssertResults();
    }
}
