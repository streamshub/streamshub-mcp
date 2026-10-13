/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.systemtest.logs;

import com.fasterxml.jackson.databind.JsonNode;
import io.fabric8.kubernetes.api.model.Namespace;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.client.KubernetesClient;
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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static io.streamshub.mcp.systemtest.TestTags.ACCEPTANCE;
import static io.streamshub.mcp.systemtest.TestTags.LOGS;
import static io.streamshub.mcp.systemtest.TestTags.OPENSEARCH;
import static io.streamshub.mcp.systemtest.TestTags.REGRESSION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * System tests for OpenSearch-backed log collection MCP tools.
 * Deploys a Kafka cluster, configures the MCP server to use the OpenSearch log
 * provider, then verifies that log retrieval tools return well-formed
 * responses sourced from OpenSearch.
 */
@Epic("Strimzi MCP E2E")
@Feature("OpenSearch Log Collection")
@Tag(ACCEPTANCE)
@Tag(REGRESSION)
@Tag(LOGS)
@Tag(OPENSEARCH)
class LogsOpenSearchToolsST extends AbstractST {

    private static final Logger LOGGER = LoggerFactory.getLogger(LogsOpenSearchToolsST.class);

    @InjectResourceManager
    KubeResourceManager krm;

    @ClassNamespace(name = Constants.MCP_NAMESPACE)
    static Namespace mcpNamespace;

    @ClassNamespace(name = Constants.STRIMZI_NAMESPACE, labels = {"app=strimzi"})
    static Namespace strimziNamespace;

    @ClassNamespace(name = Constants.KAFKA_NAMESPACE, labels = {"app=strimzi"})
    static Namespace kafkaNamespace;

    private static McpAssured.McpStreamableTestClient mcpClient;

    private record OpenSearchTestConfig(String url, String authMode) {
    }

    LogsOpenSearchToolsST() {
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

        OpenSearchTestConfig opensearchConfig = discoverOpenSearch();
        LOGGER.info("Using OpenSearch: url={}, auth={}", opensearchConfig.url(), opensearchConfig.authMode());

        McpServerSetup.Builder builder = McpServerSetup.builder(mcpNamespace.getMetadata().getName())
            .withEnv("MCP_LOG_PROVIDER", "streamshub-opensearch")
            .withEnv("QUARKUS_REST_CLIENT_OPENSEARCH_URL", opensearchConfig.url())
            .withEnv("MCP_LOG_OPENSEARCH_AUTH_MODE", opensearchConfig.authMode());

        if ("basic".equals(opensearchConfig.authMode())) {
            builder.withEnv("QUARKUS_TLS_TRUST_ALL", "true");
        }

        builder.deploy();

        String mcpUrl = ConnectivitySetup.expose(mcpNamespace.getMetadata().getName());
        mcpClient = McpClientFactory.create(mcpUrl);
    }

    @AfterAll
    static void cleanup() {
        if (mcpClient != null) {
            mcpClient.disconnect();
        }
    }

    // ---- Kafka Cluster Logs via OpenSearch ----

    @Test
    @Story("get_kafka_cluster_logs returns logs from OpenSearch")
    void testGetKafkaClusterLogsViaOpenSearch() {
        Map<String, Object> args = Map.of(
            "clusterName", Constants.KAFKA_CLUSTER_NAME,
            "tailLines", 20);

        Wait.until("OpenSearch to return log data for Kafka cluster",
            Constants.KAFKA_READY_POLL_MS, Constants.KAFKA_READY_TIMEOUT_MS, () -> {
                try {
                    mcpClient.when()
                        .toolsCall("get_kafka_cluster_logs", args, response -> {
                            JsonNode root = assertToolSuccess(response);
                            String text = response.content().getFirst().asText().text();
                            LOGGER.info("get_kafka_cluster_logs via OpenSearch response (length={})", text.length());
                            LOGGER.debug("get_kafka_cluster_logs via OpenSearch response:\n{}", text);
                            assertClusterLogsResponse(root, Constants.KAFKA_CLUSTER_NAME);
                            assertTrue(root.path("log_lines").asInt() > 0, "Should have log lines from OpenSearch");
                            assertEquals(root.path("has_errors").asBoolean(), root.path("error_count").asInt() > 0,
                                "has_errors should be consistent with error_count");
                            assertTrue(root.path("has_more").asBoolean(), "Should indicate more logs are available");
                            assertFalse(root.path("logs").asText("").isEmpty(), "logs content should not be empty");
                            assertTrue(root.path("logs").asText("").contains("=== Pod:"), "logs should contain pod log sections");
                        })
                        .thenAssertResults();
                    return true;
                } catch (Exception | AssertionError ignored) {
                    LOGGER.info("OpenSearch doesn't have logs yet, retrying...");
                    return false;
                }
            }
        );
    }

