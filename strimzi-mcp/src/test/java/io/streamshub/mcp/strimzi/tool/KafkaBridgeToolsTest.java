/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.tool;

import io.quarkiverse.mcp.server.test.McpAssured;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.streamshub.mcp.common.dto.ConditionInfo;
import io.streamshub.mcp.common.dto.PodSummaryResponse;
import io.streamshub.mcp.common.dto.ReplicasInfo;
import io.streamshub.mcp.strimzi.dto.kafkabridge.KafkaBridgeLogsResponse;
import io.streamshub.mcp.strimzi.dto.kafkabridge.KafkaBridgePodsResponse;
import io.streamshub.mcp.strimzi.dto.kafkabridge.KafkaBridgeResponse;
import io.streamshub.mcp.strimzi.service.kafkabridge.KafkaBridgeService;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * MCP integration tests for KafkaBridge tools.
 */
@QuarkusTest
class KafkaBridgeToolsTest {

    @ConfigProperty(name = "quarkus.http.test-port")
    int testPort;

    @InjectMock
    KafkaBridgeService bridgeService;

    private McpAssured.McpSseTestClient client;

    KafkaBridgeToolsTest() {
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
     * Verify list_kafka_bridges returns KafkaBridge resources.
     */
    @Test
    void testListKafkaBridges() {
        when(bridgeService.listBridges(null)).thenReturn(List.of(
            KafkaBridgeResponse.summary("my-bridge", "kafka", "Ready",
                ReplicasInfo.of(1, 1), "my-cluster-kafka-bootstrap:9092",
                "http://my-bridge-bridge-service:8080",
                List.of(ConditionInfo.of("Ready", "True", null, null, null)))
        ));

        client.when()
            .toolsCall("list_kafka_bridges", Map.of(), response -> {
                assertFalse(response.isError());
                String json = response.content().getFirst().asText().text();
                assertTrue(json.contains("my-bridge"));
                assertTrue(json.contains("Ready"));
            })
            .thenAssertResults();
    }

    /**
     * Verify get_kafka_bridge returns detailed KafkaBridge info.
     */
    @Test
    void testGetKafkaBridge() {
        when(bridgeService.getBridge(null, "my-bridge")).thenReturn(
            KafkaBridgeResponse.of("my-bridge", "kafka", "Ready",
                ReplicasInfo.of(1, 1), "my-cluster-kafka-bootstrap:9092",
                "http://my-bridge-bridge-service:8080", 8080,
                null, null, null, null, null, null, true, null,
                List.of(ConditionInfo.of("Ready", "True", null, null, null)),
                null, null)
        );

        client.when()
            .toolsCall("get_kafka_bridge", Map.of("bridgeName", "my-bridge"), response -> {
                assertFalse(response.isError());
                String json = response.content().getFirst().asText().text();
                assertTrue(json.contains("my-bridge"));
                assertTrue(json.contains("http_url"));
                assertTrue(json.contains("8080"));
            })
            .thenAssertResults();
    }

    /**
     * Verify get_kafka_bridge_pods returns pod summaries.
     */
    @Test
    void testGetKafkaBridgePods() {
        when(bridgeService.getBridgePods(null, "my-bridge")).thenReturn(
            KafkaBridgePodsResponse.of("my-bridge", "kafka",
                PodSummaryResponse.of("kafka", List.of(
                    PodSummaryResponse.PodInfo.summary(
                        "my-bridge-bridge-0", "Running", true, "bridge", 0, 60))))
        );

        client.when()
            .toolsCall("get_kafka_bridge_pods",
                Map.of("bridgeName", "my-bridge"), response -> {
                    assertFalse(response.isError());
                    String json = response.content().getFirst().asText().text();
                    assertTrue(json.contains("my-bridge-bridge-0"));
                    assertTrue(json.contains("Running"));
                })
            .thenAssertResults();
    }

    /**
     * Verify get_kafka_bridge_logs returns log data.
     */
    @Test
    void testGetKafkaBridgeLogs() {
        when(bridgeService.getBridgeLogs(any(), any(), any())).thenReturn(
            KafkaBridgeLogsResponse.of("my-bridge", "kafka",
                List.of("my-bridge-bridge-0"), false, 0, 50, false,
                "2025-01-01 INFO Bridge started")
        );

        client.when()
            .toolsCall("get_kafka_bridge_logs",
                Map.of("bridgeName", "my-bridge"), response -> {
                    assertFalse(response.isError());
                    String json = response.content().getFirst().asText().text();
                    assertTrue(json.contains("my-bridge"));
                    assertTrue(json.contains("Bridge started"));
                })
            .thenAssertResults();
    }
}
