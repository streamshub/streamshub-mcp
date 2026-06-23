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
import io.streamshub.mcp.systemtest.templates.strimzi.KafkaBridgeTemplates;
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
 * System tests for KafkaBridge MCP tools.
 * Deploys the MCP server and a KafkaBridge into a cluster
 * and verifies that the tools return correct data.
 */
@KubernetesTest(cleanup = CleanupStrategy.AUTOMATIC, collectLogs = true)
@DisplayName("KafkaBridge MCP Tools")
@Epic("Strimzi MCP E2E")
@Feature("KafkaBridge Tools")
class KafkaBridgeToolsST extends AbstractST {

    private static final Logger LOGGER = LoggerFactory.getLogger(KafkaBridgeToolsST.class);
    private static final String BRIDGE_NAME = "mcp-bridge";

    @InjectResourceManager
    KubeResourceManager krm;

    @ClassNamespace(name = Constants.MCP_NAMESPACE)
    static Namespace mcpNamespace;

    @ClassNamespace(name = Constants.STRIMZI_NAMESPACE, labels = {"app=strimzi"})
    static Namespace strimziNamespace;

    @ClassNamespace(name = Constants.KAFKA_NAMESPACE, labels = {"app=strimzi"})
    static Namespace kafkaNamespace;

    private static McpAssured.McpStreamableTestClient mcpClient;

    KafkaBridgeToolsST() {
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
                KafkaBridgeTemplates.kafkaBridge(
                    kafkaNs, BRIDGE_NAME, Constants.KAFKA_CLUSTER_NAME, 1).build());
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
     * Verify list_kafka_bridges returns the deployed bridge.
     */
    @Test
    @DisplayName("list_kafka_bridges returns deployed bridge")
    @Story("List KafkaBridges")
    void testListKafkaBridges() {
        Map<String, Object> args = Map.of("namespace", Environment.KAFKA_NAMESPACE);
        mcpClient.when()
            .toolsCall("list_kafka_bridges", args, response -> {
                assertFalse(response.isError(), "list_kafka_bridges should not return error");
                String json = response.content().getFirst().asText().text();
                LOGGER.info("list_kafka_bridges response:\n{}", json);

                JsonNode root = parseJson(json);
                JsonNode bridge = findByName(root, BRIDGE_NAME);
                assertNotNull(bridge, "Should find KafkaBridge '" + BRIDGE_NAME + "'");
                assertEquals("Ready", bridge.path("readiness").asText(), "Bridge should be Ready");
            })
            .thenAssertResults();
    }

    /**
     * Verify get_kafka_bridge returns detailed bridge info.
     */
    @Test
    @DisplayName("get_kafka_bridge returns detailed bridge info")
    @Story("Get KafkaBridge")
    void testGetKafkaBridge() {
        Map<String, Object> args = Map.of(
            "bridgeName", BRIDGE_NAME,
            "namespace", Environment.KAFKA_NAMESPACE);
        mcpClient.when()
            .toolsCall("get_kafka_bridge", args, response -> {
                assertFalse(response.isError(), "get_kafka_bridge should not return error");
                String json = response.content().getFirst().asText().text();
                LOGGER.info("get_kafka_bridge response:\n{}", json);

                JsonNode bridge = parseJson(json);
                assertEquals(BRIDGE_NAME, bridge.path("name").asText());
                assertEquals("Ready", bridge.path("readiness").asText());
                assertFalse(bridge.path("bootstrap_servers").isMissingNode(), "Should have bootstrap servers");
                assertTrue(bridge.has("replicas"), "Should have replicas info");
                assertTrue(bridge.has("http_port"), "Should have HTTP port");
            })
            .thenAssertResults();
    }

    /**
     * Verify get_kafka_bridge_pods returns running pods.
     */
    @Test
    @DisplayName("get_kafka_bridge_pods returns running pods")
    @Story("Get KafkaBridge Pods")
    void testGetKafkaBridgePods() {
        Map<String, Object> args = Map.of(
            "bridgeName", BRIDGE_NAME,
            "namespace", Environment.KAFKA_NAMESPACE);
        mcpClient.when()
            .toolsCall("get_kafka_bridge_pods", args, response -> {
                assertFalse(response.isError(), "get_kafka_bridge_pods should not return error");
                String json = response.content().getFirst().asText().text();
                LOGGER.info("get_kafka_bridge_pods response:\n{}", json);

                JsonNode root = parseJson(json);
                assertTrue(root.has("pod_summary"), "Should have pod_summary");
                assertTrue(root.path("pod_summary").path("total_pods").asInt() > 0,
                    "Should have at least one pod");
            })
            .thenAssertResults();
    }

    @Test
    @DisplayName("get_kafka_bridge_logs returns log output")
    @Story("Get KafkaBridge Logs")
    void testGetKafkaBridgeLogs() {
        Map<String, Object> args = Map.of(
            "bridgeName", BRIDGE_NAME,
            "tailLines", 50);
        mcpClient.when()
            .toolsCall("get_kafka_bridge_logs", args, response -> {
                assertFalse(response.isError(), "get_kafka_bridge_logs should not return error");
                String text = response.content().getFirst().asText().text();
                LOGGER.info("get_kafka_bridge_logs response (length={})", text.length());

                JsonNode root = parseJson(text);
                assertEquals(BRIDGE_NAME, root.path("bridge_name").asText(),
                    "bridge_name should match");
                JsonNode pods = root.path("pods");
                assertTrue(pods.isArray() && !pods.isEmpty(),
                    "pods should be a non-empty array");
                assertEquals(1, pods.size(),
                    "Should have exactly 1 bridge pod (1 replica)");
                assertTrue(root.path("log_lines").isNumber(), "log_lines should be a number");
                assertFalse(root.path("timestamp").isMissingNode(), "Should have timestamp");
                assertFalse(root.path("message").isMissingNode(), "Should have message");
            })
            .thenAssertResults();
    }

    @Test
    @DisplayName("get_strimzi_events returns events for KafkaBridge")
    @Story("Get Strimzi Events KafkaBridge")
    void testGetStrimziEventsKafkaBridge() {
        Map<String, Object> args = Map.of(
            "resourceName", BRIDGE_NAME,
            "resourceKind", "KafkaBridge",
            "namespace", kafkaNamespace.getMetadata().getName());
        mcpClient.when()
            .toolsCall("get_strimzi_events", args, response -> {
                assertFalse(response.isError(), "get_strimzi_events should not return error");
                String text = response.content().getFirst().asText().text();
                LOGGER.info("get_strimzi_events (KafkaBridge) response (length={})", text.length());

                JsonNode root = parseJson(text);
                assertEquals(BRIDGE_NAME, root.path("resource_name").asText(),
                    "resource_name should match");
                assertEquals(kafkaNamespace.getMetadata().getName(),
                    root.path("namespace").asText(), "namespace should match deployment namespace");
                assertTrue(root.path("total_events").asInt() > 0,
                    "Should have events from bridge deployment");
                assertFalse(root.path("timestamp").isMissingNode(), "Should have timestamp");
                assertFalse(root.path("message").isMissingNode(), "Should have message");
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
}
