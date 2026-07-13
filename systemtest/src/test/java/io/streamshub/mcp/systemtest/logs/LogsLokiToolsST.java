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
import java.util.Map;

import static io.streamshub.mcp.systemtest.TestTags.ACCEPTANCE;
import static io.streamshub.mcp.systemtest.TestTags.LOGS;
import static io.streamshub.mcp.systemtest.TestTags.LOKI;
import static io.streamshub.mcp.systemtest.TestTags.REGRESSION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @InjectResourceManager
    KubeResourceManager krm;

    @ClassNamespace(name = Constants.MCP_NAMESPACE)
    static Namespace mcpNamespace;

    @ClassNamespace(name = Constants.STRIMZI_NAMESPACE, labels = {"app=strimzi"})
    static Namespace strimziNamespace;

    @ClassNamespace(name = Constants.KAFKA_NAMESPACE, labels = {"app=strimzi"})
    static Namespace kafkaNamespace;

    private static McpAssured.McpStreamableTestClient mcpClient;

    private record LokiConfig(String url, String authMode, boolean trustAll) {
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

        LokiConfig lokiConfig = discoverLoki();
        LOGGER.info("Using Loki: url={}, auth={}, trustAll={}", lokiConfig.url(), lokiConfig.authMode(), lokiConfig.trustAll());

        if ("sa-token".equals(lokiConfig.authMode())) {
            McpServerSetup.deployMonitoringRbac(mcpNamespace.getMetadata().getName());
        }

        McpServerSetup.Builder builder = McpServerSetup.builder(mcpNamespace.getMetadata().getName())
            .withEnv("MCP_LOG_PROVIDER", "streamshub-loki")
            .withEnv("QUARKUS_REST_CLIENT_LOKI_URL", lokiConfig.url())
            .withEnv("MCP_LOG_LOKI_AUTH_MODE", lokiConfig.authMode());

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
                        })
                        .thenAssertResults();
                    return true;
                } catch (Exception | AssertionError ignored) {
                    LOGGER.info("Loki doesn't have logs yet (Promtail ingestion delay), retrying...");
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
                assertEquals(0, root.path("log_lines").asInt(),
                    "ERROR filter should return 0 log lines on healthy cluster");
                assertFalse(root.path("has_errors").asBoolean(), "Should have no errors");
                assertEquals(0, root.path("error_count").asInt(), "Error count should be 0");
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
                assertFalse(root.path("has_errors").asBoolean(), "Should have no errors");
                assertEquals(0, root.path("error_count").asInt(), "Error count should be 0");
                assertTrue(root.path("log_lines").asInt() > 0,
                    "Keywords filter should return matching log lines");
                String logs = root.path("logs").asText();
                assertTrue(logs.contains("partition") || logs.contains("leader"),
                    "Logs should contain keyword 'partition' or 'leader'");
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
                assertTrue(root.path("operator_pods").isArray()
                        && !root.path("operator_pods").isEmpty(),
                    "operator_pods should be a non-empty array");
                assertTrue(root.path("log_lines").isNumber(), "log_lines should be a number");
                assertTrue(root.path("log_lines").asInt() > 0, "Should have log lines from operator");
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
            .toolsCall("get_kafka_cluster_logs", args, response -> {
                String text = response.content().getFirst().asText().text();
                LOGGER.info("get_kafka_cluster_logs not found via Loki (length={})", text.length());
                LOGGER.debug("get_kafka_cluster_logs not found via Loki:\n{}", text);
                assertToolError(response, "No Kafka cluster");
            })
            .thenAssertResults();
    }

    // ---- Loki Discovery ----

    private static LokiConfig discoverLoki() {
        if (Environment.LOKI_URL != null && !Environment.LOKI_URL.isBlank()) {
            String authMode = Environment.LOKI_AUTH_MODE != null ? Environment.LOKI_AUTH_MODE : "none";
            LOGGER.info("Using LOKI_URL override: {}", Environment.LOKI_URL);
            return new LokiConfig(Environment.LOKI_URL, authMode, false);
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
                authMode, true);
        }

        Service loki = client.services()
            .inNamespace("logging")
            .withName("loki")
            .get();
        if (loki != null) {
            String authMode = Environment.LOKI_AUTH_MODE != null ? Environment.LOKI_AUTH_MODE : "none";
            LOGGER.info("Discovered standalone Loki service in logging namespace");
            return new LokiConfig(
                "http://loki.logging.svc.cluster.local:3100", authMode, false);
        }

        throw new IllegalStateException(
            "No Loki service found. Deploy Loki (dev/scripts/setup-loki-kind.sh) "
                + "or set LOKI_URL environment variable.");
    }
}
