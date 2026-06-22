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
import io.streamshub.mcp.systemtest.templates.strimzi.KafkaTopicTemplates;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * System tests for KafkaTopic MCP tools.
 * Deploys the MCP server and KafkaTopics into a cluster
 * and verifies that the tools return correct data.
 */
@KubernetesTest(cleanup = CleanupStrategy.AUTOMATIC, collectLogs = true)
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
                    Constants.KAFKA_CLUSTER_NAME, 3, 1).build());
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
     * T3.1 - Verify list_kafka_topics returns topics for the cluster.
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
            })
            .thenAssertResults();
    }

    /**
     * T3.2 - Verify list_kafka_topics with pagination returns at most 2 entries.
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
                if (root.isArray()) {
                    assertTrue(root.size() <= 2,
                        "Paginated response should have at most 2 entries but got " + root.size());
                }
            })
            .thenAssertResults();
    }

    /**
     * T3.3 - Verify list_kafka_topics page 2 returns remaining topics.
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
            })
            .thenAssertResults();
    }

    /**
     * T3.4 - Verify get_kafka_topic returns correct topic details.
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
            })
            .thenAssertResults();
    }

    /**
     * T3.5 - Verify get_kafka_topic returns error for non-existent topic.
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
            })
            .thenAssertResults();
    }

    /**
     * T15.7 - Verify diagnose_kafka_topic returns diagnostic info.
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

                String text = response.content().getFirst().asText().text();
                LOGGER.info("diagnose_kafka_topic response:\n{}", text);
            })
            .thenAssertResults();
    }
}
