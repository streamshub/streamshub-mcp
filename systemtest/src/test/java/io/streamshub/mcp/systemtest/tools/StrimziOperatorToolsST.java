/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.systemtest.tools;

import com.fasterxml.jackson.databind.JsonNode;
import io.fabric8.kubernetes.api.model.Namespace;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import io.quarkiverse.mcp.server.test.McpAssured;
import io.skodjob.kubetest4j.annotations.ClassNamespace;
import io.skodjob.kubetest4j.annotations.InjectResourceManager;
import io.skodjob.kubetest4j.resources.KubeResourceManager;
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

import java.util.Map;

import static io.streamshub.mcp.systemtest.TestTags.LOGS;
import static io.streamshub.mcp.systemtest.TestTags.REGRESSION;
import static io.streamshub.mcp.systemtest.TestTags.TOOLS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * System tests for Strimzi operator MCP tools.
 * Verifies operator discovery, status, and pod inspection against a real Strimzi deployment.
 */
@Epic("Strimzi MCP E2E")
@Feature("Strimzi Operator Tools")
@Tag(REGRESSION)
@Tag(TOOLS)
class StrimziOperatorToolsST extends AbstractST {

    private static final Logger LOGGER = LoggerFactory.getLogger(StrimziOperatorToolsST.class);

    @InjectResourceManager
    KubeResourceManager krm;

    @ClassNamespace(name = Constants.MCP_NAMESPACE)
    static Namespace mcpNamespace;

    @ClassNamespace(name = Constants.STRIMZI_NAMESPACE, labels = {"app=strimzi"})
    static Namespace strimziNamespace;

    @ClassNamespace(name = Constants.KAFKA_NAMESPACE, labels = {"app=strimzi"})
    static Namespace kafkaNamespace;

    private static McpAssured.McpStreamableTestClient mcpClient;

    StrimziOperatorToolsST() {
    }

