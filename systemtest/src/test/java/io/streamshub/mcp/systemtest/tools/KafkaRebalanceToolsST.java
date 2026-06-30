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
import io.skodjob.kubetest4j.wait.Wait;
import io.streamshub.mcp.systemtest.AbstractST;
import io.streamshub.mcp.systemtest.Constants;
import io.streamshub.mcp.systemtest.Environment;
import io.streamshub.mcp.systemtest.clients.McpClientFactory;
import io.streamshub.mcp.systemtest.resources.strimzi.KafkaRebalanceType;
import io.streamshub.mcp.systemtest.setup.mcp.ConnectivitySetup;
import io.streamshub.mcp.systemtest.setup.mcp.McpServerSetup;
import io.streamshub.mcp.systemtest.setup.strimzi.StrimziSetup;
import io.streamshub.mcp.systemtest.templates.strimzi.KafkaNodePoolTemplates;
import io.streamshub.mcp.systemtest.templates.strimzi.KafkaRebalanceTemplates;
import io.streamshub.mcp.systemtest.templates.strimzi.KafkaTemplates;
import io.strimzi.api.kafka.model.rebalance.KafkaRebalanceList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * System tests for KafkaRebalance MCP tools.
 * Deploys the MCP server and a Kafka cluster with CruiseControl
 * and verifies that the tools handle empty, not-found, and actual
 * KafkaRebalance scenarios correctly, including auto-rebalance
 * on scale-up and scale-down.
 */
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
    @Story("list_kafka_rebalances returns empty when no rebalances exist")
    void testListKafkaRebalancesEmpty() {
        waitForNoRebalances(kafkaNamespace.getMetadata().getName());
        Map<String, Object> args = Map.of("namespace", kafkaNamespace.getMetadata().getName());
        mcpClient.when()
            .toolsCall("list_kafka_rebalances", args, response -> {
                assertToolSuccess(response);

                // TODO - assert details about resources

                assertTrue(response.content().isEmpty(), "list_kafka_rebalances should return empty response");
                LOGGER.info("list_kafka_rebalances returned empty response as expected");
            })
            .thenAssertResults();
    }

    /**
     * Verify list_kafka_rebalances by cluster returns empty when no rebalances exist.
     */
    @Test
    @Story("list_kafka_rebalances by cluster returns empty when no rebalances exist")
    void testListKafkaRebalancesByCluster() {
        waitForNoRebalances(kafkaNamespace.getMetadata().getName());
        Map<String, Object> args = Map.of(
            "clusterName", Constants.KAFKA_CLUSTER_NAME,
            "namespace", kafkaNamespace.getMetadata().getName());

        mcpClient.when()
            .toolsCall("list_kafka_rebalances", args, response -> {
                assertToolSuccess(response);

                assertTrue(response.content().isEmpty(), "list_kafka_rebalances should return empty response");
            })
            .thenAssertResults();
    }

    /**
     * Verify get_kafka_rebalance returns error for nonexistent rebalance.
     */
    @Test
    @Story("get_kafka_rebalance returns error for nonexistent rebalance")
    void testGetKafkaRebalanceNotFound() {
        Map<String, Object> args = Map.of(
            "rebalanceName", "nonexistent-rebalance",
            "namespace", kafkaNamespace.getMetadata().getName());
        mcpClient.when()
            .toolsCall("get_kafka_rebalance", args, response -> {
                // TODO - improve strings for asserts
                assertToolError(response, "not found");
            })
            .thenAssertResults();
    }

    // ---- Manual KafkaRebalance CR Tests ----
    /**
     * Verify list_kafka_rebalances returns the deployed rebalance resource.
     */
    @Test
    @Story("list_kafka_rebalances returns deployed rebalance")
    void testListKafkaRebalancesWithRebalance() {
        String kafkaNs = kafkaNamespace.getMetadata().getName();
        krm.createOrUpdateResourceWithoutWait(
            KafkaRebalanceTemplates.rebalance(kafkaNs, REBALANCE_NAME,
                Constants.KAFKA_CLUSTER_NAME).build());

        waitForRebalanceToAppear(kafkaNs);

        Map<String, Object> args = Map.of("namespace", kafkaNs);

        mcpClient.when()
            .toolsCall("list_kafka_rebalances", args, response -> {
                JsonNode root = assertToolSuccess(response);

                // TODO - assert more details about rebalance

                String json = response.content().getFirst().asText().text();
                LOGGER.info("list_kafka_rebalances response (length={})", json.length());
                LOGGER.debug("list_kafka_rebalances response:\n{}", json);
                JsonNode rebalance;
                if (root.isArray()) {
                    assertTrue(root.size() >= 1, "Should have at least 1 rebalance");
                    rebalance = root.get(0);
                } else {
                    rebalance = root;
                }
                assertEquals(REBALANCE_NAME, rebalance.path("name").asText(),
                    "Rebalance name should match");
                assertFalse(rebalance.path("state").isMissingNode(),
                    "Rebalance should have 'state' field");
                assertEquals(Constants.KAFKA_CLUSTER_NAME,
                    rebalance.path("cluster").asText(),
                    "Rebalance cluster should match");
            })
            .thenAssertResults();
    }

    /**
     * Verify list_kafka_rebalances filtered by cluster returns the rebalance.
     */
    @Test
    @Story("list_kafka_rebalances filtered by cluster returns rebalance")
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
                JsonNode root = assertToolSuccess(response);

                // TODO - assert more details about rebalance

                String json = response.content().getFirst().asText().text();
                LOGGER.info("list_kafka_rebalances filtered response (length={})", json.length());
                LOGGER.debug("list_kafka_rebalances filtered response:\n{}", json);
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
    @Story("get_kafka_rebalance returns detailed rebalance info")
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
                JsonNode rebalance = assertToolSuccess(response);

                String json = response.content().getFirst().asText().text();
                LOGGER.info("get_kafka_rebalance response (length={})", json.length());
                LOGGER.debug("get_kafka_rebalance response:\n{}", json);
                assertEquals(REBALANCE_NAME, rebalance.path("name").asText(),
                    "Rebalance name should match");
                assertEquals(kafkaNs, rebalance.path("namespace").asText(),
                    "Namespace should match");
                assertEquals(Constants.KAFKA_CLUSTER_NAME, rebalance.path("cluster").asText(),
                    "Cluster should match");
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