    @Test
    @Story("get_kafka_cluster_logs narrows to a single container via OpenSearch")
    void testGetKafkaClusterLogsContainerViaOpenSearch() {
        Map<String, Object> args = Map.of(
            "clusterName", Constants.KAFKA_CLUSTER_NAME,
            "container", "kafka",
            "tailLines", 20);

        Wait.until("OpenSearch to return log data for Kafka cluster with container selector",
            Constants.KAFKA_READY_POLL_MS, Constants.KAFKA_READY_TIMEOUT_MS, () -> {
                try {
                    mcpClient.when()
                        .toolsCall("get_kafka_cluster_logs", args, response -> {
                            JsonNode root = assertToolSuccess(response);
                            String text = response.content().getFirst().asText().text();
                            LOGGER.info("get_kafka_cluster_logs container selector via OpenSearch response (length={})", text.length());
                            assertClusterLogsResponse(root, Constants.KAFKA_CLUSTER_NAME);
                            assertTrue(root.path("log_lines").asInt() > 0, "Should have log lines from the 'kafka' container");
                            assertFalse(root.path("logs").asText("").isEmpty(), "logs content should not be empty");
                            assertTrue(root.path("logs").asText("").contains("=== Pod:"), "logs should contain pod log sections");
                        })
                        .thenAssertResults();
                    return true;
                } catch (Exception | AssertionError ignored) {
                    LOGGER.info("OpenSearch doesn't have logs yet, retrying...");
                    return false;
                }
            }
        );
    }

    @Test
    @Story("get_kafka_cluster_logs with errors filter via OpenSearch")
    void testGetKafkaClusterLogsErrorFilterViaOpenSearch() {
        Map<String, Object> args = Map.of(
            "clusterName", Constants.KAFKA_CLUSTER_NAME,
            "filter", "errors",
            "tailLines", 100,
            "sinceMinutes", 60);

        mcpClient.when()
            .toolsCall("get_kafka_cluster_logs", args, response -> {
                JsonNode root = assertToolSuccess(response);
                String text = response.content().getFirst().asText().text();
                LOGGER.info("get_kafka_cluster_logs ERROR filter via OpenSearch (length={})", text.length());
                LOGGER.debug("get_kafka_cluster_logs ERROR filter via OpenSearch:\n{}", text);
                assertClusterLogsResponse(root, Constants.KAFKA_CLUSTER_NAME);
                int logLines = root.path("log_lines").asInt();
                int errorCount = root.path("error_count").asInt();
                assertTrue(errorCount <= logLines,
                    "error_count (" + errorCount + ") should not exceed log_lines (" + logLines + ")");
                assertEquals(root.path("has_errors").asBoolean(), errorCount > 0,
                    "has_errors should be consistent with error_count");
            })
            .thenAssertResults();
    }

    @Test
    @Story("get_kafka_cluster_logs with warnings filter via OpenSearch")
    void testGetKafkaClusterLogsWarningsFilterViaOpenSearch() {
        Map<String, Object> args = Map.of(
            "clusterName", Constants.KAFKA_CLUSTER_NAME,
            "filter", "warnings",
            "tailLines", 100,
            "sinceMinutes", 60);

        mcpClient.when()
            .toolsCall("get_kafka_cluster_logs", args, response -> {
                JsonNode root = assertToolSuccess(response);
                String text = response.content().getFirst().asText().text();
                LOGGER.info("get_kafka_cluster_logs WARNINGS filter via OpenSearch (length={})", text.length());
                LOGGER.debug("get_kafka_cluster_logs WARNINGS filter via OpenSearch:\n{}", text);
                assertClusterLogsResponse(root, Constants.KAFKA_CLUSTER_NAME);
                int logLines = root.path("log_lines").asInt();
                int errorCount = root.path("error_count").asInt();
                assertTrue(errorCount <= logLines,
                    "error_count (" + errorCount + ") should not exceed log_lines (" + logLines + ")");
                if (logLines == 0) {
                    assertFalse(root.path("has_errors").asBoolean(), "Should have no errors when no lines");
                    assertEquals(0, errorCount, "Error count should be 0 when no lines");
                }
            })
            .thenAssertResults();
    }

