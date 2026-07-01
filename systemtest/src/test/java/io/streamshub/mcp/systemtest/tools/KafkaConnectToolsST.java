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
import io.streamshub.mcp.systemtest.templates.strimzi.KafkaConnectTemplates;
import io.streamshub.mcp.systemtest.templates.strimzi.KafkaConnectorTemplates;
import io.streamshub.mcp.systemtest.templates.strimzi.KafkaNodePoolTemplates;
import io.streamshub.mcp.systemtest.templates.strimzi.KafkaTemplates;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

import static io.streamshub.mcp.systemtest.templates.strimzi.KafkaConnectTemplates.CAMEL_TIMER_SOURCE_CLASS_NAME;
import static io.streamshub.mcp.systemtest.templates.strimzi.KafkaConnectorTemplates.CONNECTOR_NAME;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * System tests for KafkaConnect and KafkaConnector MCP tools.
 * Deploys the MCP server, a KafkaConnect cluster, and a KafkaConnector
 * into a cluster and verifies that the tools return correct data.
 */
@Epic("Strimzi MCP E2E")
@Feature("KafkaConnect Tools")
class KafkaConnectToolsST extends AbstractST {

    private static final Logger LOGGER = LoggerFactory.getLogger(KafkaConnectToolsST.class);

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

            krm.createOrUpdateResourceWithoutWait(
                KafkaNodePoolTemplates.controllerPool(kafkaNs, "controller-np",
                    Constants.KAFKA_CLUSTER_NAME, 1).build(),
                KafkaNodePoolTemplates.brokerPool(kafkaNs, "broker-np",
                    Constants.KAFKA_CLUSTER_NAME, 1).build());

            krm.createOrUpdateResourceWithWait(
                KafkaTemplates.kafka(kafkaNs, Constants.KAFKA_CLUSTER_NAME, 1).build());

            krm.createOrUpdateResourceWithWait(
                KafkaConnectTemplates.kafkaConnect(
                    kafkaNs, Constants.CONNECT_CLUSTER_NAME, Constants.KAFKA_CLUSTER_NAME, 1).build());

            krm.createOrUpdateResourceWithWait(
                KafkaConnectorTemplates.camelTimerSource(
                    kafkaNs, CONNECTOR_NAME, Constants.CONNECT_CLUSTER_NAME, "test-topic").build());
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
    @Story("list_kafka_connects returns deployed Connect cluster")
    void testListKafkaConnects() {
        Map<String, Object> args = Map.of("namespace", Environment.KAFKA_NAMESPACE);
        mcpClient.when()
            .toolsCall("list_kafka_connects", args, response -> {
                JsonNode root = assertToolSuccess(response);

                String json = response.content().getFirst().asText().text();
                LOGGER.info("list_kafka_connects response:\n{}", json);
                JsonNode connect = findByName(root, Constants.CONNECT_CLUSTER_NAME);
                assertNotNull(connect, "Should find Connect cluster '" + Constants.CONNECT_CLUSTER_NAME + "'");
                assertEquals("Ready", connect.path("readiness").asText(), "Connect should be Ready");
                assertEquals(1, connect.path("replicas").path("expected").asInt(), "Expected replicas should be 1");
                assertEquals(1, connect.path("replicas").path("ready").asInt(), "Ready replicas should be 1");
                assertEquals("4.2.0", connect.path("version").asText(), "Version should be 4.2.0");
                assertTrue(connect.has("conditions"), "Should have conditions");
                assertEquals("Ready", connect.path("conditions").get(0).path("type").asText(), "First condition type should be Ready");
            })
            .thenAssertResults();
    }

    @Test
    @Story("get_kafka_connect returns detailed Connect info")
    void testGetKafkaConnect() {
        Map<String, Object> args = Map.of(
            "connectName", Constants.CONNECT_CLUSTER_NAME,
            "namespace", Environment.KAFKA_NAMESPACE);
        mcpClient.when()
            .toolsCall("get_kafka_connect", args, response -> {
                JsonNode connect = assertToolSuccess(response);

                String json = response.content().getFirst().asText().text();
                LOGGER.info("get_kafka_connect response:\n{}", json);
                assertEquals(Constants.CONNECT_CLUSTER_NAME, connect.path("name").asText(),
                    "Connect cluster name should match");
                assertEquals("Ready", connect.path("readiness").asText(),
                    "Connect cluster should be Ready");
                assertFalse(connect.path("rest_api_url").isMissingNode(), "Should have REST API URL");
                assertFalse(connect.path("bootstrap_servers").isMissingNode(), "Should have bootstrap servers");
                assertTrue(connect.has("replicas"), "Should have replicas info");
                assertEquals(1, connect.path("replicas").path("expected").asInt(), "Expected replicas should be 1");
                assertEquals(1, connect.path("replicas").path("ready").asInt(), "Ready replicas should be 1");
                assertEquals("4.2.0", connect.path("version").asText(), "Version should be 4.2.0");
                assertEquals(6, connect.path("connector_plugins_count").asInt(), "Should have 6 connector plugins");
                assertFalse(connect.path("creation_time").isMissingNode(), "Should have creation_time");
            })
            .thenAssertResults();
    }

