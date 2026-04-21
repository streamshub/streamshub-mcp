/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.tool;

import io.quarkiverse.mcp.server.test.McpAssured;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.streamshub.mcp.common.dto.ConditionInfo;
import io.streamshub.mcp.strimzi.dto.kafkaconnect.KafkaConnectorResponse;
import io.streamshub.mcp.strimzi.service.kafkaconnect.KafkaConnectorService;
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
 * MCP integration tests for KafkaConnector tools.
 */
@QuarkusTest
class KafkaConnectorToolsTest {

    @ConfigProperty(name = "quarkus.http.test-port")
    int testPort;

    @InjectMock
    KafkaConnectorService connectorService;

    private McpAssured.McpSseTestClient client;

    KafkaConnectorToolsTest() {
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
     * Verify list_kafka_connectors returns connectors.
     */
    @Test
    void testListKafkaConnectors() {
        when(connectorService.listConnectors(null, null)).thenReturn(List.of(
            KafkaConnectorResponse.summary("my-sink", "kafka", "my-connect",
                "org.apache.kafka.connect.file.FileStreamSinkConnector",
                1, "running", "Ready",
                KafkaConnectorResponse.AutoRestartInfo.of(true, null, 0, null),
                List.of("my-topic"),
                List.of(ConditionInfo.of("Ready", "True", null, null, null)))
        ));

        client.when()
            .toolsCall("list_kafka_connectors", Map.of(), response -> {
                assertFalse(response.isError());
                String json = response.content().getFirst().asText().text();
                assertTrue(json.contains("my-sink"));
                assertTrue(json.contains("FileStreamSinkConnector"));
                assertTrue(json.contains("running"));
            })
            .thenAssertResults();
    }

    /**
     * Verify list_kafka_connectors works with connect cluster filter.
     */
    @Test
    void testListKafkaConnectorsWithClusterFilter() {
        when(connectorService.listConnectors(null, "my-connect")).thenReturn(List.of(
            KafkaConnectorResponse.summary("my-sink", "kafka", "my-connect",
                "org.apache.kafka.connect.file.FileStreamSinkConnector",
                1, "running", "Ready", null, null,
                List.of(ConditionInfo.of("Ready", "True", null, null, null)))
        ));

        client.when()
            .toolsCall("list_kafka_connectors",
                Map.of("connectCluster", "my-connect"), response -> {
                    assertFalse(response.isError());
                    String json = response.content().getFirst().asText().text();
                    assertTrue(json.contains("my-sink"));
                    assertTrue(json.contains("my-connect"));
                })
            .thenAssertResults();
    }

    /**
     * Verify get_kafka_connector returns detailed connector info.
     */
    @Test
    void testGetKafkaConnector() {
        when(connectorService.getConnector(null, "my-sink")).thenReturn(
            KafkaConnectorResponse.of("my-sink", "kafka", "my-connect",
                "org.apache.kafka.connect.file.FileStreamSinkConnector",
                1, "running", "Ready",
                KafkaConnectorResponse.AutoRestartInfo.of(true, null, 0, null),
                List.of("my-topic"),
                Map.of("connector", Map.of("state", "RUNNING")),
                List.of(ConditionInfo.of("Ready", "True", null, null, null)),
                Map.of("file", "/tmp/output.txt", "topics", "my-topic"))
        );

        client.when()
            .toolsCall("get_kafka_connector",
                Map.of("connectorName", "my-sink"), response -> {
                    assertFalse(response.isError());
                    String json = response.content().getFirst().asText().text();
                    assertTrue(json.contains("my-sink"));
                    assertTrue(json.contains("FileStreamSinkConnector"));
                    assertTrue(json.contains("RUNNING"));
                    assertTrue(json.contains("output.txt"));
                })
            .thenAssertResults();
    }
}
