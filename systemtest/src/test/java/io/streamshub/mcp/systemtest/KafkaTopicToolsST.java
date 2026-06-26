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
import io.skodjob.kubetest4j.annotations.KubernetesTest;
import io.skodjob.kubetest4j.resources.KubeResourceManager;
import io.streamshub.mcp.systemtest.clients.McpClientFactory;
import io.streamshub.mcp.systemtest.setup.mcp.ConnectivitySetup;
import io.streamshub.mcp.systemtest.setup.mcp.McpServerSetup;
import io.streamshub.mcp.systemtest.setup.strimzi.StrimziSetup;
import io.streamshub.mcp.systemtest.templates.strimzi.KafkaNodePoolTemplates;
import io.streamshub.mcp.systemtest.templates.strimzi.KafkaTemplates;
import io.streamshub.mcp.systemtest.templates.strimzi.KafkaTopicTemplates;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * System tests for KafkaTopic MCP tools.
 * Deploys the MCP server and KafkaTopics into a cluster
 * and verifies that the tools return correct data.
 */
@KubernetesTest
@DisplayName("KafkaTopic MCP Tools")
@Epic("Strimzi MCP E2E")
@Feature("KafkaTopic Tools")
class KafkaTopicToolsST extends AbstractST {

    private static final Logger LOGGER = LoggerFactory.getLogger(KafkaTopicToolsST.class);

    @InjectResourceManager
    KubeResourceManager krm;

    @ClassNamespace(name = Constants.MCP_NAMESPACE)
    static Namespace mcpNamespace;

    @ClassNamespace(name = Constants.STRIMZI_NAMESPACE, labels = {"app=strimzi"})
    static Namespace strimziNamespace;

    @ClassNamespace(name = Constants.KAFKA_NAMESPACE, labels = {"app=strimzi"})
    static Namespace kafkaNamespace;

    private static McpAssured.McpStreamableTestClient mcpClient;

    private final Set<String> page1TopicNames = new HashSet<>();

    KafkaTopicToolsST() {
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
                KafkaTopicTemplates.topic(kafkaNs, "mcp-topic-alpha",
                    Constants.KAFKA_CLUSTER_NAME, 3, 1).build(),
                KafkaTopicTemplates.topic(kafkaNs, "mcp-topic-beta",
                    Constants.KAFKA_CLUSTER_NAME, 3, 1).build(),
                KafkaTopicTemplates.topic(kafkaNs, "mcp-topic-gamma",
                    Constants.KAFKA_CLUSTER_NAME, 3, 1).build(),
                KafkaTopicTemplates.topic(kafkaNs, "mcp-topic-single",
                    Constants.KAFKA_CLUSTER_NAME, 1, 1).build());
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
     * Verify list_kafka_topics returns topics for the cluster.
     */
    @Test
    @DisplayName("list_kafka_topics returns deployed topics")
    @Story("List Kafka Topics")
    void testListKafkaTopics() {
        Map<String, Object> args = Map.of(
            "clusterName", Constants.KAFKA_CLUSTER_NAME,
            "namespace", kafkaNamespace.getMetadata().getName());
        mcpClient.when()
            .toolsCall("list_kafka_topics", args, response -> {
                assertFalse(response.isError(), "list_kafka_topics should not return error");
                assertFalse(response.content().isEmpty(), "Should return at least one content entry");

                String json = response.content().getFirst().asText().text();
                LOGGER.info("list_kafka_topics response:\n{}", json);

                JsonNode root = parseJson(json);
                assertTrue(root.has("items"), "Response should have 'items' field");
                JsonNode items = root.path("items");
                assertTrue(items.isArray(), "items should be a JSON array");
                assertTrue(items.size() >= 3,
                    "Should return at least 3 topics (alpha, beta, gamma) but got " + items.size());

                Set<String> topicNames = new HashSet<>();
                for (JsonNode topic : items) {
                    assertTrue(topic.has("name"), "Each topic should have a 'name' field");
                    assertTrue(topic.has("partitions"), "Each topic should have a 'partitions' field");
                    assertTrue(topic.has("status"), "Each topic should have a 'status' field");
                    topicNames.add(topic.path("name").asText());
                }
                assertTrue(topicNames.contains("mcp-topic-alpha"),
                    "Response should contain mcp-topic-alpha");
                assertTrue(topicNames.contains("mcp-topic-beta"),
                    "Response should contain mcp-topic-beta");
                assertTrue(topicNames.contains("mcp-topic-gamma"),
                    "Response should contain mcp-topic-gamma");
            })
            .thenAssertResults();
    }

