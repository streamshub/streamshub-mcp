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
import io.streamshub.mcp.systemtest.templates.strimzi.KafkaConnectTemplates;
import io.streamshub.mcp.systemtest.templates.strimzi.KafkaConnectorTemplates;
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
 * System tests for KafkaConnect and KafkaConnector MCP tools.
 * Deploys the MCP server, a KafkaConnect cluster, and a KafkaConnector
 * into a cluster and verifies that the tools return correct data.
 */
@KubernetesTest(cleanup = CleanupStrategy.AUTOMATIC, collectLogs = true)
@DisplayName("KafkaConnect MCP Tools")
@Epic("Strimzi MCP E2E")
@Feature("KafkaConnect Tools")
class KafkaConnectToolsST extends AbstractST {

    private static final Logger LOGGER = LoggerFactory.getLogger(KafkaConnectToolsST.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String CONNECT_CLUSTER_NAME = "mcp-connect";
    private static final String CONNECTOR_NAME = "mcp-file-sink";

    @InjectResourceManager
    KubeResourceManager krm;

    @ClassNamespace(name = Constants.MCP_NAMESPACE)
    static Namespace mcpNamespace;

    @ClassNamespace(name = Constants.STRIMZI_NAMESPACE, labels = {"app=strimzi"})
    static Namespace strimziNamespace;

    @ClassNamespace(name = Constants.KAFKA_NAMESPACE, labels = {"app=strimzi"})
    static Namespace kafkaNamespace;

    private static McpAssured.McpStreamableTestClient mcpClient;

    KafkaConnectToolsST() {
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
                KafkaTemplates.kafkaMinimal(kafkaNs, Constants.KAFKA_CLUSTER_NAME).build());

            krm.createOrUpdateResourceWithWait(
                KafkaConnectTemplates.kafkaConnect(
                    kafkaNs, CONNECT_CLUSTER_NAME, Constants.KAFKA_CLUSTER_NAME, 1).build());

            krm.createOrUpdateResourceWithWait(
                KafkaConnectorTemplates.fileStreamSink(
                    kafkaNs, CONNECTOR_NAME, CONNECT_CLUSTER_NAME, "test-topic").build());
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
    @DisplayName("list_kafka_connects returns deployed Connect cluster")
    @Story("List KafkaConnects")
    void testListKafkaConnects() {
        Map<String, Object> args = Map.of("namespace", Environment.KAFKA_NAMESPACE);
        mcpClient.when()
            .toolsCall("list_kafka_connects", args, response -> {
                assertFalse(response.isError(), "list_kafka_connects should not return error");
                String json = response.content().getFirst().asText().text();
                LOGGER.info("list_kafka_connects response:\n{}", json);

                JsonNode root = parseJson(json);
                JsonNode connect = findByName(root, CONNECT_CLUSTER_NAME);
                assertNotNull(connect, "Should find Connect cluster '" + CONNECT_CLUSTER_NAME + "'");
                assertEquals("Ready", connect.path("readiness").asText(), "Connect should be Ready");
            })
            .thenAssertResults();
    }

    @Test
    @DisplayName("get_kafka_connect returns detailed Connect info")
    @Story("Get KafkaConnect")
    void testGetKafkaConnect() {
        Map<String, Object> args = Map.of(
            "connectName", CONNECT_CLUSTER_NAME,
            "namespace", Environment.KAFKA_NAMESPACE);
        mcpClient.when()
            .toolsCall("get_kafka_connect", args, response -> {
                assertFalse(response.isError(), "get_kafka_connect should not return error");
                String json = response.content().getFirst().asText().text();
                LOGGER.info("get_kafka_connect response:\n{}", json);

                JsonNode connect = parseJson(json);
                assertEquals(CONNECT_CLUSTER_NAME, connect.path("name").asText());
                assertEquals("Ready", connect.path("readiness").asText());
                assertFalse(connect.path("rest_api_url").isMissingNode(), "Should have REST API URL");
                assertFalse(connect.path("bootstrap_servers").isMissingNode(), "Should have bootstrap servers");
                assertTrue(connect.has("replicas"), "Should have replicas info");
            })
            .thenAssertResults();
    }

    @Test
    @DisplayName("get_kafka_connect_pods returns running pods")
    @Story("Get KafkaConnect Pods")
    void testGetKafkaConnectPods() {
        Map<String, Object> args = Map.of(
            "connectName", CONNECT_CLUSTER_NAME,
            "namespace", Environment.KAFKA_NAMESPACE);
        mcpClient.when()
            .toolsCall("get_kafka_connect_pods", args, response -> {
                assertFalse(response.isError(), "get_kafka_connect_pods should not return error");
                String json = response.content().getFirst().asText().text();
                LOGGER.info("get_kafka_connect_pods response:\n{}", json);

                JsonNode root = parseJson(json);
                assertTrue(root.has("pod_summary"), "Should have pod_summary");
                assertTrue(root.path("pod_summary").path("total_pods").asInt() > 0,
                    "Should have at least one pod");
            })
            .thenAssertResults();
    }

    @Test
    @DisplayName("list_kafka_connectors returns deployed connector")
    @Story("List KafkaConnectors")
    void testListKafkaConnectors() {
        Map<String, Object> args = Map.of("namespace", Environment.KAFKA_NAMESPACE);
        mcpClient.when()
            .toolsCall("list_kafka_connectors", args, response -> {
                assertFalse(response.isError(), "list_kafka_connectors should not return error");
                String json = response.content().getFirst().asText().text();
                LOGGER.info("list_kafka_connectors response:\n{}", json);

                JsonNode root = parseJson(json);
                JsonNode connector = findByName(root, CONNECTOR_NAME);
                assertNotNull(connector, "Should find connector '" + CONNECTOR_NAME + "'");
                assertTrue(connector.path("class_name").asText().contains("FileStreamSinkConnector"),
                    "Should have correct class name");
            })
            .thenAssertResults();
    }

    @Test
    @DisplayName("list_kafka_connectors filtered by connect cluster")
    @Story("List KafkaConnectors filtered")
    void testListKafkaConnectorsFilteredByCluster() {
        Map<String, Object> args = Map.of(
            "namespace", Environment.KAFKA_NAMESPACE,
            "connectCluster", CONNECT_CLUSTER_NAME);
        mcpClient.when()
            .toolsCall("list_kafka_connectors", args, response -> {
                assertFalse(response.isError(), "list_kafka_connectors should not return error");
                String json = response.content().getFirst().asText().text();
                LOGGER.info("list_kafka_connectors (filtered) response:\n{}", json);

                JsonNode root = parseJson(json);
                JsonNode connector = findByName(root, CONNECTOR_NAME);
                assertNotNull(connector, "Should find connector '" + CONNECTOR_NAME + "'");
                assertEquals(CONNECT_CLUSTER_NAME, connector.path("connect_cluster").asText(),
                    "Connector should belong to the filtered Connect cluster");
            })
            .thenAssertResults();
    }

    @Test
    @DisplayName("get_kafka_connector returns detailed connector info")
    @Story("Get KafkaConnector")
    void testGetKafkaConnector() {
        Map<String, Object> args = Map.of(
            "connectorName", CONNECTOR_NAME,
            "namespace", Environment.KAFKA_NAMESPACE);
        mcpClient.when()
            .toolsCall("get_kafka_connector", args, response -> {
                assertFalse(response.isError(), "get_kafka_connector should not return error");
                String json = response.content().getFirst().asText().text();
                LOGGER.info("get_kafka_connector response:\n{}", json);

                JsonNode connector = parseJson(json);
                assertEquals(CONNECTOR_NAME, connector.path("name").asText());
                assertEquals(CONNECT_CLUSTER_NAME, connector.path("connect_cluster").asText());
                assertTrue(connector.path("class_name").asText().contains("FileStreamSinkConnector"));
                assertEquals(1, connector.path("tasks_max").asInt());
                assertEquals("running", connector.path("state").asText());
                assertTrue(connector.has("config"), "Should have config for get operation");
            })
            .thenAssertResults();
    }

    /**
     * Find a node by name in a JSON array or single object.
     *
     * @param root the root JSON node
     * @param name the name to search for
     * @return the matching node, or null
     */
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
