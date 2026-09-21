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

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static io.streamshub.mcp.systemtest.TestTags.REGRESSION;
import static io.streamshub.mcp.systemtest.TestTags.TOOLS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * System tests for KafkaNodePool MCP tools.
 * Deploys the MCP server and a Kafka cluster backed by a controller pool and a
 * multi-replica broker pool, then verifies the tools return correct node pool
 * data including the KafkaNodePool.status fields added in Stack A (A3).
 */
@Epic("Strimzi MCP E2E")
@Feature("KafkaNodePool Tools")
@Tag(REGRESSION)
@Tag(TOOLS)
class KafkaNodePoolToolsST extends AbstractST {

    private static final Logger LOGGER = LoggerFactory.getLogger(KafkaNodePoolToolsST.class);
    private static final String CONTROLLER_POOL_NAME = "controller-np";
    private static final String BROKER_POOL_NAME = "broker-np";
    private static final int BROKER_REPLICAS = 2;

    @InjectResourceManager
    KubeResourceManager krm;

    @ClassNamespace(name = Constants.MCP_NAMESPACE)
    static Namespace mcpNamespace;

    @ClassNamespace(name = Constants.STRIMZI_NAMESPACE, labels = {"app=strimzi"})
    static Namespace strimziNamespace;

    @ClassNamespace(name = Constants.KAFKA_NAMESPACE, labels = {"app=strimzi"})
    static Namespace kafkaNamespace;

    private static McpAssured.McpStreamableTestClient mcpClient;

    KafkaNodePoolToolsST() {
    }

