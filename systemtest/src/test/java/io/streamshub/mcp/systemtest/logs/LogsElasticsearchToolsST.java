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
import static io.streamshub.mcp.systemtest.TestTags.ELASTICSEARCH;
import static io.streamshub.mcp.systemtest.TestTags.LOGS;
import static io.streamshub.mcp.systemtest.TestTags.REGRESSION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * System tests for Elasticsearch-backed log collection MCP tools.
 * Deploys a Kafka cluster, configures the MCP server to use the Elasticsearch log
 * provider, then verifies that log retrieval tools return well-formed
 * responses sourced from Elasticsearch.
 */
@Epic("Strimzi MCP E2E")
@Feature("Elasticsearch Log Collection")
@Tag(ACCEPTANCE)
@Tag(REGRESSION)
@Tag(LOGS)
@Tag(ELASTICSEARCH)
class LogsElasticsearchToolsST extends AbstractST {

    private static final Logger LOGGER = LoggerFactory.getLogger(LogsElasticsearchToolsST.class);

    @InjectResourceManager
    KubeResourceManager krm;

    @ClassNamespace(name = Constants.MCP_NAMESPACE)
    static Namespace mcpNamespace;

    @ClassNamespace(name = Constants.STRIMZI_NAMESPACE, labels = {"app=strimzi"})
    static Namespace strimziNamespace;

    @ClassNamespace(name = Constants.KAFKA_NAMESPACE, labels = {"app=strimzi"})
    static Namespace kafkaNamespace;

    private static McpAssured.McpStreamableTestClient mcpClient;

    private record ElasticsearchTestConfig(String url, String authMode) {
    }