    @BeforeAll
    void setup() {
        if (!Environment.SKIP_STRIMZI_INSTALL) {
            String kafkaNs = kafkaNamespace.getMetadata().getName();
            
            StrimziSetup.deploy(strimziNamespace.getMetadata().getName());
            
            krm.createOrUpdateResourceWithoutWait(
                KafkaNodePoolTemplates.controllerPool(kafkaNs, "controller-np",
                    Constants.KAFKA_CLUSTER_NAME, 3).build(),
                KafkaNodePoolTemplates.brokerPool(kafkaNs, "broker-np1",
                    Constants.KAFKA_CLUSTER_NAME, 3).build(),
                KafkaNodePoolTemplates.brokerPool(kafkaNs, "broker-np2",
                    Constants.KAFKA_CLUSTER_NAME, 3).build());
            
            krm.createOrUpdateResourceWithWait(
                KafkaTemplates.kafka(kafkaNs, Constants.KAFKA_CLUSTER_NAME, 3).build());
        }

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

    @Test
    @Story("list_strimzi_operators discovers the deployed operator")
    void testListStrimziOperators() {
        Map<String, Object> args = Map.of("namespace", Constants.STRIMZI_NAMESPACE);

        mcpClient.when()
            .toolsCall("list_strimzi_operators", args, response -> {
                JsonNode root = assertToolSuccess(response);

                String json = response.content().getFirst().asText().text();
                LOGGER.info("list_strimzi_operators response (length={})", json.length());
                LOGGER.debug("list_strimzi_operators response:\n{}", json);
                JsonNode operator = findByName(root, "strimzi-cluster-operator");
                assertNotNull(operator, "Should find 'strimzi-cluster-operator' in response");
                assertEquals(Constants.STRIMZI_NAMESPACE, operator.path("namespace").asText(),
                    "Namespace should match");
                assertTrue(operator.path("ready").asBoolean(), "Operator should be ready");
                assertEquals("HEALTHY", operator.path("status").asText(),
                    "Operator status should be HEALTHY");
                assertTrue(operator.path("replicas").asInt() > 0,
                    "Should have at least 1 replica");
                assertEquals(operator.path("replicas").asInt(),
                    operator.path("ready_replicas").asInt(),
                    "All replicas should be ready");
            })
            .thenAssertResults();
    }

    @Test
    @Story("get_strimzi_operator returns detailed operator info")
    void testGetStrimziOperator() {
        Map<String, Object> args = Map.of(
            "operatorName", "strimzi-cluster-operator",
            "namespace", Constants.STRIMZI_NAMESPACE);

        mcpClient.when()
            .toolsCall("get_strimzi_operator", args, response -> {
                JsonNode operator = assertToolSuccess(response);

                String json = response.content().getFirst().asText().text();
                LOGGER.info("get_strimzi_operator response (length={})", json.length());
                LOGGER.debug("get_strimzi_operator response:\n{}", json);
                assertEquals("strimzi-cluster-operator", operator.path("name").asText(),
                    "Operator name should match");
                assertEquals(Constants.STRIMZI_NAMESPACE, operator.path("namespace").asText(),
                    "Namespace should match");
                assertTrue(operator.path("ready").asBoolean(), "Operator should be ready");
                assertEquals("HEALTHY", operator.path("status").asText(),
                    "Operator should be HEALTHY");
                assertFalse(operator.path("version").isMissingNode(),
                    "Should have version");
                assertFalse(operator.path("version").asText().isEmpty(),
                    "Version should not be empty");
                assertFalse(operator.path("image").isMissingNode(),
                    "Should have image");
                assertTrue(operator.path("image").asText().contains("strimzi"),
                    "Image should contain 'strimzi'");
                assertFalse(operator.path("uptime_hours").isMissingNode(),
                    "Should have uptime_hours");
                assertEquals(operator.path("replicas").asInt(),
                    operator.path("ready_replicas").asInt(),
                    "All replicas should be ready");
            })
            .thenAssertResults();
    }

    @Test
    @Story("get_strimzi_operator returns error for non-existent operator")
    void testGetStrimziOperatorNotFound() {
        Map<String, Object> args = Map.of(
            "operatorName", "non-existent-operator",
            "namespace", Constants.STRIMZI_NAMESPACE);

        mcpClient.when()
            .toolsCall("get_strimzi_operator", args, response -> {
                assertToolError(response, "not found", "non-existent-operator");
            })
            .thenAssertResults();
    }

    @Test
    @Story("get_strimzi_operator_pod returns pod details")
    void testGetStrimziOperatorPod() {
        // First discover the operator pod name via operator logs
        Map<String, Object> logsArgs = Map.of("namespace", Constants.STRIMZI_NAMESPACE);

        mcpClient.when()
            .toolsCall("get_strimzi_operator_logs", logsArgs, response -> {
                JsonNode root = assertToolSuccess(response);

                String json = response.content().getFirst().asText().text();
                LOGGER.info("get_strimzi_operator_logs response (length={})", json.length());
                LOGGER.debug("get_strimzi_operator_logs response:\n{}", json);
                // operator_pods is a string array of pod names
                JsonNode operatorPods = root.path("operator_pods");
                assertTrue(operatorPods.isArray() && !operatorPods.isEmpty(),
                    "Should have at least one operator pod name");
                String podName = operatorPods.get(0).asText();
                assertFalse(podName.isEmpty(), "Pod name should not be empty");
                LOGGER.info("Discovered operator pod: {}", podName);
                // Now call get_strimzi_operator_pod with the discovered pod name
                mcpClient.when()
                    .toolsCall("get_strimzi_operator_pod",
                        Map.of("namespace", Constants.STRIMZI_NAMESPACE, "podName", podName),
                        podResponse -> {
                            JsonNode podResult = assertToolSuccess(podResponse);
                            String podJson = podResponse.content().getFirst().asText().text();
                            LOGGER.info("get_strimzi_operator_pod response (length={})",
                                podJson.length());
                            LOGGER.debug("get_strimzi_operator_pod response:\n{}",
                                podJson);
                            assertTrue(podResult.path("total_pods").asInt() > 0,
                                "Should have at least one pod");
                            assertTrue(podResult.path("ready_pods").asInt() > 0,
                                "Should have at least one ready pod");
                            assertEquals(0, podResult.path("failed_pods").asInt(),
                                "Should have no failed pods");
                            assertEquals("HEALTHY", podResult.path("health_status").asText(),
                                "Pod health should be HEALTHY");
                            JsonNode podsList = podResult.path("pods");
                            assertTrue(podsList.isArray() && !podsList.isEmpty(),
                                "Should have pod details");
                            JsonNode pod = podsList.get(0);
                            assertEquals("Running", pod.path("phase").asText(),
                                "Pod should be Running");
                            assertTrue(pod.path("ready").asBoolean(),
                                "Pod should be ready");
                            assertEquals(0, pod.path("restart_count").asInt(),
                                "Pod should have no restarts");
                        })
                    .thenAssertResults();
            })
            .thenAssertResults();
    }

    @Test
    @Story("get_strimzi_operator_logs returns logs filtered by ERROR level")
    @Tag(LOGS)
    void testGetStrimziOperatorLogsErrorFilter() {
        Map<String, Object> args = Map.of("filter", "ERROR", "tailLines", 100);

        mcpClient.when()
            .toolsCall("get_strimzi_operator_logs", args, response -> {
                JsonNode root = assertToolSuccess(response);

                LOGGER.info("get_strimzi_operator_logs (ERROR filter) response (length={})",
                    response.content().getFirst().asText().text().length());
                LOGGER.debug("get_strimzi_operator_logs (ERROR filter) response:\n{}",
                    response.content().getFirst().asText().text());
                assertTrue(root.path("operator_pods").isArray()
                        && !root.path("operator_pods").isEmpty(),
                    "operator_pods should be a non-empty array");
                assertTrue(root.path("log_lines").isNumber(),
                    "log_lines should be a number");
                assertFalse(root.path("has_errors").asBoolean(), "Should have no errors");
                assertEquals(0, root.path("error_count").asInt(), "Error count should be 0");
                assertEquals(0, root.path("log_lines").asInt(), "Should have 0 log lines with error filter");
                assertTrue(root.path("message").asText().contains("no errors found"), "Message should indicate no errors found");
            })
            .thenAssertResults();
    }

    @Test
    @Story("get_strimzi_events returns events for a Kafka resource")
    void testGetStrimziEventsKafka() {
        String kafkaNs = kafkaNamespace.getMetadata().getName();
        Map<String, Object> args = Map.of(
            "resourceName", Constants.KAFKA_CLUSTER_NAME,
            "resourceKind", "Kafka",
            "namespace", kafkaNs);

        mcpClient.when()
            .toolsCall("get_strimzi_events", args, response -> {
                JsonNode root = assertToolSuccess(response);

                LOGGER.info("get_strimzi_events (Kafka) response (length={})",
                    response.content().getFirst().asText().text().length());
                LOGGER.debug("get_strimzi_events (Kafka) response:\n{}",
                    response.content().getFirst().asText().text());
                assertEventsResponse(root, Constants.KAFKA_CLUSTER_NAME, kafkaNs);
                assertTrue(root.path("total_events").asInt() > 0, "Should have events");
                JsonNode resources = root.path("resources");
                assertTrue(resources.isArray() && resources.size() > 0, "Should have resource groups");
                assertTrue(root.path("message").asText().contains("events"), "Message should mention events");
            })
            .thenAssertResults();
    }

    @Test
    @Story("get_strimzi_events returns time-scoped events for a Kafka resource")
    void testGetStrimziEventsTimeScoped() {
        String kafkaNs = kafkaNamespace.getMetadata().getName();
        Map<String, Object> args = Map.of(
            "resourceName", Constants.KAFKA_CLUSTER_NAME,
            "resourceKind", "Kafka",
            "namespace", kafkaNs,
            "sinceMinutes", 60);

        mcpClient.when()
            .toolsCall("get_strimzi_events", args, response -> {
                JsonNode root = assertToolSuccess(response);

                LOGGER.info("get_strimzi_events (time-scoped) response (length={})",
                    response.content().getFirst().asText().text().length());
                LOGGER.debug("get_strimzi_events (time-scoped) response:\n{}",
                    response.content().getFirst().asText().text());
                assertEventsResponse(root, Constants.KAFKA_CLUSTER_NAME, kafkaNs);
                assertTrue(root.path("total_events").asInt() >= 0, "total_events should be non-negative");
                assertTrue(root.path("resources").isArray(), "resources should be an array");
            })
            .thenAssertResults();
    }

    @Test
    @Story("get_strimzi_events returns events for StrimziOperator resource")
    void testGetStrimziEventsOperator() {
        String strimziNs = strimziNamespace.getMetadata().getName();
        Map<String, Object> args = Map.of(
            "resourceName", "strimzi-cluster-operator",
            "resourceKind", "StrimziOperator",
            "namespace", strimziNs);

        mcpClient.when()
            .toolsCall("get_strimzi_events", args, response -> {
                JsonNode root = assertToolSuccess(response);

                LOGGER.info("get_strimzi_events (StrimziOperator) response (length={})",
                    response.content().getFirst().asText().text().length());
                LOGGER.debug("get_strimzi_events (StrimziOperator) response:\n{}",
                    response.content().getFirst().asText().text());
                assertEventsResponse(root, "strimzi-cluster-operator", strimziNs);
                assertTrue(root.path("total_events").asInt() > 0, "Should have events");
                JsonNode resources = root.path("resources");
                assertTrue(resources.isArray() && resources.size() > 0, "Should have resource groups");
            })
            .thenAssertResults();
    }

    @Test
    @Story("get_strimzi_events returns error for non-existent resource")
    void testGetStrimziEventsNonexistent() {
        Map<String, Object> args = Map.of(
            "resourceName", "nonexistent-xyz",
            "resourceKind", "Kafka",
            "namespace", kafkaNamespace.getMetadata().getName());

        mcpClient.when()
            .toolsCall("get_strimzi_events", args, response -> {
                assertToolError(response, "not found", "nonexistent-xyz");
            })
            .thenAssertResults();
    }

    @Test
    @Story("get_strimzi_events returns error for invalid resource kind")
    void testGetStrimziEventsInvalidKind() {
        Map<String, Object> args = Map.of(
            "resourceName", Constants.KAFKA_CLUSTER_NAME,
            "resourceKind", "InvalidKind");

        mcpClient.when()
            .toolsCall("get_strimzi_events", args, response -> {
                assertToolError(response, "resource_kind", "Supported values");
            })
            .thenAssertResults();
    }
}
