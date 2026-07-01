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
import io.streamshub.mcp.systemtest.templates.strimzi.KafkaMirrorMaker2Templates;
import io.streamshub.mcp.systemtest.templates.strimzi.KafkaNodePoolTemplates;
import io.streamshub.mcp.systemtest.templates.strimzi.KafkaTemplates;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * System tests for KafkaMirrorMaker2 MCP tools.
 * Deploys the MCP server with a source cluster, target cluster, and
 * KafkaMirrorMaker2 to verify that list, get, pods, logs, events,
 * and diagnostic tools return correct data.
 */
@Epic("Strimzi MCP E2E")
@Feature("KafkaMirrorMaker2 Tools")
class KafkaMirrorMaker2ToolsST extends AbstractST {

    private static final Logger LOGGER = LoggerFactory.getLogger(KafkaMirrorMaker2ToolsST.class);
    private static final String MM2_NAME = "mcp-mirror-maker";
    private static final String SOURCE_CLUSTER = Constants.KAFKA_CLUSTER_NAME;
    private static final String TARGET_CLUSTER = "mcp-target-cluster";
    private static final String SOURCE_ALIAS = "source";
    private static final String TARGET_ALIAS = "target";

    @InjectResourceManager
    KubeResourceManager krm;

    @ClassNamespace(name = Constants.MCP_NAMESPACE)
    static Namespace mcpNamespace;

    @ClassNamespace(name = Constants.STRIMZI_NAMESPACE, labels = {"app=strimzi"})
    static Namespace strimziNamespace;

    @ClassNamespace(name = Constants.KAFKA_NAMESPACE, labels = {"app=strimzi"})
    static Namespace kafkaNamespace;

    private static McpAssured.McpStreamableTestClient mcpClient;

    KafkaMirrorMaker2ToolsST() {
    }

