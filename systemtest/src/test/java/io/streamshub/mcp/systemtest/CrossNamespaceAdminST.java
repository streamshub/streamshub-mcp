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
import io.strimzi.api.kafka.model.kafka.Kafka;
import io.strimzi.api.kafka.model.nodepool.KafkaNodePool;
import io.strimzi.api.kafka.model.topic.KafkaTopic;
import io.strimzi.api.kafka.model.user.KafkaUser;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * System tests for admin perspective with same-named Kafka clusters in
 * different namespaces. Verifies disambiguation behavior when namespace
 * is omitted, and correct resolution when namespace is provided.
 */
@KubernetesTest
@DisplayName("Cross-Namespace Admin (Same-Name Disambiguation)")
@Epic("Strimzi MCP E2E")
@Feature("Cross-Namespace")
class CrossNamespaceAdminST extends AbstractST {

    private static final Logger LOGGER = LoggerFactory.getLogger(CrossNamespaceAdminST.class);

    @InjectResourceManager
    KubeResourceManager krm;

    @ClassNamespace(name = Constants.MCP_NAMESPACE)
    static Namespace mcpNamespace;

    @ClassNamespace(name = Constants.STRIMZI_NAMESPACE, labels = {"app=strimzi"})
    static Namespace strimziNamespace;

    @ClassNamespace(name = Constants.KAFKA_NAMESPACE, labels = {"app=strimzi"})
    static Namespace kafkaNamespace;

    @ClassNamespace(name = Constants.KAFKA_NAMESPACE_2, labels = {"app=strimzi"})
    static Namespace kafkaNamespace2;

    private static McpAssured.McpStreamableTestClient mcpClient;

    CrossNamespaceAdminST() {
    }

