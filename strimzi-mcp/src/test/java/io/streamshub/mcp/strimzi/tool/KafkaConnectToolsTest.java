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
import io.streamshub.mcp.strimzi.dto.kafkaconnect.KafkaConnectLogsResponse;
import io.streamshub.mcp.strimzi.dto.kafkaconnect.KafkaConnectPodsResponse;
import io.streamshub.mcp.strimzi.dto.kafkaconnect.KafkaConnectResponse;
import io.streamshub.mcp.strimzi.service.kafkaconnect.KafkaConnectService;
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
 * MCP integration tests for KafkaConnect tools.
 */
@QuarkusTest
class KafkaConnectToolsTest {

    @ConfigProperty(name = "quarkus.http.test-port")
    int testPort;

    @InjectMock
    KafkaConnectService connectService;

    private McpAssured.McpSseTestClient client;

    KafkaConnectToolsTest() {
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
     * Verify list_kafka_connects returns KafkaConnect clusters.
     */
    @Test
    void testListKafkaConnects() {
        when(connectService.listConnects(null)).thenReturn(List.of(
            KafkaConnectResponse.summary("my-connect", "kafka", "Ready",
                ReplicasInfo.of(3, 3), "3.7.0",
                "my-cluster-kafka-bootstrap:9092", "http://my-connect-connect-api:8083",
                5, List.of(ConditionInfo.of("Ready", "True", null, null, null)))
        ));

        client.when()
            .toolsCall("list_kafka_connects", Map.of(), response -> {
                assertFalse(response.isError());
                String json = response.content().getFirst().asText().text();
                assertTrue(json.contains("my-connect"));
                assertTrue(json.contains("Ready"));
            })
            .thenAssertResults();
    }

    /**
     * Verify get_kafka_connect returns detailed KafkaConnect info.
     */
    @Test
    void testGetKafkaConnect() {
        when(connectService.getConnect(null, "my-connect")).thenReturn(
            KafkaConnectResponse.of("my-connect", "kafka", "Ready",
                ReplicasInfo.of(3, 3), "3.7.0",
                "my-cluster-kafka-bootstrap:9092", "http://my-connect-connect-api:8083",
                2, List.of(
                    KafkaConnectResponse.ConnectorPluginInfo.of(
                        "org.apache.kafka.connect.file.FileStreamSinkConnector", "sink", "3.7.0")),
                List.of(ConditionInfo.of("Ready", "True", null, null, null)),
                null, null)
        );

        client.when()
            .toolsCall("get_kafka_connect", Map.of("connectName", "my-connect"), response -> {
                assertFalse(response.isError());
                String json = response.content().getFirst().asText().text();
                assertTrue(json.contains("my-connect"));
                assertTrue(json.contains("FileStreamSinkConnector"));
                assertTrue(json.contains("rest_api_url"));
            })
            .thenAssertResults();
    }

    /**
     * Verify get_kafka_connect_pods returns pod summaries.
     */
    @Test
    void testGetKafkaConnectPods() {
        when(connectService.getConnectPods(null, "my-connect")).thenReturn(
            KafkaConnectPodsResponse.of("my-connect", "kafka",
                PodSummaryResponse.of("kafka", List.of(
                    PodSummaryResponse.PodInfo.summary(
                        "my-connect-connect-0", "Running", true, "connect", 0, 60))))
        );

        client.when()
            .toolsCall("get_kafka_connect_pods",
                Map.of("connectName", "my-connect"), response -> {
                    assertFalse(response.isError());
                    String json = response.content().getFirst().asText().text();
                    assertTrue(json.contains("my-connect-connect-0"));
                    assertTrue(json.contains("Running"));
                })
            .thenAssertResults();
    }

    /**
     * Verify get_kafka_connect_logs returns log data.
     */
    @Test
    void testGetKafkaConnectLogs() {
        when(connectService.getConnectLogs(any(), any(), any())).thenReturn(
            KafkaConnectLogsResponse.of("my-connect", "kafka",
                List.of("my-connect-connect-0"), false, 0, 50, false,
                "2025-01-01 INFO Connect started")
        );

        client.when()
            .toolsCall("get_kafka_connect_logs",
                Map.of("connectName", "my-connect"), response -> {
                    assertFalse(response.isError());
                    String json = response.content().getFirst().asText().text();
                    assertTrue(json.contains("my-connect"));
                    assertTrue(json.contains("Connect started"));
                })
            .thenAssertResults();
    }
}
