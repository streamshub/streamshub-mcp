/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.systemtest.resilience;

import io.fabric8.kubernetes.api.model.Namespace;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import io.quarkiverse.mcp.server.test.McpAssured;
import io.skodjob.kubetest4j.annotations.ClassNamespace;
import io.streamshub.mcp.systemtest.AbstractST;
import io.streamshub.mcp.systemtest.Constants;
import io.streamshub.mcp.systemtest.clients.McpClientFactory;
import io.streamshub.mcp.systemtest.setup.mcp.ConnectivitySetup;
import io.streamshub.mcp.systemtest.setup.mcp.McpServerSetup;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * System tests for input validation edge cases. Deploys only the MCP server
 * (no Strimzi operator or Kafka cluster) and sends malformed inputs to verify
 * the server rejects them cleanly at the validation layer without crashing,
 * leaking stack traces, or making unvalidated K8s API calls.
 */
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
    @Story("Special characters in cluster name are rejected")
    void testSpecialCharsInClusterName() {
        Map<String, Object> args = Map.of("clusterName", "my;cluster");
        
        mcpClient.when()
            .toolsCall("get_kafka_cluster", args, response -> {
                assertToolError(response);
                
                String text = response.content().getFirst().asText().text();
                LOGGER.info("Special chars response: {}", text);
                assertNoStackTrace(text);
            })
            .thenAssertResults();
    }

    @Test
    @Story("Cluster name exceeding 253 characters is rejected")
    void testNameTooLong() {
        String longName = "a".repeat(254);
        Map<String, Object> args = Map.of("clusterName", longName);
        
        mcpClient.when()
            .toolsCall("get_kafka_cluster", args, response -> {
                assertToolError(response);
                
                String text = response.content().getFirst().asText().text();
                LOGGER.info("Long name response: {}", text);
                assertNoStackTrace(text);
            })
            .thenAssertResults();
    }

    @Test
    @Story("Empty cluster name is handled gracefully")
    void testEmptyClusterName() {
        Map<String, Object> args = Map.of("clusterName", "");
        
        mcpClient.when()
            .toolsCall("get_kafka_cluster", args, response -> {
                assertToolError(response);
                
                String text = response.content().getFirst().asText().text();
                LOGGER.info("Empty name response: {}", text);
                assertNoStackTrace(text);
            })
            .thenAssertResults();
    }

    @Test
    @Story("Whitespace-padded name is handled gracefully")
    void testWhitespacePaddedName() {
        Map<String, Object> args = Map.of("clusterName", " some-cluster ");
        
        mcpClient.when()
            .toolsCall("get_kafka_cluster", args, response -> {
                assertToolError(response);
                
                String text = response.content().getFirst().asText().text();
                LOGGER.info("Whitespace-padded name response: {}", text);
                assertNoStackTrace(text);
            })
            .thenAssertResults();
    }

    // ---- Namespace edge cases ----

    @Test
    @Story("Literal string 'null' as namespace is normalized")
    void testLiteralNullNamespace() {
        Map<String, Object> args = Map.of(
            "clusterName", "any-cluster",
            "namespace", "null");
        mcpClient.when()
            .toolsCall("get_kafka_cluster", args, response -> {
                assertToolError(response);
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
    @Story("Negative tailLines is handled gracefully")
    void testNegativeTailLines() {
        Map<String, Object> args = Map.of(
            "clusterName", "any-cluster",
            "tailLines", -1);
        
        mcpClient.when()
            .toolsCall("get_kafka_cluster_logs", args, response -> {
                assertToolError(response);
                
                String text = response.content().getFirst().asText().text();
                LOGGER.info("Negative tailLines response: {}", text);
                assertNoStackTrace(text);
            })
            .thenAssertResults();
    }

    @Test
    @Story("Zero topic limit is handled gracefully")
    void testZeroLimit() {
        Map<String, Object> args = Map.of(
            "clusterName", "any-cluster",
            "limit", 0);
        
        mcpClient.when()
            .toolsCall("list_kafka_topics", args, response -> {
                assertToolError(response);
                
                String text = response.content().getFirst().asText().text();
                LOGGER.info("Zero limit response: {}", text);
                assertNoStackTrace(text);
            })
            .thenAssertResults();
    }

    @Test
    @Story("Very large tailLines is handled without timeout")
    void testVeryLargeTailLines() {
        Map<String, Object> args = Map.of(
            "clusterName", "any-cluster",
            "tailLines", 999999);
        
        mcpClient.when()
            .toolsCall("get_kafka_cluster_logs", args, response -> {
                assertToolError(response);
                
                String text = response.content().getFirst().asText().text();
                LOGGER.info("Very large tailLines response: {}", text);
                assertNoStackTrace(text);
            })
            .thenAssertResults();
    }
}