    /**
     * Verify list_kafka_topics with pagination returns at most 2 entries.
     */
    @Test
    @DisplayName("list_kafka_topics with pagination returns at most 2 entries")
    @Story("List Kafka Topics")
    void testListKafkaTopicsPaginated() {
        Map<String, Object> args = Map.of(
            "clusterName", Constants.KAFKA_CLUSTER_NAME,
            "namespace", kafkaNamespace.getMetadata().getName(),
            "limit", 2,
            "offset", 0);
        mcpClient.when()
            .toolsCall("list_kafka_topics", args, response -> {
                assertFalse(response.isError(), "list_kafka_topics paginated should not return error");

                String json = response.content().getFirst().asText().text();
                LOGGER.info("list_kafka_topics paginated response:\n{}", json);

                JsonNode root = parseJson(json);
                assertTrue(root.has("items"), "Paginated response should have 'items' field");
                JsonNode items = root.path("items");
                assertTrue(items.isArray(), "items should be a JSON array");
                assertTrue(items.size() <= 2,
                    "Paginated response should have at most 2 entries but got " + items.size());
                assertTrue(items.size() > 0,
                    "Paginated response should have at least 1 entry");
                for (JsonNode topic : items) {
                    assertFalse(topic.path("name").isMissingNode(),
                        "Each paginated entry should have a 'name' field");
                }
                page1TopicNames.clear();
                for (JsonNode topic : items) {
                    page1TopicNames.add(topic.path("name").asText());
                }
            })
            .thenAssertResults();
    }

    /**
     * Verify list_kafka_topics page 2 returns remaining topics.
     */
    @Test
    @DisplayName("list_kafka_topics page 2 returns remaining topics")
    @Story("List Kafka Topics")
    void testListKafkaTopicsPage2() {
        Map<String, Object> args = Map.of(
            "clusterName", Constants.KAFKA_CLUSTER_NAME,
            "namespace", kafkaNamespace.getMetadata().getName(),
            "limit", 2,
            "offset", 2);
        mcpClient.when()
            .toolsCall("list_kafka_topics", args, response -> {
                assertFalse(response.isError(), "list_kafka_topics page 2 should not return error");

                String json = response.content().getFirst().asText().text();
                LOGGER.info("list_kafka_topics page 2 response:\n{}", json);

                JsonNode root = parseJson(json);
                assertTrue(root.has("items"), "Page 2 response should have 'items' field");
                JsonNode items = root.path("items");
                assertTrue(items.isArray(), "items should be a JSON array");
                assertTrue(items.size() >= 1,
                    "Page 2 should have at least 1 remaining topic");

                for (JsonNode topic : items) {
                    String name = topic.path("name").asText();
                    assertFalse(page1TopicNames.contains(name),
                        "Page 2 topic '" + name + "' should not overlap with page 1 topics");
                }
            })
            .thenAssertResults();
    }

    /**
     * Verify get_kafka_topic returns correct topic details.
     */
    @Test
    @DisplayName("get_kafka_topic returns topic details")
    @Story("Get Kafka Topic")
    void testGetKafkaTopic() {
        Map<String, Object> args = Map.of(
            "clusterName", Constants.KAFKA_CLUSTER_NAME,
            "topicName", "mcp-topic-alpha",
            "namespace", kafkaNamespace.getMetadata().getName());
        mcpClient.when()
            .toolsCall("get_kafka_topic", args, response -> {
                assertFalse(response.isError(), "get_kafka_topic should not return error");

                String json = response.content().getFirst().asText().text();
                LOGGER.info("get_kafka_topic response:\n{}", json);

                JsonNode topic = parseJson(json);
                assertEquals("mcp-topic-alpha", topic.path("name").asText(),
                    "Topic name should match");
                assertEquals(3, topic.path("partitions").asInt(),
                    "Topic should have 3 partitions");
                assertEquals(1, topic.path("replicas").asInt(),
                    "Topic should have 1 replica");
                assertEquals(Constants.KAFKA_CLUSTER_NAME, topic.path("cluster").asText(),
                    "Topic cluster should match");
                assertFalse(topic.path("status").isMissingNode(),
                    "Topic should have a status field");
                assertEquals("Ready", topic.path("status").asText(),
                    "Topic status should be Ready");
            })
            .thenAssertResults();
    }

    /**
     * Verify get_kafka_topic returns error for non-existent topic.
     */
    @Test
    @DisplayName("get_kafka_topic returns error for non-existent topic")
    @Story("Get Kafka Topic")
    void testGetKafkaTopicNotFound() {
        Map<String, Object> args = Map.of(
            "clusterName", Constants.KAFKA_CLUSTER_NAME,
            "topicName", "nonexistent-topic-xyz",
            "namespace", kafkaNamespace.getMetadata().getName());
        mcpClient.when()
            .toolsCall("get_kafka_topic", args, response -> {
                assertTrue(response.isError(),
                    "Should return error for non-existent topic");

                String text = response.content().getFirst().asText().text();
                LOGGER.info("get_kafka_topic error response: {}", text);
                assertTrue(text.contains("not found"),
                    "Error message should mention 'not found'");
            })
            .thenAssertResults();
    }

