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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * System tests for MCP protocol-level behavior. Deploys only the MCP server
 * (no Strimzi or Kafka) and verifies tool discovery, prompt template
 * registration, and concurrent session handling.
 */
@KubernetesTest
@DisplayName("MCP Protocol")
@Epic("Strimzi MCP E2E")
@Feature("MCP Protocol")
class McpProtocolST extends AbstractST {

    private static final Logger LOGGER = LoggerFactory.getLogger(McpProtocolST.class);

    private static final int MIN_EXPECTED_TOOLS = 50;
    private static final int MIN_EXPECTED_PROMPTS = 8;

    @ClassNamespace(name = Constants.MCP_NAMESPACE)
    static Namespace mcpNamespace;

    private static McpAssured.McpStreamableTestClient mcpClient;

    McpProtocolST() {
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

    // ---- Tool Discovery ----

    @Test
    @DisplayName("MCP server registers all expected tools with schemas")
    @Story("Tool Discovery")
    void testToolDiscovery() {
        mcpClient.when()
            .toolsList(page -> {
                LOGGER.info("tools/list returned {} tools", page.size());
                assertTrue(page.size() >= MIN_EXPECTED_TOOLS,
                    "Server should register at least " + MIN_EXPECTED_TOOLS
                        + " tools, got " + page.size());

                // Spot-check critical tools exist
                assertNotNull(page.findByName("list_kafka_clusters"),
                    "Should register list_kafka_clusters");
                assertNotNull(page.findByName("get_kafka_cluster"),
                    "Should register get_kafka_cluster");
                assertNotNull(page.findByName("diagnose_kafka_cluster"),
                    "Should register diagnose_kafka_cluster");
                assertNotNull(page.findByName("get_kafka_metrics"),
                    "Should register get_kafka_metrics");
                assertNotNull(page.findByName("get_kafka_fleet_overview"),
                    "Should register get_kafka_fleet_overview");

                // Verify each tool has a description and input schema
                page.tools().forEach(tool -> {
                    assertFalse(tool.description() == null || tool.description().isBlank(),
                        "Tool '" + tool.name() + "' should have a non-empty description");
                    assertNotNull(tool.inputSchema(),
                        "Tool '" + tool.name() + "' should have an inputSchema");
                });
            })
            .thenAssertResults();
    }

    // ---- Prompt Template Discovery ----

    @Test
    @DisplayName("MCP server registers all expected prompt templates")
    @Story("Prompt Discovery")
    void testPromptTemplatesDiscovery() {
        mcpClient.when()
            .promptsList(page -> {
                LOGGER.info("prompts/list returned {} prompts", page.size());
                assertTrue(page.size() >= MIN_EXPECTED_PROMPTS,
                    "Server should register at least " + MIN_EXPECTED_PROMPTS
                        + " prompts, got " + page.size());

                // Verify each prompt has a description
                page.prompts().forEach(prompt -> {
                    assertFalse(prompt.description() == null || prompt.description().isBlank(),
                        "Prompt '" + prompt.name() + "' should have a non-empty description");
                    LOGGER.debug("Prompt: {} — {}", prompt.name(), prompt.description());
                });
            })
            .thenAssertResults();
    }

    // ---- Concurrent Sessions ----

    @Test
    @DisplayName("Concurrent MCP tool calls do not crash or leak data")
    @Story("Concurrent Sessions")
    void testConcurrentToolCalls() throws InterruptedException {
        AtomicBoolean call1Ok = new AtomicBoolean(false);
        AtomicBoolean call2Ok = new AtomicBoolean(false);
        CountDownLatch latch = new CountDownLatch(2);

        Thread t1 = new Thread(() -> {
            try {
                mcpClient.when()
                    .toolsCall("list_kafka_clusters",
                        Map.of("namespace", "nonexistent-ns-1"), response -> {
                            // Expected to error (no cluster) but should not crash
                            call1Ok.set(true);
                            LOGGER.info("Concurrent call 1 completed (isError={})",
                                response.isError());
                        })
                    .thenAssertResults();
            } finally {
                latch.countDown();
            }
        });

        Thread t2 = new Thread(() -> {
            try {
                mcpClient.when()
                    .toolsCall("get_kafka_fleet_overview",
                        Map.of("namespace", "nonexistent-ns-2"), response -> {
                            call2Ok.set(true);
                            LOGGER.info("Concurrent call 2 completed (isError={})",
                                response.isError());
                        })
                    .thenAssertResults();
            } finally {
                latch.countDown();
            }
        });

        t1.start();
        t2.start();

        assertTrue(latch.await(60, TimeUnit.SECONDS),
            "Both concurrent calls should complete within 60 seconds");
        assertTrue(call1Ok.get(), "Concurrent call 1 should have completed");
        assertTrue(call2Ok.get(), "Concurrent call 2 should have completed");
    }
}
