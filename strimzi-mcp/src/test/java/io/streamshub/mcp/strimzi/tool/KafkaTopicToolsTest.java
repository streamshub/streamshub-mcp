/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.tool;

import io.quarkiverse.mcp.server.test.McpAssured;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.streamshub.mcp.strimzi.dto.KafkaTopicListResponse;
import io.streamshub.mcp.strimzi.dto.KafkaTopicResponse;
import io.streamshub.mcp.strimzi.service.KafkaTopicService;
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
 * MCP integration tests for Kafka topic tools.
 */
@QuarkusTest
class KafkaTopicToolsTest {

    @ConfigProperty(name = "quarkus.http.test-port")
    int testPort;

    @InjectMock
    KafkaTopicService topicService;

    private McpAssured.McpSseTestClient client;

    KafkaTopicToolsTest() {
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
    void testListKafkaTopics() {
        when(topicService.listTopics(null, "my-cluster", null, null)).thenReturn(
            KafkaTopicListResponse.of(
                List.of(new KafkaTopicResponse("user-events", "my-cluster", 12, 3, "Ready")),
                1, 0, 100, false)
        );

        client.when()
            .toolsCall("list_kafka_topics", Map.of("clusterName", "my-cluster"), response -> {
                assertFalse(response.isError());
                String json = response.content().getFirst().asText().text();
                assertTrue(json.contains("user-events"));
                assertTrue(json.contains("my-cluster"));
                assertTrue(json.contains("\"total\":1"));
                assertTrue(json.contains("\"has_more\":false"));
            })
            .thenAssertResults();
    }

    @Test
    void testGetKafkaTopic() {
        when(topicService.getTopic(null, "my-cluster", "user-events")).thenReturn(
            new KafkaTopicResponse("user-events", "my-cluster", 12, 3, "Ready")
        );

        client.when()
            .toolsCall("get_kafka_topic",
                Map.of("clusterName", "my-cluster", "topicName", "user-events"), response -> {
                    assertFalse(response.isError());
                    String json = response.content().getFirst().asText().text();
                    assertTrue(json.contains("user-events"));
                    assertTrue(json.contains("12"));
                })
            .thenAssertResults();
    }
}
