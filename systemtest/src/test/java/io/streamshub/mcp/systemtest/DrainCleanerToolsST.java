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
import io.streamshub.mcp.systemtest.setup.strimzi.DrainCleanerSetup;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * System tests for Strimzi Drain Cleaner MCP tools.
 * Verifies drain cleaner discovery, status, readiness, logs, and events
 * against a real Drain Cleaner deployment.
 *
 * <p>This test class does not deploy a Kafka cluster or Strimzi operator —
 * the Drain Cleaner operates independently as a webhook admission controller.
 */
@KubernetesTest(cleanup = CleanupStrategy.AUTOMATIC, collectLogs = true)
@DisplayName("DrainCleaner MCP Tools")
@Epic("Strimzi MCP E2E")
@Feature("DrainCleaner Tools")
class DrainCleanerToolsST extends AbstractST {

    private static final Logger LOGGER = LoggerFactory.getLogger(DrainCleanerToolsST.class);

    @InjectResourceManager
    KubeResourceManager krm;

    @ClassNamespace(name = Constants.MCP_NAMESPACE)
    static Namespace mcpNamespace;

    @ClassNamespace(name = Constants.DRAIN_CLEANER_NAMESPACE, labels = {"app=strimzi-drain-cleaner"})
    static Namespace drainCleanerNamespace;

    private static McpAssured.McpStreamableTestClient mcpClient;

    DrainCleanerToolsST() {
    }

