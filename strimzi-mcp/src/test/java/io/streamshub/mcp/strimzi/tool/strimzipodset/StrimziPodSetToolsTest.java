/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.tool.strimzipodset;

import io.quarkiverse.mcp.server.test.McpAssured;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.streamshub.mcp.strimzi.dto.strimzipodset.StrimziPodSetResponse;
import io.streamshub.mcp.strimzi.service.strimzipodset.StrimziPodSetService;
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
 * MCP integration tests for {@link StrimziPodSetTools}.
 */
@QuarkusTest
class StrimziPodSetToolsTest {

    @ConfigProperty(name = "quarkus.http.test-port")
    int testPort;

    @InjectMock
    StrimziPodSetService podSetService;

    private McpAssured.McpSseTestClient client;

    StrimziPodSetToolsTest() {
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
    void testGetStrimziPodSetsReturnsItemsAndCount() {
        when(podSetService.listStrimziPodSets("kafka", "my-cluster")).thenReturn(List.of(
            StrimziPodSetResponse.of("my-cluster-broker", "kafka", "my-cluster", 3, 3, 2,
                Map.of("my-cluster-broker-2", "rev-1")),
            StrimziPodSetResponse.of("my-cluster-controller", "kafka", "my-cluster", 3, 3, 3, null)));

        client.when()
            .toolsCall("get_strimzi_pod_sets",
                Map.of("clusterName", "my-cluster", "namespace", "kafka"), response -> {
                    assertFalse(response.isError());
                    String json = response.structuredContent().toString();
                    assertTrue(json.contains("\"count\":2"), json);
                    assertTrue(json.contains("my-cluster-broker"), json);
                    assertTrue(json.contains("my-cluster-controller"), json);
                    assertTrue(json.contains("my-cluster-broker-2"), json);
                })
            .thenAssertResults();
    }

    @Test
    void testGetStrimziPodSetsEmptyResultIsNotAnError() {
        when(podSetService.listStrimziPodSets(null, "my-cluster")).thenReturn(List.of());

        client.when()
            .toolsCall("get_strimzi_pod_sets", Map.of("clusterName", "my-cluster"), response -> {
                assertFalse(response.isError());
                assertTrue(response.structuredContent().toString().contains("\"count\":0"));
            })
            .thenAssertResults();
    }

    @Test
    void testGetStrimziPodSetsRejectsBlankClusterName() {
        client.when()
            .toolsCall("get_strimzi_pod_sets", Map.of("clusterName", ""), response ->
                assertTrue(response.isError()))
            .thenAssertResults();
    }
}
