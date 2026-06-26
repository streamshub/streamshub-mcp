/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.systemtest;

import io.fabric8.kubernetes.api.model.Namespace;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import io.quarkiverse.mcp.server.test.McpAssured;
import io.skodjob.kubetest4j.annotations.ClassNamespace;
import io.skodjob.kubetest4j.annotations.KubernetesTest;
import io.streamshub.mcp.systemtest.clients.McpClientFactory;
import io.streamshub.mcp.systemtest.setup.mcp.ConnectivitySetup;
import io.streamshub.mcp.systemtest.setup.mcp.McpServerSetup;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * System tests for input validation edge cases. Deploys only the MCP server
 * (no Strimzi operator or Kafka cluster) and sends malformed inputs to verify
 * the server rejects them cleanly at the validation layer without crashing,
 * leaking stack traces, or making unvalidated K8s API calls.
 */
@KubernetesTest
@DisplayName("Input Validation")
@Epic("Strimzi MCP E2E")
@Feature("Input Validation")
class InputValidationST extends AbstractST {

    private static final Logger LOGGER = LoggerFactory.getLogger(InputValidationST.class);

    @ClassNamespace(name = Constants.MCP_NAMESPACE)
    static Namespace mcpNamespace;

    private static McpAssured.McpStreamableTestClient mcpClient;

    InputValidationST() {
    }

    @BeforeAll
    void setup() {
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

    // ---- Invalid name format ----

    @Test
    @DisplayName("Special characters in cluster name are rejected")
    @Story("Name Validation")
    void testSpecialCharsInClusterName() {
        Map<String, Object> args = Map.of("clusterName", "my;cluster");
        mcpClient.when()
            .toolsCall("get_kafka_cluster", args, response -> {
                assertTrue(response.isError(),
                    "Special characters in cluster name should be rejected");
                String text = response.content().getFirst().asText().text();
                LOGGER.info("Special chars response: {}", text);
                assertNoStackTrace(text);
            })
            .thenAssertResults();
    }

    @Test
    @DisplayName("Cluster name exceeding 253 characters is rejected")
    @Story("Name Validation")
    void testNameTooLong() {
        String longName = "a".repeat(254);
        Map<String, Object> args = Map.of("clusterName", longName);
        mcpClient.when()
            .toolsCall("get_kafka_cluster", args, response -> {
                assertTrue(response.isError(),
                    "Name exceeding 253 chars should be rejected");
                String text = response.content().getFirst().asText().text();
                LOGGER.info("Long name response: {}", text);
                assertNoStackTrace(text);
            })
            .thenAssertResults();
    }

    @Test
    @DisplayName("Empty cluster name is handled gracefully")
    @Story("Name Validation")
    void testEmptyClusterName() {
        Map<String, Object> args = Map.of("clusterName", "");
        mcpClient.when()
            .toolsCall("get_kafka_cluster", args, response -> {
                assertTrue(response.isError(),
                    "Empty cluster name should return an error");
                String text = response.content().getFirst().asText().text();
                LOGGER.info("Empty name response: {}", text);
                assertNoStackTrace(text);
            })
            .thenAssertResults();
    }

    @Test
    @DisplayName("Whitespace-padded name is handled gracefully")
    @Story("Name Validation")
    void testWhitespacePaddedName() {
        Map<String, Object> args = Map.of("clusterName", " some-cluster ");
        mcpClient.when()
            .toolsCall("get_kafka_cluster", args, response -> {
                // Either trimmed+resolved (not found) or validation error — both acceptable
                String text = response.content().getFirst().asText().text();
                LOGGER.info("Whitespace-padded name response (isError={}): {}",
                    response.isError(), text);
                assertNoStackTrace(text);
            })
            .thenAssertResults();
    }

    // ---- Namespace edge cases ----

    @Test
    @DisplayName("Literal string 'null' as namespace is normalized")
    @Story("Namespace Validation")
    void testLiteralNullNamespace() {
        Map<String, Object> args = Map.of(
            "clusterName", "any-cluster",
            "namespace", "null");
        mcpClient.when()
            .toolsCall("get_kafka_cluster", args, response -> {
                // InputUtils.normalizeInput converts "null" to null (auto-discover)
                // Result should be a clean error (cluster not found), not a crash
                String text = response.content().getFirst().asText().text();
                LOGGER.info("Literal 'null' namespace response (isError={}): {}",
                    response.isError(), text);
                assertNoStackTrace(text);
            })
            .thenAssertResults();
    }

    // ---- Numeric parameter edge cases ----

    @Test
    @DisplayName("Negative tailLines is handled gracefully")
    @Story("Numeric Validation")
    void testNegativeTailLines() {
        Map<String, Object> args = Map.of(
            "clusterName", "any-cluster",
            "tailLines", -1);
        mcpClient.when()
            .toolsCall("get_kafka_cluster_logs", args, response -> {
                String text = response.content().getFirst().asText().text();
                LOGGER.info("Negative tailLines response (isError={}): {}",
                    response.isError(), text);
                assertNoStackTrace(text);
            })
            .thenAssertResults();
    }

    @Test
    @DisplayName("Zero topic limit is handled gracefully")
    @Story("Numeric Validation")
    void testZeroLimit() {
        Map<String, Object> args = Map.of(
            "clusterName", "any-cluster",
            "limit", 0);
        mcpClient.when()
            .toolsCall("list_kafka_topics", args, response -> {
                String text = response.content().getFirst().asText().text();
                LOGGER.info("Zero limit response (isError={}): {}", response.isError(), text);
                assertNoStackTrace(text);
            })
            .thenAssertResults();
    }

    @Test
    @DisplayName("Very large tailLines is handled without timeout")
    @Story("Numeric Validation")
    void testVeryLargeTailLines() {
        Map<String, Object> args = Map.of(
            "clusterName", "any-cluster",
            "tailLines", 999999);
        mcpClient.when()
            .toolsCall("get_kafka_cluster_logs", args, response -> {
                // Server should either reject, clamp, or return error (cluster not found)
                // Key: no hang, no OOM, no stack trace
                String text = response.content().getFirst().asText().text();
                LOGGER.info("Very large tailLines response (isError={}): {}",
                    response.isError(), text);
                assertNoStackTrace(text);
            })
            .thenAssertResults();
    }

    /**
     * Assert that a response does not contain Java stack trace indicators,
     * which would suggest an unhandled exception was leaked to the client.
     */
    private static void assertNoStackTrace(final String text) {
        assertFalse(text.contains("java.lang."),
            "Response should not contain Java package references");
        assertFalse(text.contains("NullPointerException"),
            "Response should not contain NullPointerException");
        assertFalse(text.contains("\tat "),
            "Response should not contain stack trace frames");
    }
}
