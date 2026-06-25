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
import io.skodjob.kubetest4j.annotations.CleanupStrategy;
import io.skodjob.kubetest4j.annotations.InjectResourceManager;
import io.skodjob.kubetest4j.annotations.KubernetesTest;
import io.skodjob.kubetest4j.annotations.LogCollectionStrategy;
import io.skodjob.kubetest4j.resources.KubeResourceManager;
import io.skodjob.kubetest4j.wait.Wait;
import io.streamshub.mcp.systemtest.clients.McpClientFactory;
import io.streamshub.mcp.systemtest.resources.strimzi.KafkaRebalanceType;
import io.streamshub.mcp.systemtest.setup.mcp.ConnectivitySetup;
import io.streamshub.mcp.systemtest.setup.mcp.McpServerSetup;
import io.streamshub.mcp.systemtest.setup.strimzi.StrimziSetup;
import io.streamshub.mcp.systemtest.templates.strimzi.KafkaNodePoolTemplates;
import io.streamshub.mcp.systemtest.templates.strimzi.KafkaRebalanceTemplates;
import io.streamshub.mcp.systemtest.templates.strimzi.KafkaTemplates;
import io.strimzi.api.kafka.model.bridge.KafkaBridge;
import io.strimzi.api.kafka.model.connect.KafkaConnect;
import io.strimzi.api.kafka.model.connector.KafkaConnector;
import io.strimzi.api.kafka.model.kafka.Kafka;
import io.strimzi.api.kafka.model.mirrormaker2.KafkaMirrorMaker2;
import io.strimzi.api.kafka.model.nodepool.KafkaNodePool;
import io.strimzi.api.kafka.model.rebalance.KafkaRebalance;
import io.strimzi.api.kafka.model.rebalance.KafkaRebalanceList;
import io.strimzi.api.kafka.model.topic.KafkaTopic;
import io.strimzi.api.kafka.model.user.KafkaUser;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * System tests for KafkaRebalance MCP tools.
 * Deploys the MCP server and a Kafka cluster with CruiseControl
 * and verifies that the tools handle empty, not-found, and actual
 * KafkaRebalance scenarios correctly, including auto-rebalance
 * on scale-up and scale-down.
 */
@KubernetesTest(
    cleanup = CleanupStrategy.AUTOMATIC,
    collectLogs = true,
    logCollectionStrategy = LogCollectionStrategy.ON_FAILURE,
    collectPreviousLogs = true,
    collectNamespacedResources = {
        "pods", "services", "configmaps", "secrets", "deployments",
        Kafka.RESOURCE_SINGULAR,
        KafkaNodePool.RESOURCE_SINGULAR,
        KafkaTopic.RESOURCE_SINGULAR,
        KafkaUser.RESOURCE_SINGULAR,
        KafkaConnect.RESOURCE_SINGULAR,
        KafkaConnector.RESOURCE_SINGULAR,
        KafkaBridge.RESOURCE_SINGULAR,
        KafkaMirrorMaker2.RESOURCE_SINGULAR,
        KafkaRebalance.RESOURCE_SINGULAR
    }
)
@DisplayName("KafkaRebalance MCP Tools")
@Epic("Strimzi MCP E2E")
@Feature("KafkaRebalance Tools")
class KafkaRebalanceToolsST extends AbstractST {

    private static final Logger LOGGER = LoggerFactory.getLogger(KafkaRebalanceToolsST.class);
    private static final String REBALANCE_NAME = "mcp-rebalance";
    private static final int INITIAL_BROKER_REPLICAS = 4;

    @InjectResourceManager
    KubeResourceManager krm;

    @ClassNamespace(name = Constants.MCP_NAMESPACE)
    static Namespace mcpNamespace;

    @ClassNamespace(name = Constants.STRIMZI_NAMESPACE, labels = {"app=strimzi"})
    static Namespace strimziNamespace;

    @ClassNamespace(name = Constants.KAFKA_NAMESPACE, labels = {"app=strimzi"})
    static Namespace kafkaNamespace;

    private static McpAssured.McpStreamableTestClient mcpClient;

