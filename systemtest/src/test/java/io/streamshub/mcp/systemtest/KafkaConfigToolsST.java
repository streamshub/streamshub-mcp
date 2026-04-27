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
import io.skodjob.kubetest4j.resources.KubeResourceManager;
import io.streamshub.mcp.systemtest.clients.McpClientFactory;
import io.streamshub.mcp.systemtest.setup.mcp.ConnectivitySetup;
import io.streamshub.mcp.systemtest.setup.mcp.McpServerSetup;
import io.streamshub.mcp.systemtest.setup.strimzi.StrimziSetup;
import io.streamshub.mcp.systemtest.templates.strimzi.KafkaNodePoolTemplates;
import io.streamshub.mcp.systemtest.templates.strimzi.KafkaTemplates;
import io.strimzi.api.kafka.model.kafka.KafkaBuilder;
import io.strimzi.api.kafka.model.kafka.listener.GenericKafkaListenerBuilder;
import io.strimzi.api.kafka.model.kafka.listener.KafkaListenerType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * System tests for Kafka configuration MCP tools.
 * Deploys the MCP server and two Kafka clusters with different configurations,
 * then verifies that configuration retrieval and comparison tools work
 * against real Strimzi resources.
 */
@KubernetesTest(cleanup = CleanupStrategy.AUTOMATIC, collectLogs = true)
@DisplayName("Kafka Config MCP Tools")
@Epic("Strimzi MCP E2E")
@Feature("Kafka Config Tools")
class KafkaConfigToolsST extends AbstractST {

    private static final Logger LOGGER = LoggerFactory.getLogger(KafkaConfigToolsST.class);

    private static final String SECOND_CLUSTER_NAME = "mcp-cluster-2";
    private static final int SECOND_CLUSTER_RETENTION_HOURS = 24;

    @InjectResourceManager
    KubeResourceManager krm;

    @ClassNamespace(name = Constants.MCP_NAMESPACE)
    static Namespace mcpNamespace;

    @ClassNamespace(name = Constants.STRIMZI_NAMESPACE, labels = {"app=strimzi"})
    static Namespace strimziNamespace;

    @ClassNamespace(name = Constants.KAFKA_NAMESPACE, labels = {"app=strimzi"})
    static Namespace kafkaNamespace;

    private static McpAssured.McpStreamableTestClient mcpClient;

