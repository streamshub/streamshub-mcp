/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.systemtest.tools;

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
import io.streamshub.mcp.systemtest.templates.strimzi.KafkaBridgeTemplates;
import io.streamshub.mcp.systemtest.templates.strimzi.KafkaNodePoolTemplates;
import io.streamshub.mcp.systemtest.templates.strimzi.KafkaTemplates;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

import static io.streamshub.mcp.systemtest.TestTags.LOGS;
import static io.streamshub.mcp.systemtest.TestTags.REGRESSION;
import static io.streamshub.mcp.systemtest.TestTags.TOOLS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * System tests for KafkaBridge MCP tools.
 * Deploys the MCP server and a KafkaBridge into a cluster
 * and verifies that the tools return correct data.
 */
@Epic("Strimzi MCP E2E")
@Feature("KafkaBridge Tools")
@Tag(REGRESSION)
@Tag(TOOLS)
class KafkaBridgeToolsST extends AbstractST {

    private static final Logger LOGGER = LoggerFactory.getLogger(KafkaBridgeToolsST.class);

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

            KafkaBridgeTemplates.deployMetricsConfigMap(kafkaNs);
            KafkaBridgeTemplates.deployPodMonitors(kafkaNs);

            krm.createOrUpdateResourceWithoutWait(
                KafkaNodePoolTemplates.controllerPool(kafkaNs, "controller-np",
                    Constants.KAFKA_CLUSTER_NAME, 1).build(),
                KafkaNodePoolTemplates.brokerPool(kafkaNs, "broker-np",
                    Constants.KAFKA_CLUSTER_NAME, 1).build());
            
            krm.createOrUpdateResourceWithWait(
                KafkaTemplates.kafka(kafkaNs, Constants.KAFKA_CLUSTER_NAME, 1).build());
            
            krm.createOrUpdateResourceWithWait(
                KafkaBridgeTemplates.kafkaBridge(
                    kafkaNs, Constants.BRIDGE_NAME, Constants.KAFKA_CLUSTER_NAME, 1).build());
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
    @Story("list_kafka_bridges returns deployed bridge")
    void testListKafkaBridges() {
        Map<String, Object> args = Map.of("namespace", Environment.KAFKA_NAMESPACE);

        mcpClient.when()
            .toolsCall("list_kafka_bridges", args, response -> {
                JsonNode root = assertToolSuccess(response);

                String json = response.content().getFirst().asText().text();
                LOGGER.info("list_kafka_bridges response (length={})", json.length());
                LOGGER.debug("list_kafka_bridges response:\n{}", json);
                JsonNode bridge = findByName(root, Constants.BRIDGE_NAME);
                assertNotNull(bridge, "Should find KafkaBridge '" + Constants.BRIDGE_NAME + "'");
                assertEquals("Ready", bridge.path("readiness").asText(), "Bridge should be Ready");
                assertEquals(1, bridge.path("replicas").path("expected").asInt(), "Expected replicas should be 1");
                assertEquals(1, bridge.path("replicas").path("ready").asInt(), "Ready replicas should be 1");
                assertFalse(bridge.path("bootstrap_servers").isMissingNode(), "Should have bootstrap_servers");
                JsonNode conditions = bridge.path("conditions");
                assertTrue(conditions.isArray() && !conditions.isEmpty(), "Should have conditions");
                assertEquals("Ready", conditions.get(0).path("type").asText(), "First condition type should be Ready");
            })
            .thenAssertResults();
    }

    /**
     * Verify get_kafka_bridge returns detailed bridge info.
     */
    @Test
    @Story("get_kafka_bridge returns detailed bridge info")
    void testGetKafkaBridge() {
        Map<String, Object> args = Map.of(
            "bridgeName", Constants.BRIDGE_NAME,
            "namespace", Environment.KAFKA_NAMESPACE);

        mcpClient.when()
            .toolsCall("get_kafka_bridge", args, response -> {
                JsonNode bridge = assertToolSuccess(response);

                String json = response.content().getFirst().asText().text();
                LOGGER.info("get_kafka_bridge response (length={})", json.length());
                LOGGER.debug("get_kafka_bridge response:\n{}", json);
                assertEquals(Constants.BRIDGE_NAME, bridge.path("name").asText(),
                    "Bridge name should match");
                assertEquals("Ready", bridge.path("readiness").asText(),
                    "Bridge should be Ready");
                assertFalse(bridge.path("bootstrap_servers").isMissingNode(), "Should have bootstrap servers");
                assertTrue(bridge.has("replicas"), "Should have replicas info");
                assertTrue(bridge.has("http_port"), "Should have HTTP port");
                assertEquals(8080, bridge.path("http_port").asInt(), "HTTP port should be 8080");
                assertTrue(bridge.path("tls_enabled").asBoolean(), "TLS should be enabled");
                assertEquals(1, bridge.path("replicas").path("expected").asInt(), "Expected replicas should be 1");
                assertEquals(1, bridge.path("replicas").path("ready").asInt(), "Ready replicas should be 1");
                assertFalse(bridge.path("creation_time").isMissingNode(), "Should have creation_time");
            })
            .thenAssertResults();
    }