    @Test
    @Story("get_kafka_connect_pods returns running pods")
    void testGetKafkaConnectPods() {
        Map<String, Object> args = Map.of(
            "connectName", Constants.CONNECT_CLUSTER_NAME,
            "namespace", Environment.KAFKA_NAMESPACE);

        mcpClient.when()
            .toolsCall("get_kafka_connect_pods", args, response -> {
                JsonNode root = assertToolSuccess(response);

                String json = response.content().getFirst().asText().text();
                LOGGER.info("get_kafka_connect_pods response:\n{}", json);
                assertTrue(root.has("pod_summary"), "Should have pod_summary");
                assertTrue(root.path("pod_summary").path("total_pods").asInt() > 0,
                    "Should have at least one pod");
                assertEquals(Constants.CONNECT_CLUSTER_NAME, root.path("connect_name").asText(), "connect_name should match");
                JsonNode podSummary = root.path("pod_summary");
                assertEquals(1, podSummary.path("ready_pods").asInt(), "Should have 1 ready pod");
                assertEquals(0, podSummary.path("failed_pods").asInt(), "Should have 0 failed pods");
                assertEquals("HEALTHY", podSummary.path("health_status").asText(), "Health status should be HEALTHY");
            })
            .thenAssertResults();
    }

    @Test
    @Story("list_kafka_connectors returns deployed connector")
    void testListKafkaConnectors() {
        Map<String, Object> args = Map.of("namespace", Environment.KAFKA_NAMESPACE);

        mcpClient.when()
            .toolsCall("list_kafka_connectors", args, response -> {
                JsonNode root = assertToolSuccess(response);

                String json = response.content().getFirst().asText().text();
                LOGGER.info("list_kafka_connectors response:\n{}", json);
                JsonNode connector = findByName(root, CONNECTOR_NAME);
                assertNotNull(connector, "Should find connector '" + CONNECTOR_NAME + "'");
                assertTrue(connector.path("class_name").asText().contains(CAMEL_TIMER_SOURCE_CLASS_NAME),
                    "Should have correct class name");
                assertEquals(1, connector.path("tasks_max").asInt(), "tasks_max should be 1");
                assertEquals("running", connector.path("state").asText(), "State should be running");
                assertEquals("Ready", connector.path("readiness").asText(), "Readiness should be Ready");
            })
            .thenAssertResults();
    }

    @Test
    @Story("list_kafka_connectors filtered by connect cluster")
    void testListKafkaConnectorsFilteredByCluster() {
        Map<String, Object> args = Map.of(
            "namespace", Environment.KAFKA_NAMESPACE,
            "connectCluster", Constants.CONNECT_CLUSTER_NAME);

        mcpClient.when()
            .toolsCall("list_kafka_connectors", args, response -> {
                JsonNode root = assertToolSuccess(response);

                String json = response.content().getFirst().asText().text();
                LOGGER.info("list_kafka_connectors (filtered) response:\n{}", json);
                JsonNode connector = findByName(root, CONNECTOR_NAME);
                assertNotNull(connector, "Should find connector '" + CONNECTOR_NAME + "'");
                assertEquals(Constants.CONNECT_CLUSTER_NAME, connector.path("connect_cluster").asText(),
                    "Connector should belong to the filtered Connect cluster");
                assertEquals("running", connector.path("state").asText(), "State should be running");
                assertEquals("Ready", connector.path("readiness").asText(), "Readiness should be Ready");
            })
            .thenAssertResults();
    }

