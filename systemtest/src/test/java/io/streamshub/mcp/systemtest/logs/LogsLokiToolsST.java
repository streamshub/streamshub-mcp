/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.systemtest.logs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.fabric8.kubernetes.api.model.Namespace;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.LocalPortForward;
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

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import static io.streamshub.mcp.systemtest.TestTags.ACCEPTANCE;
import static io.streamshub.mcp.systemtest.TestTags.LOGS;
import static io.streamshub.mcp.systemtest.TestTags.LOKI;
import static io.streamshub.mcp.systemtest.TestTags.REGRESSION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * System tests for Loki-backed log collection MCP tools.
 * Deploys a Kafka cluster, configures the MCP server to use the Loki log
 * provider, then verifies that log retrieval tools return well-formed
 * responses sourced from Loki via LogQL queries.
 */
@Epic("Strimzi MCP E2E")
@Feature("Loki Log Collection")
@Tag(ACCEPTANCE)
@Tag(REGRESSION)
@Tag(LOGS)
@Tag(LOKI)
class LogsLokiToolsST extends AbstractST {

    private static final Logger LOGGER = LoggerFactory.getLogger(LogsLokiToolsST.class);

    /**
     * Local copy of {@code LogFilterUtils.WARNINGS_REGEX} from the common module
     * (the systemtest module does not depend on common). Keep the two in sync.
     */
    private static final Pattern WARNINGS = Pattern.compile(
        "(?i:\\b(?:ERROR|FATAL|WARN|WARNING)\\b)|\\b\\w*(?:Exception|Error)\\b");

    private static final String POD_HEADER = "=== Pod:";
    /** Start of the note that a filtered Loki search without a time range covered only filter-lookback. */
    private static final String SCOPE_NOTE_PREFIX = "Loki searched only the last ";
    private static final String STANDALONE_LOKI_URL = "http://loki.logging.svc.cluster.local:3100";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @InjectResourceManager
    KubeResourceManager krm;

    @ClassNamespace(name = Constants.MCP_NAMESPACE)
    static Namespace mcpNamespace;

    @ClassNamespace(name = Constants.STRIMZI_NAMESPACE, labels = {"app=strimzi"})
    static Namespace strimziNamespace;

    @ClassNamespace(name = Constants.KAFKA_NAMESPACE, labels = {"app=strimzi"})
    static Namespace kafkaNamespace;

    private static McpAssured.McpStreamableTestClient mcpClient;
    private static LokiConfig lokiConfig;

    private record LokiConfig(String url, String authMode, boolean trustAll,
                              String namespaceLabel, String podLabel, String containerLabel) {
    }

