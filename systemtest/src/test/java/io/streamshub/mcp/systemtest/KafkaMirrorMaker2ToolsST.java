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
import io.skodjob.kubetest4j.resources.KubeResourceManager;
import io.streamshub.mcp.systemtest.clients.McpClientFactory;
import io.streamshub.mcp.systemtest.setup.mcp.ConnectivitySetup;
import io.streamshub.mcp.systemtest.setup.mcp.McpServerSetup;
import io.streamshub.mcp.systemtest.setup.strimzi.StrimziSetup;
import io.streamshub.mcp.systemtest.templates.strimzi.KafkaMirrorMaker2Templates;
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
    @DisplayName("list_kafka_mirror_makers returns deployed MM2")
    @Story("List MirrorMaker2")
    void testListMirrorMakers() {
        Map<String, Object> args = Map.of("namespace", Environment.KAFKA_NAMESPACE);
        mcpClient.when()
            .toolsCall("list_kafka_mirror_makers", args, response -> {
                assertFalse(response.isError(), "list_kafka_mirror_makers should not return error");
                String json = response.content().getFirst().asText().text();
                LOGGER.info("list_kafka_mirror_makers response:\n{}", json);

                JsonNode root = parseJson(json);
                JsonNode mm2 = findByName(root, MM2_NAME);
                assertNotNull(mm2, "Should find MirrorMaker2 '" + MM2_NAME + "'");
                assertEquals("Ready", mm2.path("readiness").asText(),
                    "MirrorMaker2 should be Ready");
            })
            .thenAssertResults();
    }

    @Test
    @DisplayName("get_kafka_mirror_maker returns detailed MM2 info")
    @Story("Get MirrorMaker2")
    void testGetMirrorMaker() {
        Map<String, Object> args = Map.of(
            "mirrorMakerName", MM2_NAME,
            "namespace", Environment.KAFKA_NAMESPACE);
        mcpClient.when()
            .toolsCall("get_kafka_mirror_maker", args, response -> {
                assertFalse(response.isError(), "get_kafka_mirror_maker should not return error");
                String json = response.content().getFirst().asText().text();
                LOGGER.info("get_kafka_mirror_maker response:\n{}", json);

                JsonNode root = parseJson(json);
                assertEquals(MM2_NAME, root.path("name").asText());
                assertEquals("Ready", root.path("readiness").asText());
                assertFalse(root.path("mirrors").isMissingNode(),
                    "Should have mirrors configuration");
                assertTrue(root.has("replicas"), "Should have replicas info");
            })
            .thenAssertResults();
    }

    @Test
    @DisplayName("get_kafka_mirror_maker_pods returns running pods")
    @Story("Get MirrorMaker2 Pods")
    void testGetMirrorMakerPods() {
        Map<String, Object> args = Map.of(
            "mirrorMakerName", MM2_NAME,
            "namespace", Environment.KAFKA_NAMESPACE);
        mcpClient.when()
            .toolsCall("get_kafka_mirror_maker_pods", args, response -> {
                JsonNode root = assertToolSuccess(response);
                LOGGER.info("get_kafka_mirror_maker_pods response:\n{}",
                    response.content().getFirst().asText().text());
                assertTrue(root.has("pod_summary"), "Should have pod_summary");
                assertTrue(root.path("pod_summary").path("total_pods").asInt() > 0,
                    "Should have at least one pod");
                assertFalse(root.path("pod_summary").path("health_status").isMissingNode(),
                    "Should have health_status");
            })
            .thenAssertResults();
    }

    @Test
    @DisplayName("get_kafka_mirror_maker_logs returns log output")
    @Story("Get MirrorMaker2 Logs")
    void testGetMirrorMakerLogs() {
        Map<String, Object> args = Map.of(
            "mirrorMakerName", MM2_NAME,
            "tailLines", 50);
        mcpClient.when()
            .toolsCall("get_kafka_mirror_maker_logs", args, response -> {
                JsonNode root = assertToolSuccess(response);
                LOGGER.info("get_kafka_mirror_maker_logs response (length={})",
                    response.content().getFirst().asText().text().length());
                assertLogsResponse(root, "mirror_maker_name", MM2_NAME);
            })
            .thenAssertResults();
    }

    @Test
    @DisplayName("get_strimzi_events returns events for MirrorMaker2")
    @Story("Get MirrorMaker2 Events")
    void testGetStrimziEventsMM2() {
        Map<String, Object> args = Map.of(
            "resourceName", MM2_NAME,
            "resourceKind", "KafkaMirrorMaker2",
            "namespace", kafkaNamespace.getMetadata().getName());
        mcpClient.when()
            .toolsCall("get_strimzi_events", args, response -> {
                assertFalse(response.isError(), "get_strimzi_events should not return error");
                String text = response.content().getFirst().asText().text();
                LOGGER.info("get_strimzi_events (MM2) response (length={})", text.length());

                JsonNode root = parseJson(text);
                assertEquals(MM2_NAME, root.path("resource_name").asText(),
                    "resource_name should match");
                assertEquals(kafkaNamespace.getMetadata().getName(),
                    root.path("namespace").asText(),
                    "namespace should match deployment namespace");
                assertTrue(root.path("total_events").asInt() > 0,
                    "Should have events from MM2 deployment");
            })
            .thenAssertResults();
    }

    @Test
    @DisplayName("diagnose_kafka_mirror_maker returns diagnostic report")
    @Story("Diagnose MirrorMaker2")
    void testDiagnoseMirrorMaker() {
        Map<String, Object> args = Map.of(
            "mirrorMakerName", MM2_NAME,
            "namespace", kafkaNamespace.getMetadata().getName());
        mcpClient.when()
            .toolsCall("diagnose_kafka_mirror_maker", args, response -> {
                JsonNode root = assertToolSuccess(response);
                LOGGER.info("diagnose_kafka_mirror_maker: steps={}",
                    root.path("steps_completed").size());
                assertDiagnosticReport(root);
                assertEquals(MM2_NAME,
                    root.path("mirror_maker").path("name").asText(),
                    "MirrorMaker2 name should match");
            })
            .thenAssertResults();
    }
}
