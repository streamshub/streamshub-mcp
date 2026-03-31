/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.tool;

import io.quarkiverse.mcp.server.test.McpAssured;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.streamshub.mcp.common.dto.PodSummaryResponse;
import io.streamshub.mcp.common.service.PodsService;
import io.streamshub.mcp.strimzi.dto.StrimziOperatorLogsResponse;
import io.streamshub.mcp.strimzi.dto.StrimziOperatorResponse;
import io.streamshub.mcp.strimzi.service.StrimziOperatorService;
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
 * MCP integration tests for Strimzi operator tools.
 */
@QuarkusTest
class StrimziOperatorToolsTest {

    @ConfigProperty(name = "quarkus.http.test-port")
    int testPort;

    @InjectMock
    StrimziOperatorService operatorService;

    @InjectMock
    PodsService podsService;

    private McpAssured.McpSseTestClient client;

    StrimziOperatorToolsTest() {
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
    void testListStrimziOperators() {
        when(operatorService.listOperators(null)).thenReturn(List.of(
            StrimziOperatorResponse.of("strimzi-cluster-operator", "kafka-system",
                true, 1, 1, "0.51.0", "quay.io/strimzi/operator:0.50.0", "48.5", "HEALTHY")
        ));

        client.when()
            .toolsCall("list_strimzi_operators", Map.of(), response -> {
                assertFalse(response.isError());
                String json = response.content().getFirst().asText().text();
                assertTrue(json.contains("strimzi-cluster-operator"));
                assertTrue(json.contains("HEALTHY"));
            })
            .thenAssertResults();
    }

    @Test
    void testGetStrimziOperator() {
        when(operatorService.getOperator(null, "strimzi-cluster-operator")).thenReturn(
            StrimziOperatorResponse.of("strimzi-cluster-operator", "kafka-system",
                true, 1, 1, "0.51.0", "quay.io/strimzi/operator:0.50.0", "48.5", "HEALTHY")
        );

        client.when()
            .toolsCall("get_strimzi_operator",
                Map.of("operatorName", "strimzi-cluster-operator"), response -> {
                    assertFalse(response.isError());
                    String json = response.content().getFirst().asText().text();
                    assertTrue(json.contains("strimzi-cluster-operator"));
                    assertTrue(json.contains("0.51.0"));
                })
            .thenAssertResults();
    }

    @Test
    void testGetStrimziOperatorLogs() {
        when(operatorService.getOperatorLogs(any(), any(), any()))
            .thenReturn(StrimziOperatorLogsResponse.of("kafka-system",
                "INFO: Operator running normally", List.of("strimzi-operator-abc123"),
                false, 0, 1, false));

        client.when()
            .toolsCall("get_strimzi_operator_logs", Map.of(), response -> {
                assertFalse(response.isError());
                String json = response.content().getFirst().asText().text();
                assertTrue(json.contains("Operator running normally"));
                assertTrue(json.contains("strimzi-operator-abc123"));
            })
            .thenAssertResults();
    }

    @Test
    void testGetStrimziOperatorPod() {
        when(podsService.describePod("kafka-system", "strimzi-operator-abc123", null)).thenReturn(
            PodSummaryResponse.of("kafka-system", List.of(
                PodSummaryResponse.PodInfo.summary(
                    "strimzi-operator-abc123", "Running", true, "operator", 0, 120)
            ))
        );

        client.when()
            .toolsCall("get_strimzi_operator_pod",
                Map.of("namespace", "kafka-system", "podName", "strimzi-operator-abc123"), response -> {
                    assertFalse(response.isError());
                    String json = response.content().getFirst().asText().text();
                    assertTrue(json.contains("strimzi-operator-abc123"));
                    assertTrue(json.contains("Running"));
                })
            .thenAssertResults();
    }
}
