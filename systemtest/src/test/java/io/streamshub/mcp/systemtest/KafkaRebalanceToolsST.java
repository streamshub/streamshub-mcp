/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.systemtest;

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

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * System tests for KafkaRebalance MCP tools.
 * Deploys the MCP server and a Kafka cluster (without any KafkaRebalance CR)
 * and verifies that the tools handle empty/not-found scenarios correctly.
 */
@KubernetesTest(cleanup = CleanupStrategy.AUTOMATIC, collectLogs = true)
@DisplayName("KafkaRebalance MCP Tools")
@Epic("Strimzi MCP E2E")
@Feature("KafkaRebalance Tools")
class KafkaRebalanceToolsST extends AbstractST {

    private static final Logger LOGGER = LoggerFactory.getLogger(KafkaRebalanceToolsST.class);

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
                    Constants.KAFKA_CLUSTER_NAME, 1).build());

            krm.createOrUpdateResourceWithWait(
                KafkaTemplates.kafka(kafkaNs, Constants.KAFKA_CLUSTER_NAME, 1).build());
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
     * T8.1 - Verify list_kafka_rebalances returns empty when no rebalances exist.
     */
    @Test
    @DisplayName("list_kafka_rebalances returns empty when no rebalances exist")
    @Story("List Kafka Rebalances")
    void testListKafkaRebalancesEmpty() {
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
     * T8.2 - Verify list_kafka_rebalances by cluster returns empty when no rebalances exist.
     */
    @Test
    @DisplayName("list_kafka_rebalances by cluster returns empty when no rebalances exist")
    @Story("List Kafka Rebalances")
    void testListKafkaRebalancesByCluster() {
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
     * T8.3 - Verify get_kafka_rebalance returns error for nonexistent rebalance.
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
            })
            .thenAssertResults();
    }
}