    @BeforeAll
    void setup() {
        if (!Environment.SKIP_STRIMZI_INSTALL) {
            String kafkaNs = kafkaNamespace.getMetadata().getName();

            StrimziSetup.deploy(strimziNamespace.getMetadata().getName());

            krm.createOrUpdateResourceWithoutWait(
                KafkaNodePoolTemplates.controllerPool(kafkaNs, CONTROLLER_POOL_NAME,
                    Constants.KAFKA_CLUSTER_NAME, 1).build(),
                KafkaNodePoolTemplates.brokerPool(kafkaNs, BROKER_POOL_NAME,
                    Constants.KAFKA_CLUSTER_NAME, BROKER_REPLICAS).build());

            krm.createOrUpdateResourceWithWait(
                KafkaTemplates.kafka(kafkaNs, Constants.KAFKA_CLUSTER_NAME, BROKER_REPLICAS).build());
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

    /**
     * Verify list_kafka_node_pools returns both deployed node pools.
     */
    @Test
    @Story("list_kafka_node_pools returns both deployed node pools")
    void testListKafkaNodePools() {
        Map<String, Object> args = Map.of(
            "clusterName", Constants.KAFKA_CLUSTER_NAME,
            "namespace", kafkaNamespace.getMetadata().getName());

        mcpClient.when()
            .toolsCall("list_kafka_node_pools", args, response -> {
                JsonNode root = assertToolSuccess(response);

                String json = response.content().getFirst().asText().text();
                LOGGER.info("list_kafka_node_pools response:\n{}", json);
                JsonNode items = root.path("items");
                assertTrue(items.isArray(), "Response should contain items array");
                assertEquals(2, items.size(),
                    "Should have 2 node pools (controller-np, broker-np)");
                Set<String> poolNames = new HashSet<>();
                for (JsonNode pool : items) {
                    poolNames.add(pool.path("name").asText());
                }
                assertTrue(poolNames.contains(CONTROLLER_POOL_NAME), "Should contain controller-np");
                assertTrue(poolNames.contains(BROKER_POOL_NAME), "Should contain broker-np");
            })
            .thenAssertResults();
    }

    /**
     * Verify get_kafka_node_pool returns the controller pool with the controller role.
     */
    @Test
    @Story("get_kafka_node_pool returns controller pool with controller role")
    void testGetKafkaNodePoolController() {
        Map<String, Object> args = Map.of(
            "clusterName", Constants.KAFKA_CLUSTER_NAME,
            "nodePoolName", CONTROLLER_POOL_NAME,
            "namespace", kafkaNamespace.getMetadata().getName());

        mcpClient.when()
            .toolsCall("get_kafka_node_pool", args, response -> {
                JsonNode pool = assertToolSuccess(response);

                String json = response.content().getFirst().asText().text();
                LOGGER.info("get_kafka_node_pool controller response:\n{}", json);
                assertEquals(CONTROLLER_POOL_NAME, pool.path("name").asText(), "Pool name should match");
                assertContainsRole(pool.path("roles"), "controller");
                // Reconciliation status (Stack A)
                assertReconciliationInfo(pool);
                // KafkaNodePool.status surface (A3)
                assertNodePoolStatusFields(pool, 1);
            })
            .thenAssertResults();
    }

    /**
     * Verify get_kafka_node_pool returns the broker pool with the broker role and the
     * new status fields: node_ids, status_replicas, conditions, ready (A3).
     */
    @Test
    @Story("get_kafka_node_pool returns broker pool with status fields populated")
    void testGetKafkaNodePoolBroker() {
        Map<String, Object> args = Map.of(
            "clusterName", Constants.KAFKA_CLUSTER_NAME,
            "nodePoolName", BROKER_POOL_NAME,
            "namespace", kafkaNamespace.getMetadata().getName());

        mcpClient.when()
            .toolsCall("get_kafka_node_pool", args, response -> {
                JsonNode pool = assertToolSuccess(response);

                String json = response.content().getFirst().asText().text();
                LOGGER.info("get_kafka_node_pool broker response:\n{}", json);
                assertEquals(BROKER_POOL_NAME, pool.path("name").asText(), "Pool name should match");
                assertContainsRole(pool.path("roles"), "broker");
                assertEquals(BROKER_REPLICAS, pool.path("replicas").asInt(), "Spec replicas should match");
                // Reconciliation status (Stack A)
                assertReconciliationInfo(pool);
                // KafkaNodePool.status surface (A3)
                assertNodePoolStatusFields(pool, BROKER_REPLICAS);
            })
            .thenAssertResults();
    }

    /**
     * Verify get_kafka_node_pool_pods returns one pod per broker replica.
     */
    @Test
    @Story("get_kafka_node_pool_pods returns pods for the broker pool")
    void testGetKafkaNodePoolPods() {
        Map<String, Object> args = Map.of(
            "clusterName", Constants.KAFKA_CLUSTER_NAME,
            "nodePoolName", BROKER_POOL_NAME,
            "namespace", kafkaNamespace.getMetadata().getName());

        mcpClient.when()
            .toolsCall("get_kafka_node_pool_pods", args, response -> {
                JsonNode root = assertToolSuccess(response);
                JsonNode items = root.path("items");
                assertTrue(items.isArray(), "Response should contain items array");

                LOGGER.info("get_kafka_node_pool_pods returned {} pod(s)", items.size());
                assertEquals(BROKER_REPLICAS, items.size(),
                    "Should return one pod per broker replica");
                for (JsonNode pod : items) {
                    LOGGER.debug("  pod: {}", pod);
                    assertFalse(pod.path("name").asText("").isEmpty(), "Pod should have a name");
                }
            })
            .thenAssertResults();
    }

    private static void assertContainsRole(final JsonNode roles, final String expectedRole) {
        boolean found = false;
        for (JsonNode role : roles) {
            if (expectedRole.equals(role.asText())) {
                found = true;
                break;
            }
        }
        assertTrue(found, "roles should contain '" + expectedRole + "', got: " + roles);
    }

    private static void assertNodePoolStatusFields(final JsonNode pool, final int expectedReplicas) {
        JsonNode nodeIds = pool.path("node_ids");
        assertTrue(nodeIds.isArray() && !nodeIds.isEmpty(), "node_ids should be a non-empty array");
        assertEquals(expectedReplicas, nodeIds.size(), "node_ids should have one entry per replica");
        assertEquals(expectedReplicas, pool.path("status_replicas").asInt(),
            "status_replicas should match the spec replica count");
        // KafkaNodePool conditions are only populated on fatal errors; an empty array is valid when
        // the pool is healthy. Verify the field is present as an array but do not require it to be non-empty.
        assertTrue(pool.path("conditions").isArray(), "conditions should be an array");
    }
}