    KafkaConfigToolsST() {
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
                    Constants.KAFKA_CLUSTER_NAME, 1).build(),
                KafkaNodePoolTemplates.mixedPool(kafkaNs, "mixed-np",
                    SECOND_CLUSTER_NAME, 1).build());

            krm.createOrUpdateResourceWithWait(
                KafkaTemplates.kafkaMinimal(kafkaNs, Constants.KAFKA_CLUSTER_NAME).build(),
                secondCluster(kafkaNs).build());
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
    @DisplayName("get_kafka_cluster_config returns effective configuration")
    @Story("Get Kafka Cluster Config")
    void testGetKafkaClusterConfig() {
        Map<String, Object> args = Map.of(
            "clusterName", Environment.KAFKA_CLUSTER_NAME,
            "namespace", Environment.KAFKA_NAMESPACE);
        mcpClient.when()
            .toolsCall("get_kafka_cluster_config", args, response -> {
                assertFalse(response.isError(), "get_kafka_cluster_config should not return error");
                String json = response.content().getFirst().asText().text();
                LOGGER.info("get_kafka_cluster_config response (length={}):\n{}", json.length(), json);

                JsonNode config = parseJson(json);

                assertEquals(Environment.KAFKA_CLUSTER_NAME, config.path("name").asText(),
                    "Cluster name should match");
                assertEquals(Environment.KAFKA_NAMESPACE, config.path("namespace").asText(),
                    "Namespace should match");

                // Kafka version
                assertFalse(config.path("kafka_version").isMissingNode(),
                    "Should have kafka_version");
                assertTrue(config.path("kafka_version").asText().matches("\\d+\\.\\d+\\.\\d+"),
                    "Kafka version should be in x.y.z format");

                // Broker config
                assertTrue(config.has("broker_config"), "Should have broker_config");
                JsonNode brokerConfig = config.path("broker_config");
                assertTrue(brokerConfig.has("offsets.topic.replication.factor"),
                    "Broker config should have offsets.topic.replication.factor");
                assertTrue(brokerConfig.has("default.replication.factor"),
                    "Broker config should have default.replication.factor");

                // Listeners
                assertTrue(config.has("listeners"), "Should have listeners");
                JsonNode listeners = config.path("listeners");
                assertTrue(listeners.isArray() && listeners.size() >= 2,
                    "Should have at least 2 listeners (plain + tls)");

                boolean hasPlain = false;
                boolean hasTls = false;
                for (JsonNode listener : listeners) {
                    String name = listener.path("name").asText();
                    if ("plain".equals(name)) {
                        hasPlain = true;
                        assertEquals("internal", listener.path("type").asText(),
                            "Plain listener should be internal");
                        assertFalse(listener.path("tls").asBoolean(),
                            "Plain listener should not have TLS");
                    }
                    if ("tls".equals(name)) {
                        hasTls = true;
                        assertEquals("internal", listener.path("type").asText(),
                            "TLS listener should be internal");
                        assertTrue(listener.path("tls").asBoolean(),
                            "TLS listener should have TLS enabled");
                    }
                }
                assertTrue(hasPlain, "Should have 'plain' listener");
                assertTrue(hasTls, "Should have 'tls' listener");

                // Node pools
                assertTrue(config.has("node_pools"), "Should have node_pools");
                JsonNode nodePools = config.path("node_pools");
                assertTrue(nodePools.isArray() && nodePools.size() >= 2,
                    "Should have at least 2 node pools (controller + broker)");

                boolean hasController = false;
                boolean hasBroker = false;
                for (JsonNode pool : nodePools) {
                    String poolName = pool.path("name").asText();
                    if ("controller-np".equals(poolName)) {
                        hasController = true;
                        assertTrue(pool.path("roles").toString().contains("controller"),
                            "Controller pool should have controller role");
                    }
                    if ("broker-np".equals(poolName)) {
                        hasBroker = true;
                        assertTrue(pool.path("roles").toString().contains("broker"),
                            "Broker pool should have broker role");
                    }
                }
                assertTrue(hasController, "Should have 'controller-np' node pool");
                assertTrue(hasBroker, "Should have 'broker-np' node pool");
            })
            .thenAssertResults();
    }

    @Test
    @DisplayName("get_kafka_cluster_config returns error for non-existent cluster")
    @Story("Get Kafka Cluster Config")
    void testGetKafkaClusterConfigNotFound() {
        Map<String, Object> args = Map.of(
            "clusterName", "non-existent-cluster",
            "namespace", Environment.KAFKA_NAMESPACE);
        mcpClient.when()
            .toolsCall("get_kafka_cluster_config", args, response -> {
                assertTrue(response.isError(),
                    "Should return error for non-existent cluster");

                String text = response.content().getFirst().asText().text();
                LOGGER.info("get_kafka_cluster_config error response: {}", text);
                assertTrue(text.toLowerCase(Locale.ROOT).contains("not found"),
                    "Error should mention 'not found'");
            })
            .thenAssertResults();
    }

    @Test
    @DisplayName("compare_kafka_clusters detects config differences between two clusters")
    @Story("Compare Kafka Clusters")
    void testCompareKafkaClusters() {
        Map<String, Object> args = Map.of(
            "clusterName1", Environment.KAFKA_CLUSTER_NAME,
            "namespace1", Environment.KAFKA_NAMESPACE,
            "clusterName2", SECOND_CLUSTER_NAME,
            "namespace2", Environment.KAFKA_NAMESPACE);
        mcpClient.when()
            .toolsCall("compare_kafka_clusters", args, response -> {
                assertFalse(response.isError(), "compare_kafka_clusters should not return error");
                String json = response.content().getFirst().asText().text();
                LOGGER.info("compare_kafka_clusters response (length={}):\n{}", json.length(), json);

                JsonNode report = parseJson(json);

                // Both cluster configs should be present
                assertTrue(report.has("cluster1_config"), "Should have cluster1_config");
                assertTrue(report.has("cluster2_config"), "Should have cluster2_config");

                JsonNode cluster1 = report.path("cluster1_config");
                JsonNode cluster2 = report.path("cluster2_config");

                assertEquals(Environment.KAFKA_CLUSTER_NAME, cluster1.path("name").asText(),
                    "Cluster 1 name should match");
                assertEquals(SECOND_CLUSTER_NAME, cluster2.path("name").asText(),
                    "Cluster 2 name should match");

                // Verify listener difference: cluster1 has plain+tls, cluster2 has only plain
                JsonNode listeners1 = cluster1.path("listeners");
                JsonNode listeners2 = cluster2.path("listeners");
                assertTrue(listeners1.size() > listeners2.size(),
                    "Cluster 1 should have more listeners than cluster 2");
                assertEquals(1, listeners2.size(),
                    "Cluster 2 should have only one listener (plain)");

                // Verify broker config difference: cluster2 has log.retention.hours
                JsonNode brokerConfig2 = cluster2.path("broker_config");
                assertTrue(brokerConfig2.has("log.retention.hours"),
                    "Cluster 2 should have log.retention.hours in broker config");
                assertEquals(SECOND_CLUSTER_RETENTION_HOURS,
                    brokerConfig2.path("log.retention.hours").asInt(),
                    "log.retention.hours should match configured value");

                // Verify node pool difference: cluster 2 uses a single mixed pool
                JsonNode nodePools1 = cluster1.path("node_pools");
                JsonNode nodePools2 = cluster2.path("node_pools");
                assertTrue(nodePools1.size() > nodePools2.size(),
                    "Cluster 1 should have more node pools than cluster 2");
                assertEquals(1, nodePools2.size(),
                    "Cluster 2 should have exactly one mixed node pool");
                assertEquals("mixed-np", nodePools2.get(0).path("name").asText(),
                    "Cluster 2 node pool should be 'mixed-np'");

                // Steps tracking
                assertTrue(report.has("steps_completed"), "Should have steps_completed");
                JsonNode stepsCompleted = report.path("steps_completed");
                assertTrue(stepsCompleted.isArray() && !stepsCompleted.isEmpty(),
                    "Should have at least one completed step");

                // No failures expected
                JsonNode stepsFailed = report.path("steps_failed");
                assertTrue(stepsFailed.isMissingNode() || stepsFailed.isEmpty(),
                    "Should have no failed steps");

                // Timestamp and message
                assertFalse(report.path("timestamp").isMissingNode(),
                    "Should have timestamp");
                assertFalse(report.path("message").isMissingNode(),
                    "Should have message");
            })
            .thenAssertResults();
    }

    @Test
    @DisplayName("compare_kafka_clusters handles non-existent second cluster")
    @Story("Compare Kafka Clusters")
    void testCompareKafkaClustersNotFound() {
        Map<String, Object> args = Map.of(
            "clusterName1", Environment.KAFKA_CLUSTER_NAME,
            "namespace1", Environment.KAFKA_NAMESPACE,
            "clusterName2", "non-existent-cluster",
            "namespace2", Environment.KAFKA_NAMESPACE);
        mcpClient.when()
            .toolsCall("compare_kafka_clusters", args, response -> {
                String json = response.content().getFirst().asText().text();
                LOGGER.info("compare_kafka_clusters with non-existent cluster response: {}", json);

                JsonNode report = parseJson(json);

                // The comparison tool gathers data with step failure resilience,
                // so it may return a report with failed steps rather than an error
                if (response.isError()) {
                    assertTrue(json.toLowerCase(Locale.ROOT).contains("not found"),
                        "Error should mention 'not found'");
                } else {
                    // cluster2_config should be null/missing since the cluster doesn't exist
                    assertTrue(report.path("cluster2_config").isNull()
                            || report.path("cluster2_config").isMissingNode(),
                        "Cluster 2 config should be null for non-existent cluster");
                    JsonNode stepsFailed = report.path("steps_failed");
                    assertTrue(stepsFailed.isArray() && !stepsFailed.isEmpty(),
                        "Should have at least one failed step for non-existent cluster");
                }
            })
            .thenAssertResults();
    }

    /**
     * Build a second Kafka cluster with intentionally different configuration:
     * only a plain listener (no TLS) and custom broker config.
     *
     * @param namespace the target namespace
     * @return a KafkaBuilder for the second cluster
     */
    private static KafkaBuilder secondCluster(final String namespace) {
        return new KafkaBuilder()
            .withNewMetadata()
                .withName(SECOND_CLUSTER_NAME)
                .withNamespace(namespace)
            .endMetadata()
            .editSpec()
                .editKafka()
                    .withVersion(KafkaTemplates.DEFAULT_KAFKA_VERSION)
                    .withListeners(
                        new GenericKafkaListenerBuilder()
                            .withName("plain")
                            .withPort(9092)
                            .withType(KafkaListenerType.INTERNAL)
                            .withTls(false)
                            .build())
                    .addToConfig("offsets.topic.replication.factor", 1)
                    .addToConfig("transaction.state.log.replication.factor", 1)
                    .addToConfig("transaction.state.log.min.isr", 1)
                    .addToConfig("default.replication.factor", 1)
                    .addToConfig("min.insync.replicas", 1)
                    .addToConfig("log.retention.hours", SECOND_CLUSTER_RETENTION_HOURS)
                .endKafka()
            .endSpec();
    }
}