    @Test
    @Story("get_kafka_cluster_logs with keywords via OpenSearch")
    void testGetKafkaClusterLogsKeywordsViaOpenSearch() {
        Map<String, Object> args = Map.of(
            "clusterName", Constants.KAFKA_CLUSTER_NAME,
            "keywords", List.of("partition", "leader"),
            "tailLines", 50);

        mcpClient.when()
            .toolsCall("get_kafka_cluster_logs", args, response -> {
                JsonNode root = assertToolSuccess(response);
                String text = response.content().getFirst().asText().text();
                LOGGER.info("get_kafka_cluster_logs keywords via OpenSearch (length={})", text.length());
                LOGGER.debug("get_kafka_cluster_logs keywords via OpenSearch:\n{}", text);
                assertClusterLogsResponse(root, Constants.KAFKA_CLUSTER_NAME);
                assertEquals(root.path("has_errors").asBoolean(), root.path("error_count").asInt() > 0,
                    "has_errors should be consistent with error_count");
                assertTrue(root.path("log_lines").asInt() > 0,
                    "Keywords filter should return matching log lines");
                assertFalse(root.path("logs").asText("").isEmpty(), "logs content should not be empty");
                assertTrue(root.path("logs").asText("").contains("=== Pod:"), "logs should contain pod log sections");
                String logsLower = root.path("logs").asText().toLowerCase(Locale.ROOT);
                assertTrue(logsLower.contains("partition") || logsLower.contains("leader"),
                    "Logs should contain keyword 'partition' or 'leader' (case-insensitive)");
            })
            .thenAssertResults();
    }

    @Test
    @Story("get_kafka_cluster_logs with no-match filter via OpenSearch")
    void testGetKafkaClusterLogsNoMatchFilterViaOpenSearch() {
        Map<String, Object> args = Map.of(
            "clusterName", Constants.KAFKA_CLUSTER_NAME,
            "filter", "ZZZZNONEXISTENTZZZZ",
            "tailLines", 100);

        mcpClient.when()
            .toolsCall("get_kafka_cluster_logs", args, response -> {
                JsonNode root = assertToolSuccess(response);
                String text = response.content().getFirst().asText().text();
                LOGGER.info("get_kafka_cluster_logs no-match filter via OpenSearch (length={})", text.length());
                LOGGER.debug("get_kafka_cluster_logs no-match filter via OpenSearch:\n{}", text);
                assertClusterLogsResponse(root, Constants.KAFKA_CLUSTER_NAME);
                assertEquals(0, root.path("log_lines").asInt(),
                    "Non-matching filter should return zero log lines");
                assertFalse(root.path("has_errors").asBoolean(), "Should have no errors");
                assertEquals(0, root.path("error_count").asInt(), "Error count should be 0");
                assertFalse(root.path("has_more").asBoolean(),
                    "Should not have more logs when 0 lines returned");
            })
            .thenAssertResults();
    }

    @Test
    @Story("get_kafka_cluster_logs for specific pod via OpenSearch")
    void testGetKafkaClusterLogsSpecificPodViaOpenSearch() {
        String podName = Constants.KAFKA_CLUSTER_NAME + "-broker-np-0";
        Map<String, Object> args = Map.of(
            "clusterName", Constants.KAFKA_CLUSTER_NAME,
            "podNames", List.of(podName),
            "tailLines", 20);

        mcpClient.when()
            .toolsCall("get_kafka_cluster_logs", args, response -> {
                JsonNode root = assertToolSuccess(response);
                String text = response.content().getFirst().asText().text();
                LOGGER.info("get_kafka_cluster_logs specific pod via OpenSearch (length={})", text.length());
                LOGGER.debug("get_kafka_cluster_logs specific pod via OpenSearch:\n{}", text);
                assertClusterLogsResponse(root, Constants.KAFKA_CLUSTER_NAME);
                JsonNode pods = root.path("pods");
                assertEquals(1, pods.size(), "Should contain exactly one pod");
                assertEquals(podName, pods.get(0).asText(), "Pod name should match requested pod");
                assertTrue(root.path("log_lines").asInt() > 0,
                    "Should have log lines for the requested pod");
                assertFalse(root.path("logs").asText("").isEmpty(), "logs content should not be empty");
                assertTrue(root.path("logs").asText("").contains("=== Pod:"), "logs should contain pod log sections");
            })
            .thenAssertResults();
    }