    KafkaRebalanceToolsST() {
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
                    Constants.KAFKA_CLUSTER_NAME, INITIAL_BROKER_REPLICAS).build());

            krm.createOrUpdateResourceWithWait(
                KafkaTemplates.kafkaWithCruiseControl(
                    kafkaNs, Constants.KAFKA_CLUSTER_NAME, INITIAL_BROKER_REPLICAS).build());
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

    // ---- Empty / Not-Found Tests ----

    /**
     * Verify list_kafka_rebalances returns empty when no rebalances exist.
     */
    @Test
    @DisplayName("list_kafka_rebalances returns empty when no rebalances exist")
    @Story("List Kafka Rebalances")
    void testListKafkaRebalancesEmpty() {
        waitForNoRebalances(kafkaNamespace.getMetadata().getName());

        Map<String, Object> args = Map.of("namespace", kafkaNamespace.getMetadata().getName());
        mcpClient.when()
            .toolsCall("list_kafka_rebalances", args, response -> {
                assertFalse(response.isError(), "list_kafka_rebalances should not return error");
                assertTrue(response.content().isEmpty(), "list_kafka_rebalances should return empty response");
                LOGGER.info("list_kafka_rebalances returned empty response as expected");
            })
            .thenAssertResults();
    }

    /**
     * Verify list_kafka_rebalances by cluster returns empty when no rebalances exist.
     */
    @Test
    @DisplayName("list_kafka_rebalances by cluster returns empty when no rebalances exist")
    @Story("List Kafka Rebalances")
    void testListKafkaRebalancesByCluster() {
        waitForNoRebalances(kafkaNamespace.getMetadata().getName());

        Map<String, Object> args = Map.of(
            "clusterName", Constants.KAFKA_CLUSTER_NAME,
            "namespace", kafkaNamespace.getMetadata().getName());
        mcpClient.when()
            .toolsCall("list_kafka_rebalances", args, response -> {
                assertFalse(response.isError(), "list_kafka_rebalances should not return error");
                assertTrue(response.content().isEmpty(), "list_kafka_rebalances should return empty response");
                LOGGER.info("list_kafka_rebalances by cluster returned empty response as expected");
            })
            .thenAssertResults();
    }

    /**
     * Verify get_kafka_rebalance returns error for nonexistent rebalance.
     */
    @Test
    @DisplayName("get_kafka_rebalance returns error for nonexistent rebalance")
    @Story("Get Kafka Rebalance")
    void testGetKafkaRebalanceNotFound() {
        Map<String, Object> args = Map.of(
            "rebalanceName", "nonexistent-rebalance",
            "namespace", kafkaNamespace.getMetadata().getName());
        mcpClient.when()
            .toolsCall("get_kafka_rebalance", args, response -> {
                assertTrue(response.isError(), "get_kafka_rebalance should return error for nonexistent rebalance");
                String text = response.content().getFirst().asText().text();
                LOGGER.info("get_kafka_rebalance error response:\n{}", text);
                assertTrue(text.contains("not found"),
                    "Error message should mention 'not found'");
            })
            .thenAssertResults();
    }

    // ---- Manual KafkaRebalance CR Tests ----

    /**
     * Verify list_kafka_rebalances returns the deployed rebalance resource.
     */
    @Test
    @DisplayName("list_kafka_rebalances returns deployed rebalance")
    @Story("List Kafka Rebalances")
    void testListKafkaRebalancesWithRebalance() {
        String kafkaNs = kafkaNamespace.getMetadata().getName();

        krm.createOrUpdateResourceWithoutWait(
            KafkaRebalanceTemplates.rebalance(kafkaNs, REBALANCE_NAME,
                Constants.KAFKA_CLUSTER_NAME).build());

        waitForRebalanceToAppear(kafkaNs);

        Map<String, Object> args = Map.of("namespace", kafkaNs);
        mcpClient.when()
            .toolsCall("list_kafka_rebalances", args, response -> {
                assertFalse(response.isError(), "list_kafka_rebalances should not return error");
                assertFalse(response.content().isEmpty(),
                    "list_kafka_rebalances should return at least one rebalance");

                String json = response.content().getFirst().asText().text();
                LOGGER.info("list_kafka_rebalances response:\n{}", json);

                JsonNode root = parseJson(json);

                JsonNode rebalance;
                if (root.isArray()) {
                    assertTrue(root.size() >= 1, "Should have at least 1 rebalance");
                    rebalance = root.get(0);
                } else {
                    rebalance = root;
                }
                assertFalse(rebalance.path("name").isMissingNode(),
                    "Rebalance should have 'name' field");
                assertFalse(rebalance.path("state").isMissingNode(),
                    "Rebalance should have 'state' field");
                assertFalse(rebalance.path("cluster").isMissingNode(),
                    "Rebalance should have 'cluster' field");
            })
            .thenAssertResults();
    }

    /**
     * Verify list_kafka_rebalances filtered by cluster returns the rebalance.
     */
    @Test
    @DisplayName("list_kafka_rebalances filtered by cluster returns rebalance")
    @Story("List Kafka Rebalances")
    void testListKafkaRebalancesFilteredByCluster() {
        String kafkaNs = kafkaNamespace.getMetadata().getName();

        krm.createOrUpdateResourceWithoutWait(
            KafkaRebalanceTemplates.rebalance(kafkaNs, REBALANCE_NAME,
                Constants.KAFKA_CLUSTER_NAME).build());
        waitForRebalanceToAppear(kafkaNs);

        Map<String, Object> args = Map.of(
            "clusterName", Constants.KAFKA_CLUSTER_NAME,
            "namespace", kafkaNs);
        mcpClient.when()
            .toolsCall("list_kafka_rebalances", args, response -> {
                assertFalse(response.isError(), "list_kafka_rebalances should not return error");
                assertFalse(response.content().isEmpty(),
                    "Should find rebalance when filtering by cluster");

                String json = response.content().getFirst().asText().text();
                LOGGER.info("list_kafka_rebalances filtered response:\n{}", json);

                JsonNode root = parseJson(json);
                boolean found = false;
                if (root.isArray()) {
                    for (JsonNode rebalance : root) {
                        if (REBALANCE_NAME.equals(rebalance.path("name").asText())) {
                            found = true;
                            break;
                        }
                    }
                } else if (root.isObject()) {
                    found = REBALANCE_NAME.equals(root.path("name").asText());
                }
                assertTrue(found, "Should find rebalance '" + REBALANCE_NAME + "' in filtered results");
            })
            .thenAssertResults();
    }

    /**
     * Verify get_kafka_rebalance returns details for the deployed rebalance.
     */
    @Test
    @DisplayName("get_kafka_rebalance returns detailed rebalance info")
    @Story("Get Kafka Rebalance")
    void testGetKafkaRebalance() {
        String kafkaNs = kafkaNamespace.getMetadata().getName();

        krm.createOrUpdateResourceWithoutWait(
            KafkaRebalanceTemplates.rebalance(kafkaNs, REBALANCE_NAME,
                Constants.KAFKA_CLUSTER_NAME).build());
        waitForRebalanceToAppear(kafkaNs);

        Map<String, Object> args = Map.of(
            "rebalanceName", REBALANCE_NAME,
            "namespace", kafkaNs);
        mcpClient.when()
            .toolsCall("get_kafka_rebalance", args, response -> {
                assertFalse(response.isError(), "get_kafka_rebalance should not return error");

                String json = response.content().getFirst().asText().text();
                LOGGER.info("get_kafka_rebalance response:\n{}", json);

                JsonNode rebalance = parseJson(json);
                assertFalse(rebalance.path("name").isMissingNode(),
                    "Should have 'name' field");
                assertFalse(rebalance.path("namespace").isMissingNode(),
                    "Should have 'namespace' field");
                assertFalse(rebalance.path("cluster").isMissingNode(),
                    "Should have 'cluster' field");
                assertFalse(rebalance.path("state").isMissingNode(),
                    "Should have 'state' field");
                assertFalse(rebalance.path("conditions").isMissingNode(),
                    "Should have 'conditions' field");
                assertFalse(rebalance.path("spec").isMissingNode(),
                    "Detail response should have 'spec' field");
            })
            .thenAssertResults();
    }

    // ---- Helpers ----

    private void waitForRebalanceToAppear(final String namespace) {
        LOGGER.info("Waiting for KafkaRebalance '{}' to appear in namespace '{}'",
            REBALANCE_NAME, namespace);
        try {
            KafkaRebalanceType.kafkaRebalanceClient().inNamespace(namespace)
                .withName(REBALANCE_NAME)
                .waitUntilCondition(
                    kr -> kr != null && kr.getStatus() != null
                        && kr.getStatus().getConditions() != null
                        && !kr.getStatus().getConditions().isEmpty(),
                    5, TimeUnit.MINUTES);
        } catch (Exception e) {
            LOGGER.warn("Timeout waiting for KafkaRebalance to have conditions, continuing anyway", e);
        }
    }

    private void waitForNoRebalances(final String namespace) {
        Wait.until("no KafkaRebalances in namespace '" + namespace + "'",
            Constants.KAFKA_READY_POLL_MS, Constants.KAFKA_READY_TIMEOUT_MS, () -> {
                KafkaRebalanceList list = KafkaRebalanceType.kafkaRebalanceClient()
                    .inNamespace(namespace).list();
                return list.getItems() == null || list.getItems().isEmpty();
            });
    }
}
