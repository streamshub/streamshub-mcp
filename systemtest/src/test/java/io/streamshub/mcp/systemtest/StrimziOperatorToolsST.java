/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.systemtest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.kubernetes.api.model.Namespace;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import io.quarkiverse.mcp.server.test.McpAssured;
import io.skodjob.kubetest4j.annotations.ClassNamespace;
import io.skodjob.kubetest4j.annotations.CleanupStrategy;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * System tests for Strimzi operator MCP tools.
 * Verifies operator discovery, status, and pod inspection against a real Strimzi deployment.
 */
@KubernetesTest(cleanup = CleanupStrategy.AUTOMATIC, collectLogs = true)
@DisplayName("Strimzi Operator MCP Tools")
@Epic("Strimzi MCP E2E")
@Feature("Strimzi Operator Tools")
class StrimziOperatorToolsST extends AbstractST {

    private static final Logger LOGGER = LoggerFactory.getLogger(StrimziOperatorToolsST.class);
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

    StrimziOperatorToolsST() {
    }

    @BeforeAll
    void setup() {
        if (!Environment.SKIP_STRIMZI_INSTALL) {
            String kafkaNs = kafkaNamespace.getMetadata().getName();

            StrimziSetup.deploy(strimziNamespace.getMetadata().getName());

            KafkaTemplates.deployMetricsConfigMap(kafkaNs);

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
    @DisplayName("list_strimzi_operators discovers the deployed operator")
    @Story("List Strimzi Operators")
    void testListStrimziOperators() {
        Map<String, Object> args = Map.of("namespace", Constants.STRIMZI_NAMESPACE);
        mcpClient.when()
            .toolsCall("list_strimzi_operators", args, response -> {
                assertFalse(response.isError(), "list_strimzi_operators should not return error");
                assertFalse(response.content().isEmpty(), "Should return at least one content entry");

                String json = response.content().getFirst().asText().text();
                LOGGER.info("list_strimzi_operators response:\n{}", json);

                JsonNode root = parseJson(json);

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
    @DisplayName("get_strimzi_operator returns detailed operator info")
    @Story("Get Strimzi Operator")
    void testGetStrimziOperator() {
        Map<String, Object> args = Map.of(
            "operatorName", "strimzi-cluster-operator",
            "namespace", Constants.STRIMZI_NAMESPACE);
        mcpClient.when()
            .toolsCall("get_strimzi_operator", args, response -> {
                assertFalse(response.isError(), "get_strimzi_operator should not return error");

                String json = response.content().getFirst().asText().text();
                LOGGER.info("get_strimzi_operator response:\n{}", json);

                JsonNode operator = parseJson(json);

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
    @DisplayName("get_strimzi_operator returns error for non-existent operator")
    @Story("Get Strimzi Operator")
    void testGetStrimziOperatorNotFound() {
        Map<String, Object> args = Map.of(
            "operatorName", "non-existent-operator",
            "namespace", Constants.STRIMZI_NAMESPACE);
        mcpClient.when()
            .toolsCall("get_strimzi_operator", args, response -> {
                assertTrue(response.isError(),
                    "Should return error for non-existent operator");

                String text = response.content().getFirst().asText().text();
                LOGGER.info("get_strimzi_operator error response: {}", text);
                assertTrue(text.toLowerCase(Locale.ROOT).contains("not found"),
                    "Error should mention 'not found'");
            })
            .thenAssertResults();
    }

    @Test
    @DisplayName("get_strimzi_operator_pod returns pod details")
    @Story("Get Strimzi Operator Pod")
    void testGetStrimziOperatorPod() {
        // First discover the operator pod name via operator logs
        Map<String, Object> logsArgs = Map.of("namespace", Constants.STRIMZI_NAMESPACE);
        mcpClient.when()
            .toolsCall("get_strimzi_operator_logs", logsArgs, response -> {
                assertFalse(response.isError(), "get_strimzi_operator_logs should not return error");

                String json = response.content().getFirst().asText().text();
                LOGGER.info("get_strimzi_operator_logs response (length={}):\n{}", json.length(), json);
                JsonNode root = parseJson(json);

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
                            assertFalse(podResponse.isError(),
                                "get_strimzi_operator_pod should not return error");

                            String podJson = podResponse.content().getFirst().asText().text();
                            LOGGER.info("get_strimzi_operator_pod response (length={}):\n{}",
                                podJson.length(), podJson);

                            JsonNode podResult = parseJson(podJson);

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

    /**
     * Find a node by name in a JSON response (array or single object).
     *
     * @param root the root JSON node
     * @param name the name to search for
     * @return the matching node, or null if not found
     */
    private static JsonNode findByName(final JsonNode root, final String name) {
        if (root.isArray()) {
            for (JsonNode node : root) {
                if (name.equals(node.path("name").asText(""))) {
                    return node;
                }
            }
        } else if (name.equals(root.path("name").asText(""))) {
            return root;
        }
        return null;
    }

    /**
     * Parse a JSON string into a Jackson tree node.
     *
     * @param json the JSON string
     * @return parsed JsonNode
     */
    private static JsonNode parseJson(final String json) {
        try {
            return MAPPER.readTree(json);
        } catch (JsonProcessingException e) {
            throw new AssertionError("Failed to parse JSON response: " + json, e);
        }
    }
}