    @Test
    @Story("get_kafka_cluster_logs with sinceMinutes via OpenSearch")
    void testGetKafkaClusterLogsSinceMinutesViaOpenSearch() {
        Map<String, Object> args = Map.of(
            "clusterName", Constants.KAFKA_CLUSTER_NAME,
            "sinceMinutes", 60,
            "tailLines", 50);

        mcpClient.when()
            .toolsCall("get_kafka_cluster_logs", args, response -> {
                JsonNode root = assertToolSuccess(response);
                String text = response.content().getFirst().asText().text();
                LOGGER.info("get_kafka_cluster_logs sinceMinutes via OpenSearch (length={})", text.length());
                LOGGER.debug("get_kafka_cluster_logs sinceMinutes via OpenSearch:\n{}", text);
                assertClusterLogsResponse(root, Constants.KAFKA_CLUSTER_NAME);
                assertTrue(root.path("log_lines").asInt() > 0,
                    "Should have log lines within the last 60 minutes");
                assertFalse(root.path("logs").asText("").isEmpty(), "logs content should not be empty");
                assertTrue(root.path("logs").asText("").contains("=== Pod:"), "logs should contain pod log sections");
            })
            .thenAssertResults();
    }

    @Test
    @Story("get_kafka_cluster_logs with absolute time range via OpenSearch")
    void testGetKafkaClusterLogsAbsoluteTimeRangeViaOpenSearch() {
        Instant now = Instant.now();
        String startTime = now.minus(30, ChronoUnit.MINUTES).toString();
        String endTime = now.toString();

        Map<String, Object> args = Map.of(
            "clusterName", Constants.KAFKA_CLUSTER_NAME,
            "startTime", startTime,
            "endTime", endTime,
            "tailLines", 50);

        mcpClient.when()
            .toolsCall("get_kafka_cluster_logs", args, response -> {
                JsonNode root = assertToolSuccess(response);
                String text = response.content().getFirst().asText().text();
                LOGGER.info("get_kafka_cluster_logs absolute time range via OpenSearch (length={})", text.length());
                LOGGER.debug("get_kafka_cluster_logs absolute time range via OpenSearch:\n{}", text);
                assertClusterLogsResponse(root, Constants.KAFKA_CLUSTER_NAME);
                assertTrue(root.path("log_lines").asInt() > 0,
                    "Should return data within the 30-minute window");
                assertFalse(root.path("logs").asText("").isEmpty(), "logs content should not be empty");
                assertTrue(root.path("logs").asText("").contains("=== Pod:"), "logs should contain pod log sections");
            })
            .thenAssertResults();
    }

    // ---- Strimzi Operator Logs via OpenSearch ----

    @Test
    @Story("get_strimzi_operator_logs returns logs via OpenSearch")
    void testGetStrimziOperatorLogsViaOpenSearch() {
        Map<String, Object> args = Map.of(
            "namespace", Constants.STRIMZI_NAMESPACE,
            "tailLines", 20);

        mcpClient.when()
            .toolsCall("get_strimzi_operator_logs", args, response -> {
                JsonNode root = assertToolSuccess(response);
                String text = response.content().getFirst().asText().text();
                LOGGER.info("get_strimzi_operator_logs via OpenSearch (length={})", text.length());
                LOGGER.debug("get_strimzi_operator_logs via OpenSearch:\n{}", text);
                assertOperatorLogsResponse(root, Constants.STRIMZI_NAMESPACE);
                assertTrue(root.path("log_lines").asInt() > 0, "Should have log lines from operator");
                assertFalse(root.path("logs").asText("").isEmpty(), "logs content should not be empty");
                assertTrue(root.path("logs").asText("").contains("=== Pod:"), "logs should contain pod log sections");
            })
            .thenAssertResults();
    }

