/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.tool;

import io.quarkiverse.mcp.server.test.McpAssured;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.streamshub.mcp.common.dto.ConditionInfo;
import io.streamshub.mcp.strimzi.dto.KafkaRebalanceResponse;
import io.streamshub.mcp.strimzi.dto.KafkaRebalanceResponse.OptimizationResultInfo;
import io.streamshub.mcp.strimzi.dto.KafkaRebalanceResponse.RebalanceSpecInfo;
import io.streamshub.mcp.strimzi.service.KafkaRebalanceService;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * MCP integration tests for KafkaRebalance tools.
 */
@QuarkusTest
class KafkaRebalanceToolsTest {

    @ConfigProperty(name = "quarkus.http.test-port")
    int testPort;

    @InjectMock
    KafkaRebalanceService rebalanceService;

    private McpAssured.McpSseTestClient client;

    KafkaRebalanceToolsTest() {
    }

    @BeforeEach
    void setUp() {
        McpAssured.baseUri = URI.create("http://localhost:" + testPort);
        client = McpAssured.newConnectedSseClient();
    }

    @AfterEach
    void tearDown() {
        if (client != null) {
            client.disconnect();
        }
    }

    /**
     * Verify list_kafka_rebalances returns rebalance summaries.
     */
    @Test
    void testListKafkaRebalances() {
        when(rebalanceService.listRebalances(null, "my-cluster")).thenReturn(List.of(
            KafkaRebalanceResponse.summary("my-rebalance", "kafka", "my-cluster",
                "Rebalancing", "full", null, "session-123",
                new OptimizationResultInfo(512L, 10, 5, 0, 100.0, 75.5, 92.3),
                List.of(ConditionInfo.of("Rebalancing", "True", null, null, null)))
        ));

        client.when()
            .toolsCall("list_kafka_rebalances",
                Map.of("clusterName", "my-cluster"), response -> {
                    assertFalse(response.isError());
                    String json = response.content().getFirst().asText().text();
                    assertTrue(json.contains("my-rebalance"));
                    assertTrue(json.contains("Rebalancing"));
                    assertTrue(json.contains("full"));
                    assertTrue(json.contains("my-cluster"));
                    assertTrue(json.contains("session-123"));
                })
            .thenAssertResults();
    }

    /**
     * Verify list_kafka_rebalances returns empty list when none exist.
     */
    @Test
    void testListKafkaRebalancesEmpty() {
        when(rebalanceService.listRebalances(null, null)).thenReturn(List.of());

        client.when()
            .toolsCall("list_kafka_rebalances", Map.of(), response -> {
                assertFalse(response.isError());
            })
            .thenAssertResults();
    }

    /**
     * Verify get_kafka_rebalance returns detailed rebalance with spec and optimization.
     */
    @Test
    void testGetKafkaRebalance() {
        when(rebalanceService.getRebalance(null, "my-rebalance")).thenReturn(
            KafkaRebalanceResponse.of("my-rebalance", "kafka", "my-cluster",
                "ProposalReady", "full", true, "session-456",
                new OptimizationResultInfo(1024L, 25, 10, 3, 99.5, 70.0, 95.0),
                new RebalanceSpecInfo("full", null,
                    List.of("RackAwareGoal", "ReplicaCapacityGoal"),
                    null, null, "__strimzi.*", 5, 2, 1000, 10485760L),
                "my-rebalance-progress",
                List.of(ConditionInfo.of("ProposalReady", "True", null, null, null)))
        );

        client.when()
            .toolsCall("get_kafka_rebalance",
                Map.of("rebalanceName", "my-rebalance"), response -> {
                    assertFalse(response.isError());
                    String json = response.content().getFirst().asText().text();
                    assertTrue(json.contains("my-rebalance"));
                    assertTrue(json.contains("ProposalReady"));
                    assertTrue(json.contains("RackAwareGoal"));
                    assertTrue(json.contains("__strimzi.*"));
                    assertTrue(json.contains("data_to_move_mb"));
                    assertTrue(json.contains("1024"));
                    assertTrue(json.contains("num_replica_movements"));
                    assertTrue(json.contains("balancedness_score_before"));
                    assertTrue(json.contains("my-rebalance-progress"));
                    assertTrue(json.contains("\"auto_approval\":true"));
                })
            .thenAssertResults();
    }

    /**
     * Verify get_kafka_rebalance with namespace parameter passes through.
     */
    @Test
    void testGetKafkaRebalanceWithNamespace() {
        when(rebalanceService.getRebalance("kafka", "my-rebalance")).thenReturn(
            KafkaRebalanceResponse.of("my-rebalance", "kafka", "my-cluster",
                "Ready", "full", null, null,
                null, null, null,
                List.of(ConditionInfo.of("Ready", "True", null, null, null)))
        );

        client.when()
            .toolsCall("get_kafka_rebalance",
                Map.of("rebalanceName", "my-rebalance", "namespace", "kafka"), response -> {
                    assertFalse(response.isError());
                    String json = response.content().getFirst().asText().text();
                    assertTrue(json.contains("Ready"));
                })
            .thenAssertResults();
    }
}