    @BeforeAll
    void setup() {
        if (!Environment.SKIP_DRAIN_CLEANER_INSTALL) {
            DrainCleanerSetup.deploy(drainCleanerNamespace.getMetadata().getName());
        }
        McpServerSetup.deploy(mcpNamespace.getMetadata().getName());
        McpServerSetup.deploySensitiveRbac(
            mcpNamespace.getMetadata().getName(),
            drainCleanerNamespace.getMetadata().getName());

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
    @DisplayName("list_drain_cleaners discovers the deployed drain cleaner")
    @Story("List Drain Cleaners")
    void testListDrainCleaners() {
        Map<String, Object> args = Map.of();
        mcpClient.when()
            .toolsCall("list_drain_cleaners", args, response -> {
                assertFalse(response.isError(), "list_drain_cleaners should not return error");
                assertFalse(response.content().isEmpty(), "Should return at least one content entry");

                String json = response.content().getFirst().asText().text();
                LOGGER.info("list_drain_cleaners response:\n{}", json);

                JsonNode root = parseJson(json);

                // May be a single object or an array
                JsonNode dc = findByName(root, Constants.DRAIN_CLEANER_NAME);
                assertNotNull(dc, "Should find '" + Constants.DRAIN_CLEANER_NAME + "' in response");

                assertEquals(Constants.DRAIN_CLEANER_NAMESPACE, dc.path("namespace").asText(),
                    "Namespace should match");
                assertTrue(dc.path("ready").asBoolean(), "Drain cleaner should be ready");
                assertEquals("HEALTHY", dc.path("status").asText(),
                    "Status should be HEALTHY");
                assertTrue(dc.path("replicas").asInt() > 0,
                    "Should have at least 1 replica");
                assertEquals(dc.path("replicas").asInt(),
                    dc.path("ready_replicas").asInt(),
                    "All replicas should be ready");
                assertFalse(dc.path("version").isMissingNode(), "Should have version");
                assertFalse(dc.path("mode").isMissingNode(), "Should have mode");
            })
            .thenAssertResults();
    }

    @Test
    @DisplayName("list_drain_cleaners with namespace filter returns only matching")
    @Story("List Drain Cleaners")
    void testListDrainCleanersWithNamespace() {
        Map<String, Object> args = Map.of("namespace", Constants.DRAIN_CLEANER_NAMESPACE);
        mcpClient.when()
            .toolsCall("list_drain_cleaners", args, response -> {
                assertFalse(response.isError(), "list_drain_cleaners should not return error");

                String json = response.content().getFirst().asText().text();
                LOGGER.info("list_drain_cleaners (with namespace) response:\n{}", json);

                JsonNode root = parseJson(json);

                // May be a single object or an array
                if (root.isArray()) {
                    assertTrue(root.size() >= 1,
                        "Should find at least one drain cleaner in namespace");
                    for (JsonNode node : root) {
                        assertEquals(Constants.DRAIN_CLEANER_NAMESPACE, node.path("namespace").asText(),
                            "All entries should be in the specified namespace");
                    }
                } else {
                    assertEquals(Constants.DRAIN_CLEANER_NAMESPACE, root.path("namespace").asText(),
                        "Entry should be in the specified namespace");
                }
            })
            .thenAssertResults();
    }

    @Test
    @DisplayName("get_drain_cleaner returns detailed deployment info")
    @Story("Get Drain Cleaner")
    void testGetDrainCleaner() {
        Map<String, Object> args = Map.of(
            "drainCleanerName", Constants.DRAIN_CLEANER_NAME,
            "namespace", Constants.DRAIN_CLEANER_NAMESPACE);
        mcpClient.when()
            .toolsCall("get_drain_cleaner", args, response -> {
                assertFalse(response.isError(), "get_drain_cleaner should not return error");

                String json = response.content().getFirst().asText().text();
                LOGGER.info("get_drain_cleaner response:\n{}", json);

                JsonNode dc = parseJson(json);

                assertEquals(Constants.DRAIN_CLEANER_NAME, dc.path("name").asText(),
                    "Name should match");
                assertEquals(Constants.DRAIN_CLEANER_NAMESPACE, dc.path("namespace").asText(),
                    "Namespace should match");
                assertTrue(dc.path("ready").asBoolean(), "Should be ready");
                assertEquals("HEALTHY", dc.path("status").asText(),
                    "Status should be HEALTHY");

                assertFalse(dc.path("image").isMissingNode(), "Should have image");
                assertTrue(dc.path("image").asText().contains("drain-cleaner"),
                    "Image should contain 'drain-cleaner'");

                assertFalse(dc.path("uptime_hours").isMissingNode(), "Should have uptime_hours");
                assertFalse(dc.path("version").isMissingNode(), "Should have version");

                assertTrue(dc.path("webhook_configured").asBoolean(),
                    "Webhook should be configured");
                assertFalse(dc.path("failure_policy").isMissingNode(),
                    "Should have failure_policy");

                assertEquals("standard", dc.path("mode").asText(),
                    "Should be in standard mode");
                assertTrue(dc.path("deny_eviction").asBoolean(),
                    "Deny eviction should be true");
                assertTrue(dc.path("drain_kafka").asBoolean(),
                    "Drain kafka should be true");
            })
            .thenAssertResults();
    }

    @Test
    @DisplayName("get_drain_cleaner returns error for non-existent drain cleaner")
    @Story("Get Drain Cleaner")
    void testGetDrainCleanerNotFound() {
        Map<String, Object> args = Map.of(
            "drainCleanerName", "non-existent-drain-cleaner",
            "namespace", Constants.DRAIN_CLEANER_NAMESPACE);
        mcpClient.when()
            .toolsCall("get_drain_cleaner", args, response -> {
                assertTrue(response.isError(),
                    "Should return error for non-existent drain cleaner");

                String text = response.content().getFirst().asText().text();
                LOGGER.info("get_drain_cleaner error response: {}", text);
                assertTrue(text.toLowerCase(Locale.ROOT).contains("not found"),
                    "Error should mention 'not found'");
            })
            .thenAssertResults();
    }

    @Test
    @DisplayName("check_drain_cleaner_readiness returns all checks passing")
    @Story("Check Drain Cleaner Readiness")
    void testCheckDrainCleanerReadiness() {
        Map<String, Object> args = Map.of();
        mcpClient.when()
            .toolsCall("check_drain_cleaner_readiness", args, response -> {
                assertFalse(response.isError(),
                    "check_drain_cleaner_readiness should not return error");

                String json = response.content().getFirst().asText().text();
                LOGGER.info("check_drain_cleaner_readiness response:\n{}", json);

                JsonNode root = parseJson(json);

                assertTrue(root.path("deployed").asBoolean(), "Should be deployed");
                assertTrue(root.path("all_replicas_ready").asBoolean(),
                    "All replicas should be ready");
                assertTrue(root.path("webhook_configured").asBoolean(),
                    "Webhook should be configured");
                assertFalse(root.path("failure_policy").isMissingNode(),
                    "Should have failure_policy");
                assertEquals("standard", root.path("mode").asText(),
                    "Should be in standard mode");
                assertTrue(root.path("certificate_valid").asBoolean(),
                    "Certificate should be valid");
                assertTrue(root.path("certificate_days_until_expiry").asLong() > 0,
                    "Certificate should not be expired");
                assertFalse(root.path("covered_namespaces").isMissingNode(),
                    "Should have covered_namespaces");

                JsonNode checks = root.path("checks");
                assertTrue(checks.isArray() && !checks.isEmpty(),
                    "Should have readiness checks");
                for (JsonNode check : checks) {
                    assertFalse(check.path("name").asText().isEmpty(),
                        "Check should have a name");
                    assertFalse(check.path("detail").isMissingNode(),
                        "Check should have detail");
                }

                assertTrue(root.path("overall_ready").asBoolean(),
                    "Overall should be ready");
            })
            .thenAssertResults();
    }

    @Test
    @DisplayName("get_drain_cleaner_logs returns log output")
    @Story("Get Drain Cleaner Logs")
    void testGetDrainCleanerLogs() {
        Map<String, Object> args = Map.of("tailLines", 50);
        mcpClient.when()
            .toolsCall("get_drain_cleaner_logs", args, response -> {
                assertFalse(response.isError(),
                    "get_drain_cleaner_logs should not return error");

                String json = response.content().getFirst().asText().text();
                LOGGER.info("get_drain_cleaner_logs response (length={})", json.length());

                JsonNode root = parseJson(json);

                assertEquals(Constants.DRAIN_CLEANER_NAMESPACE, root.path("namespace").asText(),
                    "Namespace should match");
                JsonNode pods = root.path("drain_cleaner_pods");
                assertTrue(pods.isArray() && !pods.isEmpty(),
                    "Should have drain cleaner pod names");
                assertTrue(root.path("log_lines").asInt() > 0,
                    "Should have log lines");
                assertFalse(root.path("timestamp").isMissingNode(),
                    "Should have timestamp");
                assertFalse(root.path("message").isMissingNode(),
                    "Should have message");
            })
            .thenAssertResults();
    }

    @Test
    @DisplayName("get_drain_cleaner_logs with error filter does not return error")
    @Story("Get Drain Cleaner Logs")
    void testGetDrainCleanerLogsErrorFilter() {
        Map<String, Object> args = Map.of("filter", "errors", "tailLines", 100);
        mcpClient.when()
            .toolsCall("get_drain_cleaner_logs", args, response -> {
                assertFalse(response.isError(),
                    "get_drain_cleaner_logs with errors filter should not return error");

                String json = response.content().getFirst().asText().text();
                LOGGER.info("get_drain_cleaner_logs (errors filter) response (length={}):\n{}",
                    json.length(), json);
            })
            .thenAssertResults();
    }

    @Test
    @DisplayName("get_strimzi_events returns events for DrainCleaner")
    @Story("Get Strimzi Events DrainCleaner")
    void testGetStrimziEventsDrainCleaner() {
        Map<String, Object> args = Map.of(
            "resourceName", Constants.DRAIN_CLEANER_NAME,
            "resourceKind", "DrainCleaner",
            "namespace", Constants.DRAIN_CLEANER_NAMESPACE);
        mcpClient.when()
            .toolsCall("get_strimzi_events", args, response -> {
                assertFalse(response.isError(),
                    "get_strimzi_events for DrainCleaner should not return error");

                String json = response.content().getFirst().asText().text();
                LOGGER.info("get_strimzi_events (DrainCleaner) response:\n{}", json);
            })
            .thenAssertResults();
    }

}
