/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.tool.operator;

import io.quarkiverse.mcp.server.JsonRpcErrorCodes;
import io.quarkiverse.mcp.server.test.McpAssured;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.streamshub.mcp.common.dto.ResourceEventsResult;
import io.streamshub.mcp.common.util.McpErrors;
import io.streamshub.mcp.strimzi.dto.operator.StrimziEventsResponse;
import io.streamshub.mcp.strimzi.service.operator.StrimziEventsService;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * MCP integration tests for {@link StrimziEventsTools}.
 */
@QuarkusTest
class StrimziEventsToolsTest {

    @ConfigProperty(name = "quarkus.http.test-port")
    int testPort;

    @InjectMock
    StrimziEventsService eventsService;

    private McpAssured.McpSseTestClient client;

    StrimziEventsToolsTest() {
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
    void testGetStrimziEventsReturnsGroupedEvents() {
        ResourceEventsResult.EventInfo event = new ResourceEventsResult.EventInfo(
            "Warning", "FailedScheduling", "0/3 nodes available", 2, "t1", "t2", "scheduler");
        when(eventsService.getEvents("kafka", "my-cluster", "Kafka", 30)).thenReturn(
            StrimziEventsResponse.of("my-cluster", "kafka",
                List.of(new ResourceEventsResult("Kafka", "my-cluster", List.of(event)))));

        client.when()
            .toolsCall("get_strimzi_events", Map.of("resourceName", "my-cluster", "namespace", "kafka",
                "resourceKind", "Kafka", "sinceMinutes", 30), response -> {
                    assertFalse(response.isError());
                    String json = response.structuredContent().toString();
                    assertTrue(json.contains("\"resource_name\":\"my-cluster\""), json);
                    assertTrue(json.contains("\"total_events\":1"), json);
                    assertTrue(json.contains("FailedScheduling"), json);
                })
            .thenAssertResults();
    }

    @Test
    void testGetStrimziEventsRejectsMissingResourceKind() {
        client.when()
            .toolsCall("get_strimzi_events", Map.of("resourceName", "my-cluster"), response ->
                assertTrue(response.isError()))
            .thenAssertResults();
    }

    @Test
    void testGetStrimziEventsRejectsNonPositiveSinceMinutes() {
        client.when()
            .toolsCall("get_strimzi_events", Map.of("resourceName", "my-cluster", "resourceKind", "Kafka",
                "sinceMinutes", 0), response -> assertTrue(response.isError()))
            .thenAssertResults();
    }

    @Test
    void testGetStrimziEventsServiceValidationErrorIsAProtocolError() {
        when(eventsService.getEvents(null, "my-cluster", "Bogus", null)).thenThrow(
            McpErrors.invalidParams("resourceKind is required. Supported values: [Kafka]"));

        client.when()
            .toolsCall("get_strimzi_events")
            .withArguments(Map.of("resourceName", "my-cluster", "resourceKind", "Bogus"))
            .withErrorAssert(error -> {
                assertEquals(JsonRpcErrorCodes.INVALID_PARAMS, error.code());
                assertTrue(error.message().contains("resourceKind"), error.message());
            })
            .send()
            .thenAssertResults();
    }
}
