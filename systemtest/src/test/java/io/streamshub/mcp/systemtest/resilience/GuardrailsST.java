/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.systemtest.resilience;

import com.fasterxml.jackson.databind.JsonNode;
import io.fabric8.kubernetes.api.model.Namespace;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import io.quarkiverse.mcp.server.test.McpAssured;
import io.skodjob.kubetest4j.annotations.ClassNamespace;
import io.skodjob.kubetest4j.annotations.InjectResourceManager;
import io.skodjob.kubetest4j.resources.KubeResourceManager;
import io.skodjob.kubetest4j.wait.Wait;
import io.streamshub.mcp.systemtest.AbstractST;
import io.streamshub.mcp.systemtest.Constants;
import io.streamshub.mcp.systemtest.Environment;
import io.streamshub.mcp.systemtest.clients.McpClientFactory;
import io.streamshub.mcp.systemtest.setup.mcp.ConnectivitySetup;
import io.streamshub.mcp.systemtest.setup.mcp.McpServerSetup;
import io.streamshub.mcp.systemtest.setup.strimzi.StrimziSetup;
import io.streamshub.mcp.systemtest.templates.strimzi.KafkaNodePoolTemplates;
import io.streamshub.mcp.systemtest.templates.strimzi.KafkaTemplates;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * System tests for MCP server guardrails: rate limiting, response size
 * truncation, and log redaction. Deploys the MCP server with specific
 * guardrail configuration and verifies that the safety mechanisms work
 * end-to-end against a real Kafka cluster.
 */
@Epic("Strimzi MCP E2E")
@Feature("Guardrails for requests")
class GuardrailsST extends AbstractST {

    private static final Logger LOGGER = LoggerFactory.getLogger(GuardrailsST.class);
    /**
     * Small response size limit to trigger truncation in tests.
     * Kafka startup logs from a fresh cluster easily exceed this.
     */
    private static final String MAX_RESPONSE_BYTES = "1000";
    /**
     * Rate limit for log tools: 2 requests per minute.
     * Low enough to trigger within a single test method.
     */
    private static final String LOG_RPM = "2";

    @InjectResourceManager
    KubeResourceManager krm;

    @ClassNamespace(name = Constants.MCP_NAMESPACE)
    static Namespace mcpNamespace;

    @ClassNamespace(name = Constants.STRIMZI_NAMESPACE, labels = {"app=strimzi"})
    static Namespace strimziNamespace;

