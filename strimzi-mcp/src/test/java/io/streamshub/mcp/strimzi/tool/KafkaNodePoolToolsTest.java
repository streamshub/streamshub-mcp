/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.tool;

import io.quarkiverse.mcp.server.test.McpAssured;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.streamshub.mcp.common.dto.PodSummaryResponse;
import io.streamshub.mcp.strimzi.dto.KafkaNodePoolResponse;
import io.streamshub.mcp.strimzi.service.KafkaNodePoolService;
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
 * MCP integration tests for KafkaNodePool tools.
 */
@QuarkusTest
class KafkaNodePoolToolsTest {

    @ConfigProperty(name = "quarkus.http.test-port")
    int testPort;

    @InjectMock
    KafkaNodePoolService nodePoolService;

    private McpAssured.McpSseTestClient client;

    KafkaNodePoolToolsTest() {
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

    @Test
    void testListKafkaNodePools() {
        when(nodePoolService.listNodePools(null, "my-cluster")).thenReturn(List.of(
            new KafkaNodePoolResponse("broker", "kafka", "my-cluster",
                List.of("broker"), 3, "jbod", "100Gi")
        ));

        client.when()
            .toolsCall("list_kafka_node_pools", Map.of("clusterName", "my-cluster"), response -> {
                assertFalse(response.isError());
                String json = response.content().getFirst().asText().text();
                assertTrue(json.contains("broker"));
                assertTrue(json.contains("my-cluster"));
            })
            .thenAssertResults();
    }

    @Test
    void testGetKafkaNodePool() {
        when(nodePoolService.getNodePool(null, "my-cluster", "broker")).thenReturn(
            new KafkaNodePoolResponse("broker", "kafka", "my-cluster",
                List.of("broker"), 3, "jbod", "100Gi")
        );

        client.when()
            .toolsCall("get_kafka_node_pool",
                Map.of("clusterName", "my-cluster", "nodePoolName", "broker"), response -> {
                    assertFalse(response.isError());
                    String json = response.content().getFirst().asText().text();
                    assertTrue(json.contains("broker"));
                    assertTrue(json.contains("100Gi"));
                })
            .thenAssertResults();
    }

    @Test
    void testGetKafkaNodePoolPods() {
        when(nodePoolService.getNodePoolPods(null, "my-cluster", "broker")).thenReturn(List.of(
            PodSummaryResponse.PodInfo.summary("my-cluster-broker-0", "Running", true, "kafka", 0, 60)
        ));

        client.when()
            .toolsCall("get_kafka_node_pool_pods",
                Map.of("clusterName", "my-cluster", "nodePoolName", "broker"), response -> {
                    assertFalse(response.isError());
                    String json = response.content().getFirst().asText().text();
                    assertTrue(json.contains("my-cluster-broker-0"));
                    assertTrue(json.contains("Running"));
                })
            .thenAssertResults();
    }
}