    /**
     * Verify diagnose_kafka_topic returns diagnostic info.
     */
    @Test
    @DisplayName("diagnose_kafka_topic returns diagnostic info")
    @Story("Diagnose Kafka Topic")
    void testDiagnoseKafkaTopic() {
        Map<String, Object> args = Map.of(
            "topicName", "mcp-topic-alpha",
            "namespace", kafkaNamespace.getMetadata().getName());
        mcpClient.when()
            .toolsCall("diagnose_kafka_topic", args, response -> {
                assertFalse(response.isError(), "diagnose_kafka_topic should not return error");
                assertFalse(response.content().isEmpty(),
                    "diagnose_kafka_topic should return content");

                String text = response.content().getFirst().asText().text();
                LOGGER.info("diagnose_kafka_topic response:\n{}", text);
                assertTrue(text.contains("mcp-topic-alpha"),
                    "Diagnostic response should reference the topic name");
            })
            .thenAssertResults();
    }

    /**
     * Verify get_kafka_topic returns correct values for a topic with different config.
     */
    @Test
    @DisplayName("get_kafka_topic returns correct values for single-partition topic")
    @Story("Get Kafka Topic")
    void testGetKafkaTopicSinglePartition() {
        Map<String, Object> args = Map.of(
            "clusterName", Constants.KAFKA_CLUSTER_NAME,
            "topicName", "mcp-topic-single",
            "namespace", kafkaNamespace.getMetadata().getName());
        mcpClient.when()
            .toolsCall("get_kafka_topic", args, response -> {
                assertFalse(response.isError(), "get_kafka_topic should not return error");

                String json = response.content().getFirst().asText().text();
                LOGGER.info("get_kafka_topic single-partition response:\n{}", json);

                JsonNode topic = parseJson(json);
                assertEquals("mcp-topic-single", topic.path("name").asText(),
                    "Topic name should match");
                assertEquals(1, topic.path("partitions").asInt(),
                    "Topic should have 1 partition");
                assertEquals(1, topic.path("replicas").asInt(),
                    "Topic should have 1 replica");
            })
            .thenAssertResults();
    }

    /**
     * Verify list_kafka_topics returns empty for a non-existent cluster.
     */
    @Test
    @DisplayName("list_kafka_topics returns empty for non-existent cluster")
    @Story("List Kafka Topics")
    void testListKafkaTopicsNonExistentCluster() {
        Map<String, Object> args = Map.of(
            "clusterName", "nonexistent-cluster",
            "namespace", kafkaNamespace.getMetadata().getName());
        mcpClient.when()
            .toolsCall("list_kafka_topics", args, response -> {
                assertFalse(response.isError(),
                    "list_kafka_topics should not return error for non-existent cluster");
                assertFalse(response.content().isEmpty(),
                    "Should return a paginated response");

                String json = response.content().getFirst().asText().text();
                LOGGER.info("list_kafka_topics for non-existent cluster response:\n{}", json);

                JsonNode root = parseJson(json);
                assertTrue(root.has("items"), "Response should have 'items' field");
                JsonNode items = root.path("items");
                assertTrue(items.isArray(), "items should be a JSON array");
                assertEquals(0, items.size(),
                    "Items should be empty for non-existent cluster");
                assertEquals(0, root.path("total").asInt(),
                    "Total should be 0 for non-existent cluster");
            })
            .thenAssertResults();
    }

    /**
     * Verify get_kafka_topic returns error for wrong namespace.
     */
    @Test
    @DisplayName("get_kafka_topic returns error for wrong namespace")
    @Story("Get Kafka Topic")
    void testGetKafkaTopicWrongNamespace() {
        Map<String, Object> args = Map.of(
            "clusterName", Constants.KAFKA_CLUSTER_NAME,
            "topicName", "mcp-topic-alpha",
            "namespace", "nonexistent-namespace");
        mcpClient.when()
            .toolsCall("get_kafka_topic", args, response -> {
                assertTrue(response.isError(),
                    "Should return error for wrong namespace");

                String text = response.content().getFirst().asText().text();
                LOGGER.info("get_kafka_topic wrong namespace error: {}", text);
                assertTrue(text.contains("not found"),
                    "Error message should mention 'not found'");
            })
            .thenAssertResults();
    }

    /**
     * Verify diagnose_kafka_topic with optional clusterName parameter.
     */
    @Test
    @DisplayName("diagnose_kafka_topic with clusterName parameter")
    @Story("Diagnose Kafka Topic")
    void testDiagnoseKafkqaTopicWithClusterName() {
        Map<String, Object> args = Map.of(
            "topicName", "mcp-topic-alpha",
            "clusterName", Constants.KAFKA_CLUSTER_NAME,
            "namespace", kafkaNamespace.getMetadata().getName());
        mcpClient.when()
            .toolsCall("diagnose_kafka_topic", args, response -> {
                assertFalse(response.isError(),
                    "diagnose_kafka_topic with clusterName should not return error");
                assertFalse(response.content().isEmpty(),
                    "diagnose_kafka_topic should return content");

                String text = response.content().getFirst().asText().text();
                LOGGER.info("diagnose_kafka_topic with clusterName response:\n{}", text);
                assertTrue(text.contains("mcp-topic-alpha"),
                    "Diagnostic response should reference the topic name");
            })
            .thenAssertResults();
    }
}