    @ClassNamespace(name = Constants.KAFKA_NAMESPACE, labels = {"app=strimzi"})
    static Namespace kafkaNamespace;
    private McpAssured.McpStreamableTestClient mcpClient;
    GuardrailsST() {
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
        }
    }

    @BeforeEach
    void setupMcpServer() {
        McpServerSetup.builder(mcpNamespace.getMetadata().getName())
            .withEnv("MCP_GUARDRAIL_MAX_RESPONSE_BYTES", MAX_RESPONSE_BYTES)
            .withEnv("MCP_GUARDRAIL_RATE_LIMIT_LOG_RPM", LOG_RPM)
            .withEnv("MCP_GUARDRAIL_RATE_LIMIT_GENERAL_RPM", "0")
            .deploy();

        String mcpUrl = ConnectivitySetup.expose(mcpNamespace.getMetadata().getName());
        mcpClient = McpClientFactory.create(mcpUrl);
    }

    @AfterEach
    void cleanupClient() {
        if (mcpClient != null) {
            mcpClient.disconnect();
        }
    }

    // ---- Response Size Truncation ----
    /**
     * Verify that responses exceeding {@code max-response-bytes} are truncated
     * and include a truncation notice in the response content.
     */
    @Test
    @Story("Response size truncation limits large log output")
    void testResponseSizeTruncation() {
        Map<String, Object> args = Map.of(
            "clusterName", Constants.KAFKA_CLUSTER_NAME,
            "namespace", kafkaNamespace.getMetadata().getName(),
            "tailLines", 500);

        AtomicReference<String> capturedJson = new AtomicReference<>();
        Wait.until("cluster logs to trigger response size truncation",
            Constants.KAFKA_READY_POLL_MS, Constants.MCP_READY_TIMEOUT_MS, () -> {
                try {
                    mcpClient.when()
                        .toolsCall("get_kafka_cluster_logs", args, response -> {
                            if (!response.isError()) {
                                capturedJson.set(
                                    response.content().getFirst().asText().text());
                            }
                        })
                        .thenAssertResults();
                } catch (Exception e) {
                    LOGGER.debug("Tool call attempt failed, retrying: {}",
                        e.getMessage());
                    return false;
                }
                String json = capturedJson.get();
                return json != null && json.contains("[...response truncated");
            });
        String json = capturedJson.get();
        LOGGER.info("Truncated response (length={}, limit={}): {}",
            json.length(), MAX_RESPONSE_BYTES, json);
        assertTrue(json.length() <= Integer.parseInt(MAX_RESPONSE_BYTES) + 500,
            "Response should be close to or under the max-response-bytes limit "
                + "(got " + json.length() + " bytes)");
        JsonNode truncRoot = parseJson(json);
        assertEquals("mcp-cluster", truncRoot.path("cluster_name").asText(),
            "cluster_name should be preserved even in truncated response");
        assertTrue(truncRoot.path("log_lines").asInt() > 0,
            "log_lines should be non-zero to confirm logs were actually truncated, not empty");
    }

    // ---- Rate Limiting ----
    /**
     * Verify that log tool calls are rate-limited after exceeding the
     * configured requests-per-minute threshold.
     */
    @Test
    @Story("Rate limiting enforced after exceeding log RPM threshold")
    void testRateLimitEnforced() {
        Map<String, Object> args = Map.of(
            "clusterName", Constants.KAFKA_CLUSTER_NAME,
            "namespace", kafkaNamespace.getMetadata().getName(),
            "tailLines", 10);
        
        // First two calls should succeed (LOG_RPM=2)
        for (int i = 1; i <= 2; i++) {
            int callNum = i;
            mcpClient.when()
                .toolsCall("get_kafka_cluster_logs", args, response -> {
                    assertToolSuccess(response);
                    LOGGER.info("Log call #{} succeeded (within rate limit)", callNum);
                })
                .thenAssertResults();
        }
        
        // Third call should hit the rate limit
        mcpClient.when()
            .toolsCall("get_kafka_cluster_logs", args, response -> {
                LOGGER.info("Log call #3 response (isError={})", response.isError());
                assertToolError(response, "rate");
                String text = response.content().getFirst().asText().text();
                assertTrue(text.contains("Rate limit exceeded"),
                    "Error should say 'Rate limit exceeded', got: " + text);
                assertTrue(text.contains("2/min"),
                    "Error should reference the configured limit '2/min', got: " + text);
                assertTrue(text.contains("Try again in"),
                    "Error should include retry guidance, got: " + text);
            })
            .thenAssertResults();
    }

    /**
     * Verify that rate limiting is applied per category: log tools are
     * rate-limited while general tools remain unrestricted.
     */
    @Test
    @Story("Rate limiting applies per category (log vs general)")
    void testRateLimitPerCategory() {
        Map<String, Object> logArgs = Map.of(
            "clusterName", Constants.KAFKA_CLUSTER_NAME,
            "namespace", kafkaNamespace.getMetadata().getName(),
            "tailLines", 10);
        
        // Exhaust the log rate limit (LOG_RPM=2)
        for (int i = 1; i <= 2; i++) {
            int callNum = i;
            mcpClient.when()
                .toolsCall("get_kafka_cluster_logs", logArgs, response -> {
                    assertToolSuccess(response);
                    LOGGER.info("Log call #{} succeeded (within rate limit)", callNum);
                })
                .thenAssertResults();
        }
        
        // Next log call should be rate-limited
        mcpClient.when()
            .toolsCall("get_kafka_cluster_logs", logArgs, response -> {
                LOGGER.info("Log call #3 response (isError={})", response.isError());
                assertToolError(response, "rate");
            })
            .thenAssertResults();
        
        // General tool should still work (GENERAL_RPM=0 = unlimited)
        Map<String, Object> generalArgs = Map.of(
            "clusterName", Constants.KAFKA_CLUSTER_NAME,
            "namespace", kafkaNamespace.getMetadata().getName());
        
        mcpClient.when()
            .toolsCall("get_kafka_cluster", generalArgs, response -> {
                String text = response.content().getFirst().asText().text();
                LOGGER.info("General tool response (isError={}): {}", response.isError(), text);
                JsonNode root = assertToolSuccess(response);
                LOGGER.info("General tool succeeded despite log rate limit: {}",
                    root.path("name").asText());
                assertEquals("mcp-cluster", root.path("name").asText(),
                    "General tool should return the correct cluster name");
                assertEquals("Ready", root.path("readiness").asText(),
                    "Cluster readiness should be Ready");
            })
            .thenAssertResults();
    }

    // ---- Log Redaction ----
    /**
     * Verify that the log redaction filter is active and redacts sensitive
     * patterns from log output. Log redaction is enabled by default.
     */
    @Test
    @Story("Log redaction removes sensitive patterns from log output")
    void testLogRedactionActive() {
        Map<String, Object> args = Map.of(
            "clusterName", Constants.KAFKA_CLUSTER_NAME,
            "namespace", kafkaNamespace.getMetadata().getName(),
            "tailLines", 200);
        
        mcpClient.when()
            .toolsCall("get_kafka_cluster_logs", args, response -> {
                String fullResponse = response.content().getFirst().asText().text();
                LOGGER.info("Log redaction response (isError={}, length={})",
                    response.isError(), fullResponse.length());
                // These patterns should never appear in tool output when redaction is active
                assertFalse(fullResponse.contains("password="),
                    "Logs should not contain raw 'password=' patterns");
                assertFalse(fullResponse.contains("Bearer "),
                    "Logs should not contain raw Bearer tokens");
                assertFalse(fullResponse.contains("BEGIN PRIVATE KEY"),
                    "Logs should not contain private key material");
                assertFalse(fullResponse.contains("BEGIN RSA PRIVATE KEY"),
                    "Logs should not contain RSA private key material");
                assertFalse(response.isError(), "Log call should succeed");
                assertFalse(fullResponse.contains("secret_key="),
                    "Logs should not contain raw 'secret_key=' patterns");
            })
            .thenAssertResults();
    }
}