    LogsLokiToolsST() {
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

        lokiConfig = discoverLoki();
        LOGGER.info("Using Loki: url={}, auth={}, trustAll={}", lokiConfig.url(), lokiConfig.authMode(), lokiConfig.trustAll());

        if ("sa-token".equals(lokiConfig.authMode())) {
            McpServerSetup.deployLokiRbac(mcpNamespace.getMetadata().getName());
        }

        McpServerSetup.Builder builder = McpServerSetup.builder(mcpNamespace.getMetadata().getName())
            .withEnv("MCP_LOG_PROVIDER", "streamshub-loki")
            .withEnv("QUARKUS_REST_CLIENT_LOKI_URL", lokiConfig.url())
            .withEnv("MCP_LOG_LOKI_AUTH_MODE", lokiConfig.authMode())
            .withEnv("MCP_LOG_LOKI_LABEL_NAMESPACE", lokiConfig.namespaceLabel())
            .withEnv("MCP_LOG_LOKI_LABEL_POD", lokiConfig.podLabel())
            .withEnv("MCP_LOG_LOKI_LABEL_CONTAINER", lokiConfig.containerLabel())
            // OpenShift Logging needs the unwrap; on the standalone Loki the pushed ViaQ records need it,
            // and the plain-text lines from the collector must pass through it unchanged.
            .withEnv("MCP_LOG_LOKI_UNWRAP_JSON", "true");

        if (lokiConfig.trustAll()) {
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

    // ---- Kafka Cluster Logs via Loki ----

    @Test
    @Story("get_kafka_cluster_logs returns logs from Loki")
    void testGetKafkaClusterLogsViaLoki() {
        Map<String, Object> args = Map.of(
            "clusterName", Constants.KAFKA_CLUSTER_NAME,
            "tailLines", 20);

        Wait.until("Loki to return log data for Kafka cluster",
            Constants.KAFKA_READY_POLL_MS, Constants.KAFKA_READY_TIMEOUT_MS, () -> {
                try {
                    mcpClient.when()
                        .toolsCall("get_kafka_cluster_logs", args, response -> {
                            JsonNode root = assertToolSuccess(response);
                            String text = response.content().getFirst().asText().text();
                            LOGGER.info("get_kafka_cluster_logs via Loki response (length={})", text.length());
                            LOGGER.debug("get_kafka_cluster_logs via Loki response:\n{}", text);
                            assertClusterLogsResponse(root, Constants.KAFKA_CLUSTER_NAME);
                            assertTrue(root.path("log_lines").asInt() > 0, "Should have log lines from Loki");
                            assertEquals(root.path("has_errors").asBoolean(), root.path("error_count").asInt() > 0,
                                "has_errors should be consistent with error_count");
                            assertTrue(root.path("has_more").asBoolean(), "Should indicate more logs are available");
                            assertFalse(root.path("logs").asText("").isEmpty(), "logs content should not be empty");
                            assertTrue(root.path("logs").asText("").contains("=== Pod:"), "logs should contain pod log sections");
                            List<String> lines = logLines(root);
                            lines.forEach(line -> {
                                assertFalse(line.startsWith("{\""),
                                    "Loki lines should be log messages, not JSON records: " + line);
                                assertFalse(line.contains("<no value>"),
                                    "Plain-text lines must pass the unwrap stage unchanged: " + line);
                            });
                            assertTrue(lines.stream().anyMatch(line -> !line.isBlank()),
                                "At least one returned log line should be non-blank");
                        })
                        .thenAssertResults();
                    return true;
                } catch (Exception | AssertionError ignored) {
                    LOGGER.info("Loki doesn't have logs yet, retrying...");
                    return false;
                }
            }
        );
    }

    @Test
    @Story("get_kafka_cluster_logs narrows to a single container via Loki")
    void testGetKafkaClusterLogsContainerViaLoki() {
        Map<String, Object> args = Map.of(
            "clusterName", Constants.KAFKA_CLUSTER_NAME,
            "container", "kafka",
            "tailLines", 20);

        Wait.until("Loki to return log data for Kafka cluster with container selector",
            Constants.KAFKA_READY_POLL_MS, Constants.KAFKA_READY_TIMEOUT_MS, () -> {
                try {
                    mcpClient.when()
                        .toolsCall("get_kafka_cluster_logs", args, response -> {
                            JsonNode root = assertToolSuccess(response);
                            String text = response.content().getFirst().asText().text();
                            LOGGER.info("get_kafka_cluster_logs container selector via Loki response (length={})", text.length());
                            assertClusterLogsResponse(root, Constants.KAFKA_CLUSTER_NAME);
                            assertTrue(root.path("log_lines").asInt() > 0, "Should have log lines from the 'kafka' container");
                            assertFalse(root.path("logs").asText("").isEmpty(), "logs content should not be empty");
                            assertTrue(root.path("logs").asText("").contains("=== Pod:"), "logs should contain pod log sections");
                            assertTrue(root.path("warnings").isMissingNode() || root.path("warnings").isEmpty(),
                                "A container filter that returns lines should not add a warning: " + root.path("warnings"));
                        })
                        .thenAssertResults();
                    return true;
                } catch (Exception | AssertionError ignored) {
                    LOGGER.info("Loki doesn't have logs yet, retrying...");
                    return false;
                }
            }
        );
    }

    @Test
    @Story("get_kafka_cluster_logs with errors filter via Loki")
    void testGetKafkaClusterLogsErrorFilterViaLoki() {
        Map<String, Object> args = Map.of(
            "clusterName", Constants.KAFKA_CLUSTER_NAME,
            "filter", "errors",
            "tailLines", 100,
            "sinceMinutes", 60);

        mcpClient.when()
            .toolsCall("get_kafka_cluster_logs", args, response -> {
                JsonNode root = assertToolSuccess(response);
                String text = response.content().getFirst().asText().text();
                LOGGER.info("get_kafka_cluster_logs ERROR filter via Loki (length={})", text.length());
                LOGGER.debug("get_kafka_cluster_logs ERROR filter via Loki:\n{}", text);
                assertClusterLogsResponse(root, Constants.KAFKA_CLUSTER_NAME);
                int logLines = root.path("log_lines").asInt();
                int errorCount = root.path("error_count").asInt();
                assertTrue(errorCount <= logLines,
                    "error_count (" + errorCount + ") should not exceed log_lines (" + logLines + ")");
                assertEquals(root.path("has_errors").asBoolean(), errorCount > 0,
                    "has_errors should be consistent with error_count");
                assertTrue(warnings(root).stream().noneMatch(w -> w.startsWith(SCOPE_NOTE_PREFIX)),
                    "A filtered search with sinceMinutes should not add the search scope note: " + root.path("warnings"));
            })
            .thenAssertResults();
    }

    @Test
    @Story("get_kafka_cluster_logs with warnings filter via Loki")
    void testGetKafkaClusterLogsWarningsFilterViaLoki() {
        Map<String, Object> args = Map.of(
            "clusterName", Constants.KAFKA_CLUSTER_NAME,
            "filter", "warnings",
            "tailLines", 100,
            "sinceMinutes", 60);

        mcpClient.when()
            .toolsCall("get_kafka_cluster_logs", args, response -> {
                JsonNode root = assertToolSuccess(response);
                String text = response.content().getFirst().asText().text();
                LOGGER.info("get_kafka_cluster_logs WARNINGS filter via Loki (length={})", text.length());
                LOGGER.debug("get_kafka_cluster_logs WARNINGS filter via Loki:\n{}", text);
                assertClusterLogsResponse(root, Constants.KAFKA_CLUSTER_NAME);
                int logLines = root.path("log_lines").asInt();
                int errorCount = root.path("error_count").asInt();
                assertTrue(errorCount <= logLines,
                    "error_count (" + errorCount + ") should not exceed log_lines (" + logLines + ")");
                if (logLines == 0) {
                    assertFalse(root.path("has_errors").asBoolean(), "Should have no errors when no lines");
                    assertEquals(0, errorCount, "Error count should be 0 when no lines");
                }
                logLines(root).forEach(line -> assertTrue(WARNINGS.matcher(line).find(),
                    "warnings filter returned a line without a warning or error: " + line));
            })
            .thenAssertResults();
    }

    @Test
    @Story("Loki reduces ViaQ JSON records to their message before filtering")
    void testViaqRecordIsUnwrappedBeforeFiltering() throws IOException {
        assumeTrue(STANDALONE_LOKI_URL.equals(lokiConfig.url()),
            "Needs the standalone Loki from setup-loki-kind.sh (the LokiStack gateway needs write credentials)");

        String kafkaNs = kafkaNamespace.getMetadata().getName();
        KubernetesClient client = KubeResourceManager.get().kubeClient().getClient();
        String podName = client.pods().inNamespace(kafkaNs)
            .withLabel("strimzi.io/cluster", Constants.KAFKA_CLUSTER_NAME)
            .list().getItems().stream()
            .map(pod -> pod.getMetadata().getName())
            .filter(name -> name.contains("broker"))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("No broker pod for " + Constants.KAFKA_CLUSTER_NAME));

        String marker = "mcp-st-" + UUID.randomUUID();
        String warnMessage = "2026-11-13 11:20:00,000 WARN [" + marker
            + "] Disk usage above 90% (kafka.log.LogManager) [kafka-scheduler-1]";
        String infoMessage = "2026-11-13 11:20:00,000 INFO [" + marker
            + "] Rolled new log segment (kafka.log.LogManager) [kafka-scheduler-1]";
        // The middle record has an empty message (a blank log line). It must not come back as the
        // whole JSON record, which carries the marker in a pod label and the
        // pod-security.kubernetes.io/warn namespace label.
        pushViaqRecords(kafkaNs, podName, marker, List.of(warnMessage, "", infoMessage));

        Map<String, Object> args = new HashMap<>(Map.of(
            "clusterName", Constants.KAFKA_CLUSTER_NAME,
            "podNames", List.of(podName),
            "keywords", List.of(marker),
            "sinceMinutes", 10,
            "tailLines", 10));

        Wait.until("Loki to return the pushed ViaQ records as messages",
            Constants.KAFKA_READY_POLL_MS, Constants.KAFKA_READY_TIMEOUT_MS, () -> {
                try {
                    mcpClient.when()
                        .toolsCall("get_kafka_cluster_logs", args, response -> {
                            JsonNode root = assertToolSuccess(response);
                            assertEquals(List.of(warnMessage, infoMessage), logLines(root),
                                "ViaQ records should come back as their message only");
                        })
                        .thenAssertResults();
                    return true;
                } catch (Exception | AssertionError ignored) {
                    LOGGER.info("Loki doesn't return the pushed records yet, retrying...");
                    return false;
                }
            });

        args.put("filter", "warnings");
        mcpClient.when()
            .toolsCall("get_kafka_cluster_logs", args, response -> {
                JsonNode root = assertToolSuccess(response);
                assertEquals(List.of(warnMessage), logLines(root),
                    "warnings filter should match the message, not the pod-security.kubernetes.io/warn label");
            })
            .thenAssertResults();
    }

    @Test
    @Story("get_kafka_cluster_logs with keywords via Loki")
    void testGetKafkaClusterLogsKeywordsViaLoki() {
        Map<String, Object> args = Map.of(
            "clusterName", Constants.KAFKA_CLUSTER_NAME,
            "keywords", List.of("partition", "leader"),
            "tailLines", 50);

        mcpClient.when()
            .toolsCall("get_kafka_cluster_logs", args, response -> {
                JsonNode root = assertToolSuccess(response);
                String text = response.content().getFirst().asText().text();
                LOGGER.info("get_kafka_cluster_logs keywords via Loki (length={})", text.length());
                LOGGER.debug("get_kafka_cluster_logs keywords via Loki:\n{}", text);
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
                assertEquals(1, warnings(root).stream().filter(w -> w.startsWith(SCOPE_NOTE_PREFIX)).count(),
                    "A keywords search without a time range should state its search window once: "
                        + root.path("warnings"));
            })
            .thenAssertResults();
    }

    @Test
    @Story("get_kafka_cluster_logs with no-match filter via Loki")
    void testGetKafkaClusterLogsNoMatchFilterViaLoki() {
        Map<String, Object> args = Map.of(
            "clusterName", Constants.KAFKA_CLUSTER_NAME,
            "filter", "ZZZZNONEXISTENTZZZZ",
            "tailLines", 100);

        mcpClient.when()
            .toolsCall("get_kafka_cluster_logs", args, response -> {
                JsonNode root = assertToolSuccess(response);
                String text = response.content().getFirst().asText().text();
                LOGGER.info("get_kafka_cluster_logs no-match filter via Loki (length={})", text.length());
                LOGGER.debug("get_kafka_cluster_logs no-match filter via Loki:\n{}", text);
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
    @Story("get_kafka_cluster_logs for specific pod via Loki")
    void testGetKafkaClusterLogsSpecificPodViaLoki() {
        String podName = Constants.KAFKA_CLUSTER_NAME + "-broker-np-0";
        Map<String, Object> args = Map.of(
            "clusterName", Constants.KAFKA_CLUSTER_NAME,
            "podNames", List.of(podName),
            "tailLines", 20);

        mcpClient.when()
            .toolsCall("get_kafka_cluster_logs", args, response -> {
                JsonNode root = assertToolSuccess(response);
                String text = response.content().getFirst().asText().text();
                LOGGER.info("get_kafka_cluster_logs specific pod via Loki (length={})", text.length());
                LOGGER.debug("get_kafka_cluster_logs specific pod via Loki:\n{}", text);
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
    @Story("get_kafka_cluster_logs with sinceMinutes via Loki")
    void testGetKafkaClusterLogsSinceMinutesViaLoki() {
        Map<String, Object> args = Map.of(
            "clusterName", Constants.KAFKA_CLUSTER_NAME,
            "sinceMinutes", 60,
            "tailLines", 50);

        mcpClient.when()
            .toolsCall("get_kafka_cluster_logs", args, response -> {
                JsonNode root = assertToolSuccess(response);
                String text = response.content().getFirst().asText().text();
                LOGGER.info("get_kafka_cluster_logs sinceMinutes via Loki (length={})", text.length());
                LOGGER.debug("get_kafka_cluster_logs sinceMinutes via Loki:\n{}", text);
                assertClusterLogsResponse(root, Constants.KAFKA_CLUSTER_NAME);
                assertTrue(root.path("log_lines").asInt() > 0,
                    "Should have log lines within the last 60 minutes");
                assertFalse(root.path("logs").asText("").isEmpty(), "logs content should not be empty");
                assertTrue(root.path("logs").asText("").contains("=== Pod:"), "logs should contain pod log sections");
            })
            .thenAssertResults();
    }

    @Test
    @Story("get_kafka_cluster_logs with absolute time range via Loki")
    void testGetKafkaClusterLogsAbsoluteTimeRangeViaLoki() {
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
                LOGGER.info("get_kafka_cluster_logs absolute time range via Loki (length={})", text.length());
                LOGGER.debug("get_kafka_cluster_logs absolute time range via Loki:\n{}", text);
                assertClusterLogsResponse(root, Constants.KAFKA_CLUSTER_NAME);
                assertTrue(root.path("log_lines").asInt() > 0,
                    "Should return data within the 30-minute window");
                assertFalse(root.path("logs").asText("").isEmpty(), "logs content should not be empty");
                assertTrue(root.path("logs").asText("").contains("=== Pod:"), "logs should contain pod log sections");
            })
            .thenAssertResults();
    }

    // ---- Strimzi Operator Logs via Loki ----

    @Test
    @Story("get_strimzi_operator_logs returns logs via Loki")
    void testGetStrimziOperatorLogsViaLoki() {
        Map<String, Object> args = Map.of(
            "namespace", Constants.STRIMZI_NAMESPACE,
            "tailLines", 20);

        mcpClient.when()
            .toolsCall("get_strimzi_operator_logs", args, response -> {
                JsonNode root = assertToolSuccess(response);
                String text = response.content().getFirst().asText().text();
                LOGGER.info("get_strimzi_operator_logs via Loki (length={})", text.length());
                LOGGER.debug("get_strimzi_operator_logs via Loki:\n{}", text);
                assertOperatorLogsResponse(root, Constants.STRIMZI_NAMESPACE);
                assertTrue(root.path("log_lines").asInt() > 0, "Should have log lines from operator");
                assertFalse(root.path("logs").asText("").isEmpty(), "logs content should not be empty");
                assertTrue(root.path("logs").asText("").contains("=== Pod:"), "logs should contain pod log sections");
            })
            .thenAssertResults();
    }

    @Test
    @Story("get_strimzi_operator_logs with errors filter via Loki")
    void testGetStrimziOperatorLogsErrorFilterViaLoki() {
        Map<String, Object> args = Map.of(
            "namespace", Constants.STRIMZI_NAMESPACE,
            "filter", "errors",
            "tailLines", 100);

        mcpClient.when()
            .toolsCall("get_strimzi_operator_logs", args, response -> {
                JsonNode root = assertToolSuccess(response);
                String text = response.content().getFirst().asText().text();
                LOGGER.info("get_strimzi_operator_logs ERROR filter via Loki (length={})", text.length());
                LOGGER.debug("get_strimzi_operator_logs ERROR filter via Loki:\n{}", text);
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
    @Story("get_kafka_cluster_logs returns error for non-existent cluster via Loki")
    void testGetKafkaClusterLogsNotFoundViaLoki() {
        Map<String, Object> args = Map.of(
            "clusterName", "nonexistent-cluster-xyz",
            "tailLines", 20);

        mcpClient.when()
            .toolsCall("get_kafka_cluster_logs")
            .withArguments(args)
            .withErrorAssert(error -> {
                LOGGER.info("get_kafka_cluster_logs not found via Loki (length={})", error.message().length());
                LOGGER.debug("get_kafka_cluster_logs not found via Loki:\n{}", error.message());
                assertToolProtocolError(error, -32002, "RESOURCE_NOT_FOUND", "not found", "nonexistent-cluster-xyz");
            })
            .send()
            .thenAssertResults();
    }

    // ---- Helpers ----

    /**
     * The returned log lines without the {@code === Pod:} section headers.
     *
     * @param root the tool response
     * @return the log lines
     */
    private static List<String> logLines(final JsonNode root) {
        return Arrays.stream(root.path("logs").asText("").split("\n"))
            .filter(line -> !line.startsWith(POD_HEADER))
            .filter(line -> !line.isEmpty())
            .toList();
    }

    private static List<String> warnings(final JsonNode root) {
        List<String> warnings = new ArrayList<>();
        root.path("warnings").forEach(warning -> warnings.add(warning.asText()));
        return warnings;
    }

    /**
     * Push OpenShift Logging (ViaQ) shaped records, one per message, into the standalone Loki
     * through a port-forward to {@code svc/loki} in the {@code logging} namespace.
     *
     * @param namespace the Kubernetes namespace of the pod
     * @param podName   the pod the records belong to
     * @param marker    value of the {@code mcp-st-marker} pod label in every record
     * @param messages  the log messages, oldest first (an empty string is a blank log line)
     * @throws IOException when the fixture, the port-forward or the push fails
     */
    private static void pushViaqRecords(final String namespace, final String podName, final String marker,
                                        final List<String> messages) throws IOException {
        JsonNode template;
        try (InputStream in = LogsLokiToolsST.class.getClassLoader().getResourceAsStream("loki/viaq-record.json")) {
            if (in == null) {
                throw new IllegalStateException("Missing test resource loki/viaq-record.json");
            }
            template = MAPPER.readTree(in);
        }

        ObjectNode body = MAPPER.createObjectNode();
        ObjectNode stream = body.putArray("streams").addObject();
        stream.putObject("stream")
            .put("namespace", namespace)
            .put("pod", podName)
            .put("container", "kafka");
        ArrayNode values = stream.putArray("values");
        Instant now = Instant.now();
        for (int i = 0; i < messages.size(); i++) {
            Instant timestamp = now.plusMillis(i);
            ObjectNode record = template.deepCopy();
            record.put("message", messages.get(i));
            record.put("@timestamp", timestamp.toString());
            ObjectNode kubernetes = (ObjectNode) record.path("kubernetes");
            kubernetes.put("namespace_name", namespace);
            kubernetes.put("pod_name", podName);
            ((ObjectNode) kubernetes.path("labels")).put("mcp-st-marker", marker);
            values.addArray()
                .add(String.valueOf(timestamp.getEpochSecond() * 1_000_000_000L + timestamp.getNano()))
                .add(MAPPER.writeValueAsString(record));
        }

        KubernetesClient client = KubeResourceManager.get().kubeClient().getClient();
        try (LocalPortForward forward = client.services().inNamespace("logging").withName("loki").portForward(3100)) {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + forward.getLocalPort() + "/loki/api/v1/push"))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
                .build();
            HttpResponse<String> response = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(204, response.statusCode(), "Loki push failed: " + response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while pushing records to Loki", e);
        }
    }

    // ---- Loki Discovery ----

    private static LokiConfig discoverLoki() {
        if (Environment.LOKI_URL != null && !Environment.LOKI_URL.isBlank()) {
            String authMode = Environment.LOKI_AUTH_MODE != null ? Environment.LOKI_AUTH_MODE : "none";
            LOGGER.info("Using LOKI_URL override: {}", Environment.LOKI_URL);
            return new LokiConfig(Environment.LOKI_URL, authMode, false, "namespace", "pod", "container");
        }

        KubernetesClient client = KubeResourceManager.get().kubeClient().getClient();

        Service lokiGateway = client.services()
            .inNamespace("openshift-logging")
            .withName("logging-loki-gateway-http")
            .get();
        if (lokiGateway != null) {
            String authMode = Environment.LOKI_AUTH_MODE != null ? Environment.LOKI_AUTH_MODE : "sa-token";
            LOGGER.info("Discovered OpenShift Logging LokiStack gateway in openshift-logging namespace");
            return new LokiConfig(
                "https://logging-loki-gateway-http.openshift-logging.svc:8080/api/logs/v1/application",
                authMode, true, "kubernetes_namespace_name", "kubernetes_pod_name", "kubernetes_container_name");
        }

        Service loki = client.services()
            .inNamespace("logging")
            .withName("loki")
            .get();
        if (loki != null) {
            String authMode = Environment.LOKI_AUTH_MODE != null ? Environment.LOKI_AUTH_MODE : "none";
            LOGGER.info("Discovered standalone Loki service in logging namespace");
            return new LokiConfig(STANDALONE_LOKI_URL, authMode, false, "namespace", "pod", "container");
        }

        throw new IllegalStateException(
            "No Loki service found. Deploy Loki (dev/scripts/setup-loki-kind.sh) "
                + "or set LOKI_URL environment variable.");
    }
}
