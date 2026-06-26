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
import io.skodjob.kubetest4j.annotations.KubernetesTest;
import io.skodjob.kubetest4j.resources.KubeResourceManager;
import io.streamshub.mcp.systemtest.clients.McpClientFactory;
import io.streamshub.mcp.systemtest.setup.mcp.ConnectivitySetup;
import io.streamshub.mcp.systemtest.setup.mcp.McpServerSetup;
import io.streamshub.mcp.systemtest.setup.strimzi.StrimziSetup;
import io.streamshub.mcp.systemtest.templates.strimzi.KafkaNodePoolTemplates;
import io.streamshub.mcp.systemtest.templates.strimzi.KafkaTemplates;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * System tests for MCP server guardrails: rate limiting, response size
 * truncation, and log redaction. Deploys the MCP server with specific
 * guardrail configuration and verifies that the safety mechanisms work
 * end-to-end against a real Kafka cluster.
 */
@KubernetesTest
@DisplayName("Guardrails MCP Tools")
@Epic("Strimzi MCP E2E")
@Feature("Guardrails")
class GuardrailsST extends AbstractST {

    private static final Logger LOGGER = LoggerFactory.getLogger(GuardrailsST.class);

    /**
     * Small response size limit to trigger truncation in tests.
     * Real logs from a single broker with 50+ lines will exceed this.
     */
    private static final String MAX_RESPONSE_BYTES = "5000";

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

    private static McpAssured.McpStreamableTestClient mcpClient;

    GuardrailsST() {
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
        }

        McpServerSetup.builder(mcpNamespace.getMetadata().getName())
            .withEnv("MCP_GUARDRAIL_MAX_RESPONSE_BYTES", MAX_RESPONSE_BYTES)
            .withEnv("MCP_GUARDRAIL_RATE_LIMIT_LOG_RPM", LOG_RPM)
            .withEnv("MCP_GUARDRAIL_RATE_LIMIT_GENERAL_RPM", "0")
            .deploy();

        String mcpUrl = ConnectivitySetup.expose(mcpNamespace.getMetadata().getName());
        mcpClient = McpClientFactory.create(mcpUrl);
    }

    @AfterAll
    static void cleanup() {
        if (mcpClient != null) {
            mcpClient.disconnect();
        }
    }

    // ---- Response Size Truncation ----

    /**
     * Verify that responses exceeding {@code max-response-bytes} are truncated
     * and include a truncation notice in the message.
     */
    @Test
    @DisplayName("Response size truncation limits large log output")
    @Story("Response Size Guardrail")
    void testResponseSizeTruncation() {
        Map<String, Object> args = Map.of(
            "clusterName", Constants.KAFKA_CLUSTER_NAME,
            "namespace", kafkaNamespace.getMetadata().getName(),
            "tailLines", 500);

        mcpClient.when()
            .toolsCall("get_kafka_cluster_logs", args, response -> {
                JsonNode root = assertToolSuccess(response);

                String json = response.content().getFirst().asText().text();
                LOGGER.info("get_kafka_cluster_logs response length={} (limit={})",
                    json.length(), MAX_RESPONSE_BYTES);

                assertTrue(json.length() <= Integer.parseInt(MAX_RESPONSE_BYTES) + 500,
                    "Response should be close to or under the max-response-bytes limit "
                        + "(got " + json.length() + " bytes)");

                String message = root.path("message").asText("");
                assertTrue(message.toLowerCase(Locale.ROOT).contains("truncat")
                        || message.toLowerCase(Locale.ROOT).contains("limit"),
                    "Response message should indicate truncation occurred, got: " + message);
            })
            .thenAssertResults();
    }

    // ---- Rate Limiting ----

    /**
     * Verify that log tool calls are rate-limited after exceeding the
     * configured requests-per-minute threshold.
     */
    @Test
    @DisplayName("Rate limiting enforced after exceeding log RPM threshold")
    @Story("Rate Limit Guardrail")
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
                    assertFalse(response.isError(),
                        "Log call #" + callNum + " should succeed (within rate limit)");
                    LOGGER.info("Log call #{} succeeded as expected", callNum);
                })
                .thenAssertResults();
        }

        // Third call should hit the rate limit
        mcpClient.when()
            .toolsCall("get_kafka_cluster_logs", args, response -> {
                assertToolError(response, "rate");
                LOGGER.info("Log call #3 rate-limited as expected");
            })
            .thenAssertResults();
    }

    /**
     * Verify that rate limiting is applied per category: log tools are
     * rate-limited while general tools remain unrestricted.
     */
    @Test
    @DisplayName("Rate limiting applies per category (log vs general)")
    @Story("Rate Limit Guardrail")
    void testRateLimitPerCategory() {
        Map<String, Object> logArgs = Map.of(
            "clusterName", Constants.KAFKA_CLUSTER_NAME,
            "namespace", kafkaNamespace.getMetadata().getName(),
            "tailLines", 10);

        // Exhaust the log rate limit (LOG_RPM=2)
        for (int i = 1; i <= 2; i++) {
            mcpClient.when()
                .toolsCall("get_kafka_cluster_logs", logArgs, response ->
                    assertFalse(response.isError(), "Log call should succeed within limit"))
                .thenAssertResults();
        }

        // Next log call should be rate-limited
        mcpClient.when()
            .toolsCall("get_kafka_cluster_logs", logArgs, response -> {
                assertToolError(response, "rate");
                LOGGER.info("Log tool rate-limited as expected");
            })
            .thenAssertResults();

        // General tool should still work (GENERAL_RPM=0 = unlimited)
        Map<String, Object> generalArgs = Map.of(
            "clusterName", Constants.KAFKA_CLUSTER_NAME,
            "namespace", kafkaNamespace.getMetadata().getName());

        mcpClient.when()
            .toolsCall("get_kafka_cluster", generalArgs, response -> {
                JsonNode root = assertToolSuccess(response);
                LOGGER.info("General tool succeeded despite log rate limit: {}",
                    root.path("name").asText());
            })
            .thenAssertResults();
    }

    // ---- Log Redaction ----

    /**
     * Verify that the log redaction filter is active and redacts sensitive
     * patterns from log output. Log redaction is enabled by default.
     */
    @Test
    @DisplayName("Log redaction removes sensitive patterns from log output")
    @Story("Log Redaction Guardrail")
    void testLogRedactionActive() {
        Map<String, Object> args = Map.of(
            "clusterName", Constants.KAFKA_CLUSTER_NAME,
            "namespace", kafkaNamespace.getMetadata().getName(),
            "tailLines", 200);

        mcpClient.when()
            .toolsCall("get_kafka_cluster_logs", args, response -> {
                JsonNode root = assertToolSuccess(response);

                String fullResponse = response.content().getFirst().asText().text();
                LOGGER.info("Log response length={}, checking redaction patterns",
                    fullResponse.length());

                // These patterns should never appear in tool output when redaction is active
                assertFalse(fullResponse.contains("password="),
                    "Logs should not contain raw 'password=' patterns");
                assertFalse(fullResponse.contains("Bearer ey"),
                    "Logs should not contain raw Bearer tokens");
                assertFalse(fullResponse.contains("BEGIN PRIVATE KEY"),
                    "Logs should not contain private key material");
                assertFalse(fullResponse.contains("BEGIN RSA PRIVATE KEY"),
                    "Logs should not contain RSA private key material");
            })
            .thenAssertResults();
    }
}