    @BeforeAll
    void setup() {
        if (!Environment.SKIP_STRIMZI_INSTALL) {
            String kafkaNs = kafkaNamespace.getMetadata().getName();
            String kafkaNs2 = kafkaNamespace2.getMetadata().getName();

            StrimziSetup.deploy(strimziNamespace.getMetadata().getName());

            // Same-named cluster in namespace 1
            krm.createOrUpdateResourceWithoutWait(
                KafkaNodePoolTemplates.controllerPool(kafkaNs, "controller-np",
                    Constants.KAFKA_CLUSTER_NAME, 1).build(),
                KafkaNodePoolTemplates.brokerPool(kafkaNs, "broker-np",
                    Constants.KAFKA_CLUSTER_NAME, 1).build());
            krm.createOrUpdateResourceWithWait(
                KafkaTemplates.kafka(kafkaNs, Constants.KAFKA_CLUSTER_NAME, 1).build());

            // Same-named cluster in namespace 2
            krm.createOrUpdateResourceWithoutWait(
                KafkaNodePoolTemplates.controllerPool(kafkaNs2, "controller-np",
                    Constants.KAFKA_CLUSTER_NAME, 1).build(),
                KafkaNodePoolTemplates.brokerPool(kafkaNs2, "broker-np",
                    Constants.KAFKA_CLUSTER_NAME, 1).build());
            krm.createOrUpdateResourceWithWait(
                KafkaTemplates.kafka(kafkaNs2, Constants.KAFKA_CLUSTER_NAME, 1).build());
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

    // ---- Disambiguation ----

    @Test
    @DisplayName("get_kafka_cluster without namespace returns error for ambiguous name")
    @Story("Disambiguation")
    void testGetClusterAmbiguousName() {
        Map<String, Object> args = Map.of("clusterName", Constants.KAFKA_CLUSTER_NAME);
        mcpClient.when()
            .toolsCall("get_kafka_cluster", args, response -> {
                assertTrue(response.isError(),
                    "Should return error when same-named cluster exists in multiple namespaces");

                String text = response.content().getFirst().asText().text();
                LOGGER.info("Disambiguation error: {}", text);
                assertTrue(text.toLowerCase(Locale.ROOT).contains("multiple"),
                    "Error should mention 'multiple' clusters");
                assertTrue(text.contains(Constants.KAFKA_NAMESPACE),
                    "Error should list namespace 1: " + Constants.KAFKA_NAMESPACE);
                assertTrue(text.contains(Constants.KAFKA_NAMESPACE_2),
                    "Error should list namespace 2: " + Constants.KAFKA_NAMESPACE_2);
            })
            .thenAssertResults();
    }

    @Test
    @DisplayName("get_kafka_cluster with namespace resolves to namespace 1")
    @Story("Disambiguation")
    void testGetClusterResolvedNamespace1() {
        Map<String, Object> args = Map.of(
            "clusterName", Constants.KAFKA_CLUSTER_NAME,
            "namespace", Constants.KAFKA_NAMESPACE);
        mcpClient.when()
            .toolsCall("get_kafka_cluster", args, response -> {
                JsonNode cluster = assertToolSuccess(response);
                assertEquals(Constants.KAFKA_CLUSTER_NAME, cluster.path("name").asText());
                assertEquals(Constants.KAFKA_NAMESPACE, cluster.path("namespace").asText(),
                    "Should resolve to namespace 1");
                assertEquals("Ready", cluster.path("readiness").asText());
                LOGGER.info("Resolved to ns-1: {}/{}", cluster.path("namespace").asText(),
                    cluster.path("name").asText());
            })
            .thenAssertResults();
    }

    @Test
    @DisplayName("get_kafka_cluster with namespace resolves to namespace 2")
    @Story("Disambiguation")
    void testGetClusterResolvedNamespace2() {
        Map<String, Object> args = Map.of(
            "clusterName", Constants.KAFKA_CLUSTER_NAME,
            "namespace", Constants.KAFKA_NAMESPACE_2);
        mcpClient.when()
            .toolsCall("get_kafka_cluster", args, response -> {
                JsonNode cluster = assertToolSuccess(response);
                assertEquals(Constants.KAFKA_CLUSTER_NAME, cluster.path("name").asText());
                assertEquals(Constants.KAFKA_NAMESPACE_2, cluster.path("namespace").asText(),
                    "Should resolve to namespace 2");
                assertEquals("Ready", cluster.path("readiness").asText());
                LOGGER.info("Resolved to ns-2: {}/{}", cluster.path("namespace").asText(),
                    cluster.path("name").asText());
            })
            .thenAssertResults();
    }

    // ---- Fleet Overview ----

    @Test
    @DisplayName("get_kafka_fleet_overview counts clusters from both namespaces")
    @Story("Fleet Overview")
    void testFleetOverviewCountsBoth() {
        Map<String, Object> args = Map.of();
        mcpClient.when()
            .toolsCall("get_kafka_fleet_overview", args, response -> {
                JsonNode root = assertToolSuccess(response);
                LOGGER.info("Fleet overview (all): total_clusters={}",
                    root.path("total_clusters").asInt());

                assertTrue(root.path("total_clusters").asInt() >= 2,
                    "Should count at least 2 clusters");
            })
            .thenAssertResults();
    }

    @Test
    @DisplayName("get_kafka_fleet_overview per namespace returns only that namespace's cluster")
    @Story("Fleet Overview")
    void testFleetOverviewPerNamespace() {
        mcpClient.when()
            .toolsCall("get_kafka_fleet_overview",
                Map.of("namespace", Constants.KAFKA_NAMESPACE), response -> {
                    JsonNode root = assertToolSuccess(response);
                    assertEquals(1, root.path("total_clusters").asInt(),
                        "Namespace 1 should have exactly 1 cluster");

                    JsonNode clusters = root.path("clusters");
                    assertNotNull(findByName(clusters, Constants.KAFKA_CLUSTER_NAME),
                        "Should find cluster in namespace 1");
                })
            .thenAssertResults();

        mcpClient.when()
            .toolsCall("get_kafka_fleet_overview",
                Map.of("namespace", Constants.KAFKA_NAMESPACE_2), response -> {
                    JsonNode root = assertToolSuccess(response);
                    assertEquals(1, root.path("total_clusters").asInt(),
                        "Namespace 2 should have exactly 1 cluster");

                    JsonNode clusters = root.path("clusters");
                    assertNotNull(findByName(clusters, Constants.KAFKA_CLUSTER_NAME),
                        "Should find cluster in namespace 2");
                })
            .thenAssertResults();
    }

    // ---- List per Namespace ----

    @Test
    @DisplayName("list_kafka_clusters per namespace returns only that namespace's cluster")
    @Story("List Clusters")
    void testListClustersPerNamespace() {
        mcpClient.when()
            .toolsCall("list_kafka_clusters",
                Map.of("namespace", Constants.KAFKA_NAMESPACE), response -> {
                    JsonNode root = assertToolSuccess(response);
                    JsonNode cluster = findByName(root, Constants.KAFKA_CLUSTER_NAME);
                    assertNotNull(cluster, "Should find cluster in namespace 1");
                    assertEquals(Constants.KAFKA_NAMESPACE, cluster.path("namespace").asText());
                })
            .thenAssertResults();

        mcpClient.when()
            .toolsCall("list_kafka_clusters",
                Map.of("namespace", Constants.KAFKA_NAMESPACE_2), response -> {
                    JsonNode root = assertToolSuccess(response);
                    JsonNode cluster = findByName(root, Constants.KAFKA_CLUSTER_NAME);
                    assertNotNull(cluster, "Should find cluster in namespace 2");
                    assertEquals(Constants.KAFKA_NAMESPACE_2, cluster.path("namespace").asText());
                })
            .thenAssertResults();
    }

    // ---- Cross-Namespace Operations ----

    @Test
    @DisplayName("compare_kafka_clusters across namespaces using namespace parameters")
    @Story("Cross-Namespace Comparison")
    void testCompareAcrossNamespaces() {
        Map<String, Object> args = Map.of(
            "clusterName1", Constants.KAFKA_CLUSTER_NAME,
            "namespace1", Constants.KAFKA_NAMESPACE,
            "clusterName2", Constants.KAFKA_CLUSTER_NAME,
            "namespace2", Constants.KAFKA_NAMESPACE_2);
        mcpClient.when()
            .toolsCall("compare_kafka_clusters", args, response -> {
                JsonNode root = assertToolSuccess(response);
                LOGGER.info("compare_kafka_clusters (cross-ns): length={}",
                    root.toString().length());
            })
            .thenAssertResults();
    }

    @Test
    @DisplayName("get_kafka_bootstrap_servers differ between namespaces")
    @Story("Cross-Namespace Operations")
    void testBootstrapServersDifferAcrossNamespaces() {
        StringBuilder bootstrap1 = new StringBuilder();
        StringBuilder bootstrap2 = new StringBuilder();

        mcpClient.when()
            .toolsCall("get_kafka_bootstrap_servers",
                Map.of("clusterName", Constants.KAFKA_CLUSTER_NAME,
                    "namespace", Constants.KAFKA_NAMESPACE), response -> {
                    JsonNode root = assertToolSuccess(response);
                    bootstrap1.append(root.toString());
                })
            .thenAssertResults();

        mcpClient.when()
            .toolsCall("get_kafka_bootstrap_servers",
                Map.of("clusterName", Constants.KAFKA_CLUSTER_NAME,
                    "namespace", Constants.KAFKA_NAMESPACE_2), response -> {
                    JsonNode root = assertToolSuccess(response);
                    bootstrap2.append(root.toString());
                })
            .thenAssertResults();

        assertNotEquals(bootstrap1.toString(), bootstrap2.toString(),
            "Bootstrap servers should differ between namespaces");
    }

    @Test
    @DisplayName("diagnose_kafka_cluster targets correct cluster when namespace is provided")
    @Story("Cross-Namespace Operations")
    void testDiagnoseWithNamespace() {
        mcpClient.when()
            .toolsCall("diagnose_kafka_cluster",
                Map.of("clusterName", Constants.KAFKA_CLUSTER_NAME,
                    "namespace", Constants.KAFKA_NAMESPACE), response -> {
                    JsonNode root = assertToolSuccess(response);
                    LOGGER.info("Diagnose (ns-1): steps={}",
                        root.path("steps_completed").size());
                    assertTrue(root.path("steps_completed").isArray(),
                        "Should complete diagnostic steps");
                })
            .thenAssertResults();
    }
}