    @BeforeAll
    void setup() {
        if (!Environment.SKIP_STRIMZI_INSTALL) {
            String kafkaNs = kafkaNamespace.getMetadata().getName();
            
            StrimziSetup.deploy(strimziNamespace.getMetadata().getName());
            
            // Source cluster
            krm.createOrUpdateResourceWithoutWait(
                KafkaNodePoolTemplates.controllerPool(kafkaNs, "source-controller",
                    SOURCE_CLUSTER, 1).build(),
                KafkaNodePoolTemplates.brokerPool(kafkaNs, "source-broker",
                    SOURCE_CLUSTER, 1).build());
            
            krm.createOrUpdateResourceWithWait(
                KafkaTemplates.kafka(kafkaNs, SOURCE_CLUSTER, 1).build());
            
            // Target cluster (same namespace, different name)
            krm.createOrUpdateResourceWithoutWait(
                KafkaNodePoolTemplates.controllerPool(kafkaNs, "target-controller",
                    TARGET_CLUSTER, 1).build(),
                KafkaNodePoolTemplates.brokerPool(kafkaNs, "target-broker",
                    TARGET_CLUSTER, 1).build());
            
            krm.createOrUpdateResourceWithWait(
                KafkaTemplates.kafka(kafkaNs, TARGET_CLUSTER, 1).build());
            
            // MirrorMaker2
            String sourceBootstrap = SOURCE_CLUSTER + "-kafka-bootstrap." + kafkaNs + ".svc:9092";
            String targetBootstrap = TARGET_CLUSTER + "-kafka-bootstrap." + kafkaNs + ".svc:9092";
            
            krm.createOrUpdateResourceWithWait(
                KafkaMirrorMaker2Templates.mirrorMaker2(kafkaNs, MM2_NAME,
                    sourceBootstrap, SOURCE_ALIAS,
                    targetBootstrap, TARGET_ALIAS, 1).build());
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
    @Story("list_kafka_mirror_makers returns deployed MM2")
    void testListMirrorMakers() {
        Map<String, Object> args = Map.of("namespace", Environment.KAFKA_NAMESPACE);
        mcpClient.when()
            .toolsCall("list_kafka_mirror_makers", args, response -> {
                JsonNode root = assertToolSuccess(response);

                String json = response.content().getFirst().asText().text();
                LOGGER.info("list_kafka_mirror_makers response:\n{}", json);
                JsonNode mm2 = findByName(root, MM2_NAME);
                assertNotNull(mm2, "Should find MirrorMaker2 '" + MM2_NAME + "'");
                assertEquals("Ready", mm2.path("readiness").asText(),
                    "MirrorMaker2 should be Ready");
                assertEquals(1, mm2.path("replicas").path("expected").asInt(), "Expected replicas should be 1");
                assertEquals(1, mm2.path("replicas").path("ready").asInt(), "Ready replicas should be 1");
                assertEquals("target", mm2.path("target_cluster").asText(), "Target cluster should be 'target'");
                assertTrue(mm2.path("source_cluster_aliases").isArray(), "source_cluster_aliases should be an array");
                assertEquals("source", mm2.path("source_cluster_aliases").get(0).asText(), "Source cluster alias should be 'source'");
            })
            .thenAssertResults();
    }

    @Test
    @Story("get_kafka_mirror_maker returns detailed MM2 info")
    void testGetMirrorMaker() {
        Map<String, Object> args = Map.of(
            "mirrorMakerName", MM2_NAME,
            "namespace", Environment.KAFKA_NAMESPACE);

        mcpClient.when()
            .toolsCall("get_kafka_mirror_maker", args, response -> {
                JsonNode root = assertToolSuccess(response);

                String json = response.content().getFirst().asText().text();
                LOGGER.info("get_kafka_mirror_maker response:\n{}", json);
                assertEquals(MM2_NAME, root.path("name").asText());
                assertEquals("Ready", root.path("readiness").asText());
                assertFalse(root.path("mirrors").isMissingNode(),
                    "Should have mirrors configuration");
                assertTrue(root.has("replicas"), "Should have replicas info");
                assertEquals(1, root.path("replicas").path("expected").asInt(), "Expected replicas should be 1");
                assertEquals(1, root.path("replicas").path("ready").asInt(), "Ready replicas should be 1");
                assertEquals("target", root.path("target_cluster").asText(), "Target cluster should be 'target'");
                assertEquals("4.2.0", root.path("version").asText(), "Version should be 4.2.0");
                JsonNode mirrors = root.path("mirrors");
                assertTrue(mirrors.isArray() && mirrors.size() == 1, "Should have exactly 1 mirror");
                assertEquals("source", mirrors.get(0).path("source_cluster").asText(), "Mirror source cluster should be 'source'");
                JsonNode connectorStatuses = root.path("connector_statuses");
                assertTrue(connectorStatuses.isArray() && connectorStatuses.size() >= 1, "Should have at least 1 connector status");
                assertEquals("RUNNING", connectorStatuses.get(0).path("connector").path("state").asText(), "Connector state should be RUNNING");
            })
            .thenAssertResults();
    }

    @Test
    @Story("get_kafka_mirror_maker_pods returns running pods")
    void testGetMirrorMakerPods() {
        Map<String, Object> args = Map.of(
            "mirrorMakerName", MM2_NAME,
            "namespace", Environment.KAFKA_NAMESPACE);

        mcpClient.when()
            .toolsCall("get_kafka_mirror_maker_pods", args, response -> {
                JsonNode root = assertToolSuccess(response);

                LOGGER.info("get_kafka_mirror_maker_pods response (length={})",
                    response.content().getFirst().asText().text().length());
                LOGGER.debug("get_kafka_mirror_maker_pods response:\n{}",
                    response.content().getFirst().asText().text());
                assertTrue(root.has("pod_summary"), "Should have pod_summary");
                assertTrue(root.path("pod_summary").path("total_pods").asInt() > 0,
                    "Should have at least one pod");
                assertFalse(root.path("pod_summary").path("health_status").isMissingNode(),
                    "Should have health_status");
                assertEquals(MM2_NAME, root.path("mirror_maker_name").asText(), "mirror_maker_name should match");
                JsonNode podSummary = root.path("pod_summary");
                assertEquals(1, podSummary.path("ready_pods").asInt(), "Should have 1 ready pod");
                assertEquals(0, podSummary.path("failed_pods").asInt(), "Should have 0 failed pods");
                assertEquals("HEALTHY", podSummary.path("health_status").asText(), "Health status should be HEALTHY");
            })
            .thenAssertResults();
    }

    @Test
    @Story("get_kafka_mirror_maker_logs returns log output")
    void testGetMirrorMakerLogs() {
        Map<String, Object> args = Map.of(
            "mirrorMakerName", MM2_NAME,
            "tailLines", 50);

        mcpClient.when()
            .toolsCall("get_kafka_mirror_maker_logs", args, response -> {
                JsonNode root = assertToolSuccess(response);

                LOGGER.info("get_kafka_mirror_maker_logs response (length={})",
                    response.content().getFirst().asText().text().length());
                LOGGER.debug("get_kafka_mirror_maker_logs response:\n{}",
                    response.content().getFirst().asText().text());
                assertLogsResponse(root, "mirror_maker_name", MM2_NAME);
                assertEquals(1, root.path("pods").size(), "Should have exactly 1 pod");
                assertTrue(root.path("log_lines").asInt() > 0, "Should have log lines");
                assertTrue(root.path("has_more").asBoolean(), "Should indicate more logs available");
                String logs = root.path("logs").asText();
                assertTrue(logs.contains("MirrorSourceConnector"), "Logs should contain MirrorSourceConnector references");
            })
            .thenAssertResults();
    }

    @Test
    @Story("get_strimzi_events returns events for MirrorMaker2")
    void testGetStrimziEventsMM2() {
        Map<String, Object> args = Map.of(
            "resourceName", MM2_NAME,
            "resourceKind", "KafkaMirrorMaker2",
            "namespace", kafkaNamespace.getMetadata().getName());

        mcpClient.when()
            .toolsCall("get_strimzi_events", args, response -> {
                JsonNode root = assertToolSuccess(response);

                String text = response.content().getFirst().asText().text();
                LOGGER.info("get_strimzi_events (MM2) response (length={})", text.length());
                LOGGER.debug("get_strimzi_events (MM2) response:\n{}", text);
                assertEquals(MM2_NAME, root.path("resource_name").asText(),
                    "resource_name should match");
                assertEquals(kafkaNamespace.getMetadata().getName(),
                    root.path("namespace").asText(),
                    "namespace should match deployment namespace");
                assertTrue(root.path("total_events").asInt() > 0,
                    "Should have events from MM2 deployment");
                JsonNode resources = root.path("resources");
                assertTrue(resources.isArray() && resources.size() > 0, "Should have resource groups");
                assertEquals("Pod", resources.get(0).path("resource_kind").asText(), "Resource kind should be Pod");
            })
            .thenAssertResults();
    }

    @Test
    @Story("diagnose_kafka_mirror_maker returns diagnostic report")
    void testDiagnoseMirrorMaker() {
        Map<String, Object> args = Map.of(
            "mirrorMakerName", MM2_NAME,
            "namespace", kafkaNamespace.getMetadata().getName());

        mcpClient.when()
            .toolsCall("diagnose_kafka_mirror_maker", args, response -> {
                JsonNode root = assertToolSuccess(response);

                LOGGER.info("diagnose_kafka_mirror_maker: steps={}",
                    root.path("steps_completed").size());
                LOGGER.debug("diagnose_kafka_mirror_maker response:\n{}",
                    response.content().getFirst().asText().text());
                assertDiagnosticReport(root);
                assertEquals(MM2_NAME,
                    root.path("mirror_maker").path("name").asText(),
                    "MirrorMaker2 name should match");
                assertEquals(4, root.path("steps_completed").size(), "Should have 4 completed steps");
                assertTrue(root.path("message").asText().contains("4 steps succeeded"), "Message should indicate 4 steps succeeded");
                assertEquals("Ready", root.path("mirror_maker").path("readiness").asText(), "MM2 readiness should be Ready");
                assertEquals(1, root.path("pods").path("pod_summary").path("total_pods").asInt(), "Should have 1 pod");
                assertEquals("HEALTHY", root.path("pods").path("pod_summary").path("health_status").asText(), "Health status should be HEALTHY");
            })
            .thenAssertResults();
    }
}