    @Test
    @Story("get_strimzi_operator_logs with errors filter via OpenSearch")
    void testGetStrimziOperatorLogsErrorFilterViaOpenSearch() {
        Map<String, Object> args = Map.of(
            "namespace", Constants.STRIMZI_NAMESPACE,
            "filter", "errors",
            "tailLines", 100);

        mcpClient.when()
            .toolsCall("get_strimzi_operator_logs", args, response -> {
                JsonNode root = assertToolSuccess(response);
                String text = response.content().getFirst().asText().text();
                LOGGER.info("get_strimzi_operator_logs ERROR filter via OpenSearch (length={})", text.length());
                LOGGER.debug("get_strimzi_operator_logs ERROR filter via OpenSearch:\n{}", text);
                assertOperatorLogsResponse(root, Constants.STRIMZI_NAMESPACE);
                int logLines = root.path("log_lines").asInt();
                int errorCount = root.path("error_count").asInt();
                assertTrue(errorCount <= logLines,
                    "error_count (" + errorCount + ") should not exceed log_lines (" + logLines + ")");
                assertEquals(root.path("has_errors").asBoolean(), errorCount > 0,
                    "has_errors should be consistent with error_count");
            })
            .thenAssertResults();
    }

    // ---- Error Cases ----

    @Test
    @Story("get_kafka_cluster_logs returns error for non-existent cluster via OpenSearch")
    void testGetKafkaClusterLogsNotFoundViaOpenSearch() {
        Map<String, Object> args = Map.of(
            "clusterName", "nonexistent-cluster-xyz",
            "tailLines", 20);

        mcpClient.when()
            .toolsCall("get_kafka_cluster_logs")
            .withArguments(args)
            .withErrorAssert(error -> {
                LOGGER.info("get_kafka_cluster_logs not found via OpenSearch (length={})", error.message().length());
                LOGGER.debug("get_kafka_cluster_logs not found via OpenSearch:\n{}", error.message());
                assertToolProtocolError(error, -32002, "RESOURCE_NOT_FOUND", "not found", "nonexistent-cluster-xyz");
            })
            .send()
            .thenAssertResults();
    }

    // ---- OpenSearch Discovery ----

    private static OpenSearchTestConfig discoverOpenSearch() {
        if (Environment.OPENSEARCH_URL != null && !Environment.OPENSEARCH_URL.isBlank()) {
            String authMode = Environment.OPENSEARCH_AUTH_MODE != null ? Environment.OPENSEARCH_AUTH_MODE : "none";
            LOGGER.info("Using OPENSEARCH_URL override: {}", Environment.OPENSEARCH_URL);
            return new OpenSearchTestConfig(Environment.OPENSEARCH_URL, authMode);
        }

        KubernetesClient client = KubeResourceManager.get().kubeClient().getClient();

        // ECK Elasticsearch is the wire-compatible backend for the OpenSearch provider under test.
        Service backendEck = client.services()
            .inNamespace("elasticsearch-logging")
            .withName("elasticsearch-es-http")
            .get();
        if (backendEck != null) {
            String authMode = Environment.OPENSEARCH_AUTH_MODE != null ? Environment.OPENSEARCH_AUTH_MODE : "basic";
            LOGGER.info("Discovered OpenShift ECK Elasticsearch backend in elasticsearch-logging namespace");
            return new OpenSearchTestConfig(
                "https://elasticsearch-es-http.elasticsearch-logging.svc:9200", authMode);
        }

        Service backend = client.services()
            .inNamespace("elasticsearch")
            .withName("elasticsearch")
            .get();
        if (backend != null) {
            String authMode = Environment.OPENSEARCH_AUTH_MODE != null ? Environment.OPENSEARCH_AUTH_MODE : "none";
            LOGGER.info("Discovered standalone Elasticsearch backend in elasticsearch namespace");
            return new OpenSearchTestConfig(
                "http://elasticsearch.elasticsearch.svc.cluster.local:9200", authMode);
        }

        throw new IllegalStateException(
            "No OpenSearch backend service found. Deploy the ECK Elasticsearch backend "
                + "(dev/scripts/setup-elasticsearch-kind.sh) or set OPENSEARCH_URL environment variable.");
    }
}