    LogsElasticsearchToolsST() {
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

        ElasticsearchTestConfig elasticsearchConfig = discoverElasticsearch();
        LOGGER.info("Using Elasticsearch: url={}, auth={}", elasticsearchConfig.url(), elasticsearchConfig.authMode());

        McpServerSetup.Builder builder = McpServerSetup.builder(mcpNamespace.getMetadata().getName())
            .withEnv("MCP_LOG_PROVIDER", "streamshub-elasticsearch")
            .withEnv("QUARKUS_REST_CLIENT_ELASTICSEARCH_URL", elasticsearchConfig.url())
            .withEnv("MCP_LOG_ELASTICSEARCH_AUTH_MODE", elasticsearchConfig.authMode());

        if ("basic".equals(elasticsearchConfig.authMode())) {
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

    // ---- Kafka Cluster Logs via Elasticsearch ----

    @Test
    @Story("get_kafka_cluster_logs returns logs from Elasticsearch")
    void testGetKafkaClusterLogsViaElasticsearch() {
        Map<String, Object> args = Map.of(
            "clusterName", Constants.KAFKA_CLUSTER_NAME,
            "tailLines", 20);

        Wait.until("Elasticsearch to return log data for Kafka cluster",
            Constants.KAFKA_READY_POLL_MS, Constants.KAFKA_READY_TIMEOUT_MS, () -> {
                try {
                    mcpClient.when()
                        .toolsCall("get_kafka_cluster_logs", args, response -> {
                            JsonNode root = assertToolSuccess(response);
                            String text = response.content().getFirst().asText().text();
                            LOGGER.info("get_kafka_cluster_logs via Elasticsearch response (length={})", text.length());
                            LOGGER.debug("get_kafka_cluster_logs via Elasticsearch response:\n{}", text);
                            assertClusterLogsResponse(root, Constants.KAFKA_CLUSTER_NAME);
                            assertTrue(root.path("log_lines").asInt() > 0, "Should have log lines from Elasticsearch");
                            assertEquals(root.path("has_errors").asBoolean(), root.path("error_count").asInt() > 0,
                                "has_errors should be consistent with error_count");
                            assertTrue(root.path("has_more").asBoolean(), "Should indicate more logs are available");
                            assertFalse(root.path("logs").asText("").isEmpty(), "logs content should not be empty");
                            assertTrue(root.path("logs").asText("").contains("=== Pod:"), "logs should contain pod log sections");
                        })
                        .thenAssertResults();
                    return true;
                } catch (Exception | AssertionError ignored) {
                    LOGGER.info("Elasticsearch doesn't have logs yet, retrying...");
                    return false;
                }
            }
        );
    }

    @Test
    @Story("get_kafka_cluster_logs with errors filter via Elasticsearch")
    void testGetKafkaClusterLogsErrorFilterViaElasticsearch() {
        Map<String, Object> args = Map.of(
            "clusterName", Constants.KAFKA_CLUSTER_NAME,
            "filter", "errors",
            "tailLines", 100,
            "sinceMinutes", 60);

        mcpClient.when()
            .toolsCall("get_kafka_cluster_logs", args, response -> {
                JsonNode root = assertToolSuccess(response);
                String text = response.content().getFirst().asText().text();
                LOGGER.info("get_kafka_cluster_logs ERROR filter via Elasticsearch (length={})", text.length());
                LOGGER.debug("get_kafka_cluster_logs ERROR filter via Elasticsearch:\n{}", text);
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
    @Story("get_kafka_cluster_logs with warnings filter via Elasticsearch")
    void testGetKafkaClusterLogsWarningsFilterViaElasticsearch() {
        Map<String, Object> args = Map.of(
            "clusterName", Constants.KAFKA_CLUSTER_NAME,
            "filter", "warnings",
            "tailLines", 100,
            "sinceMinutes", 60);

        mcpClient.when()
            .toolsCall("get_kafka_cluster_logs", args, response -> {
                JsonNode root = assertToolSuccess(response);
                String text = response.content().getFirst().asText().text();
                LOGGER.info("get_kafka_cluster_logs WARNINGS filter via Elasticsearch (length={})", text.length());
                LOGGER.debug("get_kafka_cluster_logs WARNINGS filter via Elasticsearch:\n{}", text);
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
    @Story("get_kafka_cluster_logs with keywords via Elasticsearch")
    void testGetKafkaClusterLogsKeywordsViaElasticsearch() {
        Map<String, Object> args = Map.of(
            "clusterName", Constants.KAFKA_CLUSTER_NAME,
            "keywords", List.of("partition", "leader"),
            "tailLines", 50);

        mcpClient.when()
            .toolsCall("get_kafka_cluster_logs", args, response -> {
                JsonNode root = assertToolSuccess(response);
                String text = response.content().getFirst().asText().text();
                LOGGER.info("get_kafka_cluster_logs keywords via Elasticsearch (length={})", text.length());
                LOGGER.debug("get_kafka_cluster_logs keywords via Elasticsearch:\n{}", text);
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
    @Story("get_kafka_cluster_logs with no-match filter via Elasticsearch")
    void testGetKafkaClusterLogsNoMatchFilterViaElasticsearch() {
        Map<String, Object> args = Map.of(
            "clusterName", Constants.KAFKA_CLUSTER_NAME,
            "filter", "ZZZZNONEXISTENTZZZZ",
            "tailLines", 100);

        mcpClient.when()
            .toolsCall("get_kafka_cluster_logs", args, response -> {
                JsonNode root = assertToolSuccess(response);
                String text = response.content().getFirst().asText().text();
                LOGGER.info("get_kafka_cluster_logs no-match filter via Elasticsearch (length={})", text.length());
                LOGGER.debug("get_kafka_cluster_logs no-match filter via Elasticsearch:\n{}", text);
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
    @Story("get_kafka_cluster_logs for specific pod via Elasticsearch")
    void testGetKafkaClusterLogsSpecificPodViaElasticsearch() {
        String podName = Constants.KAFKA_CLUSTER_NAME + "-broker-np-0";
        Map<String, Object> args = Map.of(
            "clusterName", Constants.KAFKA_CLUSTER_NAME,
            "podNames", List.of(podName),
            "tailLines", 20);

        mcpClient.when()
            .toolsCall("get_kafka_cluster_logs", args, response -> {
                JsonNode root = assertToolSuccess(response);
                String text = response.content().getFirst().asText().text();
                LOGGER.info("get_kafka_cluster_logs specific pod via Elasticsearch (length={})", text.length());
                LOGGER.debug("get_kafka_cluster_logs specific pod via Elasticsearch:\n{}", text);
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
    @Story("get_kafka_cluster_logs with sinceMinutes via Elasticsearch")
    void testGetKafkaClusterLogsSinceMinutesViaElasticsearch() {
        Map<String, Object> args = Map.of(
            "clusterName", Constants.KAFKA_CLUSTER_NAME,
            "sinceMinutes", 60,
            "tailLines", 50);

        mcpClient.when()
            .toolsCall("get_kafka_cluster_logs", args, response -> {
                JsonNode root = assertToolSuccess(response);
                String text = response.content().getFirst().asText().text();
                LOGGER.info("get_kafka_cluster_logs sinceMinutes via Elasticsearch (length={})", text.length());
                LOGGER.debug("get_kafka_cluster_logs sinceMinutes via Elasticsearch:\n{}", text);
                assertClusterLogsResponse(root, Constants.KAFKA_CLUSTER_NAME);
                assertTrue(root.path("log_lines").asInt() > 0,
                    "Should have log lines within the last 60 minutes");
                assertFalse(root.path("logs").asText("").isEmpty(), "logs content should not be empty");
                assertTrue(root.path("logs").asText("").contains("=== Pod:"), "logs should contain pod log sections");
            })
            .thenAssertResults();
    }

    @Test
    @Story("get_kafka_cluster_logs with absolute time range via Elasticsearch")
    void testGetKafkaClusterLogsAbsoluteTimeRangeViaElasticsearch() {
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
                LOGGER.info("get_kafka_cluster_logs absolute time range via Elasticsearch (length={})", text.length());
                LOGGER.debug("get_kafka_cluster_logs absolute time range via Elasticsearch:\n{}", text);
                assertClusterLogsResponse(root, Constants.KAFKA_CLUSTER_NAME);
                assertTrue(root.path("log_lines").asInt() > 0,
                    "Should return data within the 30-minute window");
                assertFalse(root.path("logs").asText("").isEmpty(), "logs content should not be empty");
                assertTrue(root.path("logs").asText("").contains("=== Pod:"), "logs should contain pod log sections");
            })
            .thenAssertResults();
    }

    // ---- Strimzi Operator Logs via Elasticsearch ----

    @Test
    @Story("get_strimzi_operator_logs returns logs via Elasticsearch")
    void testGetStrimziOperatorLogsViaElasticsearch() {
        Map<String, Object> args = Map.of(
            "namespace", Constants.STRIMZI_NAMESPACE,
            "tailLines", 20);

        mcpClient.when()
            .toolsCall("get_strimzi_operator_logs", args, response -> {
                JsonNode root = assertToolSuccess(response);
                String text = response.content().getFirst().asText().text();
                LOGGER.info("get_strimzi_operator_logs via Elasticsearch (length={})", text.length());
                LOGGER.debug("get_strimzi_operator_logs via Elasticsearch:\n{}", text);
                assertOperatorLogsResponse(root, Constants.STRIMZI_NAMESPACE);
                assertTrue(root.path("log_lines").asInt() > 0, "Should have log lines from operator");
                assertFalse(root.path("logs").asText("").isEmpty(), "logs content should not be empty");
                assertTrue(root.path("logs").asText("").contains("=== Pod:"), "logs should contain pod log sections");
            })
            .thenAssertResults();
    }

    @Test
    @Story("get_strimzi_operator_logs with errors filter via Elasticsearch")
    void testGetStrimziOperatorLogsErrorFilterViaElasticsearch() {
        Map<String, Object> args = Map.of(
            "namespace", Constants.STRIMZI_NAMESPACE,
            "filter", "errors",
            "tailLines", 100);

        mcpClient.when()
            .toolsCall("get_strimzi_operator_logs", args, response -> {
                JsonNode root = assertToolSuccess(response);
                String text = response.content().getFirst().asText().text();
                LOGGER.info("get_strimzi_operator_logs ERROR filter via Elasticsearch (length={})", text.length());
                LOGGER.debug("get_strimzi_operator_logs ERROR filter via Elasticsearch:\n{}", text);
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
    @Story("get_kafka_cluster_logs returns error for non-existent cluster via Elasticsearch")
    void testGetKafkaClusterLogsNotFoundViaElasticsearch() {
        Map<String, Object> args = Map.of(
            "clusterName", "nonexistent-cluster-xyz",
            "tailLines", 20);

        mcpClient.when()
            .toolsCall("get_kafka_cluster_logs", args, response -> {
                String text = response.content().getFirst().asText().text();
                LOGGER.info("get_kafka_cluster_logs not found via Elasticsearch (length={})", text.length());
                LOGGER.debug("get_kafka_cluster_logs not found via Elasticsearch:\n{}", text);
                assertToolError(response, "No Kafka cluster");
            })
            .thenAssertResults();
    }

    // ---- Elasticsearch Discovery ----

    private static ElasticsearchTestConfig discoverElasticsearch() {
        if (Environment.ELASTICSEARCH_URL != null && !Environment.ELASTICSEARCH_URL.isBlank()) {
            String authMode = Environment.ELASTICSEARCH_AUTH_MODE != null ? Environment.ELASTICSEARCH_AUTH_MODE : "none";
            LOGGER.info("Using ELASTICSEARCH_URL override: {}", Environment.ELASTICSEARCH_URL);
            return new ElasticsearchTestConfig(Environment.ELASTICSEARCH_URL, authMode);
        }

        KubernetesClient client = KubeResourceManager.get().kubeClient().getClient();

        Service elasticsearchEck = client.services()
            .inNamespace("elasticsearch-logging")
            .withName("elasticsearch-es-http")
            .get();
        if (elasticsearchEck != null) {
            String authMode = Environment.ELASTICSEARCH_AUTH_MODE != null ? Environment.ELASTICSEARCH_AUTH_MODE : "basic";
            LOGGER.info("Discovered OpenShift ECK Elasticsearch in elasticsearch-logging namespace");
            return new ElasticsearchTestConfig(
                "https://elasticsearch-es-http.elasticsearch-logging.svc:9200", authMode);
        }

        Service elasticsearch = client.services()
            .inNamespace("elasticsearch")
            .withName("elasticsearch")
            .get();
        if (elasticsearch != null) {
            String authMode = Environment.ELASTICSEARCH_AUTH_MODE != null ? Environment.ELASTICSEARCH_AUTH_MODE : "none";
            LOGGER.info("Discovered standalone Elasticsearch service in elasticsearch namespace");
            return new ElasticsearchTestConfig(
                "http://elasticsearch.elasticsearch.svc.cluster.local:9200", authMode);
        }

        throw new IllegalStateException(
            "No Elasticsearch service found. Deploy Elasticsearch (dev/scripts/setup-elasticsearch-kind.sh) "
                + "or set ELASTICSEARCH_URL environment variable.");
    }
}