    @Test
    @Story("get_kafka_connector returns detailed connector info")
    void testGetKafkaConnector() {
        Map<String, Object> args = Map.of(
            "connectorName", CONNECTOR_NAME,
            "namespace", Environment.KAFKA_NAMESPACE);

        mcpClient.when()
            .toolsCall("get_kafka_connector", args, response -> {
                JsonNode connector = assertToolSuccess(response);

                String json = response.content().getFirst().asText().text();
                LOGGER.info("get_kafka_connector response:\n{}", json);
                assertEquals(CONNECTOR_NAME, connector.path("name").asText(),
                    "Connector name should match");
                assertEquals(Constants.CONNECT_CLUSTER_NAME, connector.path("connect_cluster").asText(),
                    "Connect cluster should match");
                assertTrue(connector.path("class_name").asText().contains(CAMEL_TIMER_SOURCE_CLASS_NAME),
                    "Class name should contain the Camel Timer Source class");
                assertEquals(1, connector.path("tasks_max").asInt(),
                    "tasks_max should be 1");
                assertEquals("running", connector.path("state").asText(),
                    "Connector state should be running");
                assertTrue(connector.has("config"), "Should have config for get operation");
            })
            .thenAssertResults();
    }

    @Test
    @Story("get_kafka_connect_logs returns log output")
    void testGetKafkaConnectLogs() {
        Map<String, Object> args = Map.of(
            "connectName", Constants.CONNECT_CLUSTER_NAME,
            "tailLines", 50);

        mcpClient.when()
            .toolsCall("get_kafka_connect_logs", args, response -> {
                JsonNode root = assertToolSuccess(response);

                String text = response.content().getFirst().asText().text();
                LOGGER.info("get_kafka_connect_logs response (length={})", text.length());
                LOGGER.debug("get_kafka_connect_logs response:\n{}", text);
                assertEquals(Constants.CONNECT_CLUSTER_NAME, root.path("connect_name").asText(),
                    "connect_name should match");
                JsonNode pods = root.path("pods");
                assertTrue(pods.isArray() && !pods.isEmpty(),
                    "pods should be a non-empty array");
                assertEquals(1, pods.size(),
                    "Should have exactly 1 connect pod (1 replica)");
                assertTrue(root.path("log_lines").isNumber(), "log_lines should be a number");
                assertFalse(root.path("timestamp").isMissingNode(), "Should have timestamp");
                assertFalse(root.path("message").isMissingNode(), "Should have message");
                assertFalse(root.path("has_errors").asBoolean(), "Should have no errors");
                assertEquals(0, root.path("error_count").asInt(), "Error count should be 0");
                assertTrue(root.path("log_lines").asInt() > 0, "Should have log lines");
                assertTrue(root.path("has_more").asBoolean(), "Should indicate more logs available");
                assertTrue(root.path("logs").asText().contains("CamelSourceTask connector task started"), "Logs should contain CamelSourceTask started message");
            })
            .thenAssertResults();
    }

    @Test
    @Story("get_strimzi_events returns events for KafkaConnect")
    void testGetStrimziEventsKafkaConnect() {
        Map<String, Object> args = Map.of(
            "resourceName", Constants.CONNECT_CLUSTER_NAME,
            "resourceKind", "KafkaConnect",
            "namespace", kafkaNamespace.getMetadata().getName());

        mcpClient.when()
            .toolsCall("get_strimzi_events", args, response -> {
                JsonNode root = assertToolSuccess(response);

                String text = response.content().getFirst().asText().text();
                LOGGER.info("get_strimzi_events (KafkaConnect) response (length={})", text.length());
                LOGGER.debug("get_strimzi_events (KafkaConnect) response:\n{}", text);
                assertEquals(Constants.CONNECT_CLUSTER_NAME, root.path("resource_name").asText(),
                    "resource_name should match");
                assertEquals(kafkaNamespace.getMetadata().getName(),
                    root.path("namespace").asText(), "namespace should match deployment namespace");
                assertTrue(root.path("total_events").asInt() > 0,
                    "Should have events from connect deployment");
                assertFalse(root.path("timestamp").isMissingNode(), "Should have timestamp");
                assertFalse(root.path("message").isMissingNode(), "Should have message");
                assertTrue(root.path("total_events").asInt() > 0, "Should have events");
                JsonNode resources = root.path("resources");
                assertTrue(resources.isArray() && resources.size() > 0, "Should have resource groups");
                assertEquals("Pod", resources.get(0).path("resource_kind").asText(), "Resource kind should be Pod");
            })
            .thenAssertResults();
    }
}