    /**
     * Verify get_kafka_bridge_pods returns running pods.
     */
    @Test
    @Story("get_kafka_bridge_pods returns running pods")
    void testGetKafkaBridgePods() {
        Map<String, Object> args = Map.of(
            "bridgeName", Constants.BRIDGE_NAME,
            "namespace", Environment.KAFKA_NAMESPACE);

        mcpClient.when()
            .toolsCall("get_kafka_bridge_pods", args, response -> {
                JsonNode root = assertToolSuccess(response);

                String json = response.content().getFirst().asText().text();
                LOGGER.info("get_kafka_bridge_pods response (length={})", json.length());
                LOGGER.debug("get_kafka_bridge_pods response:\n{}", json);
                assertTrue(root.has("pod_summary"), "Should have pod_summary");
                assertTrue(root.path("pod_summary").path("total_pods").asInt() > 0,
                    "Should have at least one pod");
                assertEquals(Constants.BRIDGE_NAME, root.path("bridge_name").asText(), "bridge_name should match");
                assertEquals(1, root.path("pod_summary").path("ready_pods").asInt(), "Should have 1 ready pod");
                assertEquals(0, root.path("pod_summary").path("failed_pods").asInt(), "Should have 0 failed pods");
                assertEquals("HEALTHY", root.path("pod_summary").path("health_status").asText(), "Health status should be HEALTHY");
            })
            .thenAssertResults();
    }

    @Test
    @Story("get_kafka_bridge_logs returns log output")
    @Tag(LOGS)
    void testGetKafkaBridgeLogs() {
        Map<String, Object> args = Map.of(
            "bridgeName", Constants.BRIDGE_NAME,
            "tailLines", 50);

        mcpClient.when()
            .toolsCall("get_kafka_bridge_logs", args, response -> {
                JsonNode root = assertToolSuccess(response);

                String text = response.content().getFirst().asText().text();
                LOGGER.info("get_kafka_bridge_logs response (length={})", text.length());
                LOGGER.debug("get_kafka_bridge_logs response:\n{}", text);
                assertEquals(Constants.BRIDGE_NAME, root.path("bridge_name").asText(),
                    "bridge_name should match");
                JsonNode pods = root.path("pods");
                assertTrue(pods.isArray() && !pods.isEmpty(),
                    "pods should be a non-empty array");
                assertEquals(1, pods.size(),
                    "Should have exactly 1 bridge pod (1 replica)");
                assertTrue(root.path("log_lines").isNumber(), "log_lines should be a number");
                assertFalse(root.path("timestamp").isMissingNode(), "Should have timestamp");
                assertFalse(root.path("message").isMissingNode(), "Should have message");
                assertFalse(root.path("has_errors").asBoolean(), "Should have no errors");
                assertEquals(0, root.path("error_count").asInt(), "Error count should be 0");
                assertTrue(root.path("log_lines").asInt() > 0, "Should have log lines");
                assertTrue(root.path("logs").asText().contains("HTTP Bridge server started"), "Logs should contain bridge startup message");
            })
            .thenAssertResults();
    }

    @Test
    @Story("get_strimzi_events returns events for KafkaBridge")
    void testGetStrimziEventsKafkaBridge() {
        Map<String, Object> args = Map.of(
            "resourceName", Constants.BRIDGE_NAME,
            "resourceKind", "KafkaBridge",
            "namespace", kafkaNamespace.getMetadata().getName());

        mcpClient.when()
            .toolsCall("get_strimzi_events", args, response -> {
                JsonNode root = assertToolSuccess(response);

                String text = response.content().getFirst().asText().text();
                LOGGER.info("get_strimzi_events (KafkaBridge) response (length={})", text.length());
                LOGGER.debug("get_strimzi_events (KafkaBridge) response:\n{}", text);
                assertEquals(Constants.BRIDGE_NAME, root.path("resource_name").asText(),
                    "resource_name should match");
                assertEquals(kafkaNamespace.getMetadata().getName(),
                    root.path("namespace").asText(), "namespace should match deployment namespace");
                assertTrue(root.path("total_events").asInt() > 0,
                    "Should have events from bridge deployment");
                assertFalse(root.path("timestamp").isMissingNode(), "Should have timestamp");
                assertFalse(root.path("message").isMissingNode(), "Should have message");
                assertTrue(root.path("total_events").asInt() > 0, "Should have events");
                JsonNode resources = root.path("resources");
                assertTrue(resources.isArray() && resources.size() > 0, "Should have resource groups");
                assertTrue(root.path("message").asText().contains("events"), "Message should summarize events");
            })
            .thenAssertResults();
    }
}
