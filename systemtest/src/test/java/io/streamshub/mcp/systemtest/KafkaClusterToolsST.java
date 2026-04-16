/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.systemtest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.kubernetes.api.model.Namespace;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import io.quarkiverse.mcp.server.test.McpAssured;
import io.skodjob.kubetest4j.annotations.ClassNamespace;
import io.skodjob.kubetest4j.annotations.CleanupStrategy;
import io.skodjob.kubetest4j.annotations.InjectResourceManager;
import io.skodjob.kubetest4j.annotations.KubernetesTest;
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
@KubernetesTest(cleanup = CleanupStrategy.AUTOMATIC, collectLogs = true)
@DisplayName("Kafka Cluster MCP Tools")
@Epic("Strimzi MCP E2E")
@Feature("Kafka Cluster Tools")
class KafkaClusterToolsST extends AbstractST {

    private static final Logger LOGGER = LoggerFactory.getLogger(KafkaClusterToolsST.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @InjectResourceManager
    static KubeResourceManager krm;

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
    static void setup() {
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

    /**
     * Parse a JSON string into a Jackson tree node.
     *
     * @param json the JSON string
     * @return parsed JsonNode
     */
    private static JsonNode parseJson(final String json) {
        try {
            return MAPPER.readTree(json);
        } catch (JsonProcessingException e) {
            throw new AssertionError("Failed to parse JSON response: " + json, e);
        }
    }
}
