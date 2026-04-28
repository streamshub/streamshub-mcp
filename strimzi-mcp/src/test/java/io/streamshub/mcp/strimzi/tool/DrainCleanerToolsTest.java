/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.tool;

import io.quarkiverse.mcp.server.test.McpAssured;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.streamshub.mcp.strimzi.dto.DrainCleanerLogsResponse;
import io.streamshub.mcp.strimzi.dto.DrainCleanerReadinessResponse;
import io.streamshub.mcp.strimzi.dto.DrainCleanerResponse;
import io.streamshub.mcp.strimzi.service.DrainCleanerService;
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
 * MCP integration tests for Strimzi Drain Cleaner tools.
 */
@QuarkusTest
class DrainCleanerToolsTest {

    @ConfigProperty(name = "quarkus.http.test-port")
    int testPort;

    @InjectMock
    DrainCleanerService drainCleanerService;

    private McpAssured.McpSseTestClient client;

    DrainCleanerToolsTest() {
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
    void testListDrainCleaners() {
        when(drainCleanerService.listDrainCleaners(null)).thenReturn(List.of(
            DrainCleanerResponse.of("strimzi-drain-cleaner", "strimzi-drain-cleaner",
                true, 1, 1, "1.5.0", "quay.io/strimzi/drain-cleaner:1.5.0",
                "48.5", "HEALTHY", "standard", null, null, null, null, null)
        ));

        client.when()
            .toolsCall("list_drain_cleaners", Map.of(), response -> {
                assertFalse(response.isError());
                String json = response.content().getFirst().asText().text();
                assertTrue(json.contains("strimzi-drain-cleaner"));
                assertTrue(json.contains("HEALTHY"));
            })
            .thenAssertResults();
    }

    @Test
    void testListDrainCleanersWithNamespace() {
        when(drainCleanerService.listDrainCleaners("strimzi-drain-cleaner")).thenReturn(List.of(
            DrainCleanerResponse.of("strimzi-drain-cleaner", "strimzi-drain-cleaner",
                true, 1, 1, "1.5.0", "quay.io/strimzi/drain-cleaner:1.5.0",
                "48.5", "HEALTHY", "standard", null, null, null, null, null)
        ));

        client.when()
            .toolsCall("list_drain_cleaners",
                Map.of("namespace", "strimzi-drain-cleaner"), response -> {
                    assertFalse(response.isError());
                    String json = response.content().getFirst().asText().text();
                    assertTrue(json.contains("strimzi-drain-cleaner"));
                })
            .thenAssertResults();
    }

    @Test
    void testGetDrainCleaner() {
        when(drainCleanerService.getDrainCleaner(null, "strimzi-drain-cleaner")).thenReturn(
            DrainCleanerResponse.of("strimzi-drain-cleaner", "strimzi-drain-cleaner",
                true, 1, 1, "1.5.0", "quay.io/strimzi/drain-cleaner:1.5.0",
                "48.5", "HEALTHY", "standard", true, "Ignore", true, true, null)
        );

        client.when()
            .toolsCall("get_drain_cleaner",
                Map.of("drainCleanerName", "strimzi-drain-cleaner"), response -> {
                    assertFalse(response.isError());
                    String json = response.content().getFirst().asText().text();
                    assertTrue(json.contains("strimzi-drain-cleaner"));
                    assertTrue(json.contains("1.5.0"));
                    assertTrue(json.contains("Ignore"));
                    assertTrue(json.contains("standard"));
                })
            .thenAssertResults();
    }

    @Test
    void testGetDrainCleanerLogs() {
        when(drainCleanerService.getDrainCleanerLogs(any(), any(), any()))
            .thenReturn(DrainCleanerLogsResponse.of("strimzi-drain-cleaner",
                "INFO: Drain cleaner running normally", List.of("strimzi-drain-cleaner-abc123"),
                false, 0, 1, false));

        client.when()
            .toolsCall("get_drain_cleaner_logs", Map.of(), response -> {
                assertFalse(response.isError());
                String json = response.content().getFirst().asText().text();
                assertTrue(json.contains("Drain cleaner running normally"));
                assertTrue(json.contains("strimzi-drain-cleaner-abc123"));
            })
            .thenAssertResults();
    }

    @Test
    void testCheckDrainCleanerReadiness() {
        when(drainCleanerService.checkReadiness(null)).thenReturn(
            DrainCleanerReadinessResponse.notDeployed()
        );

        client.when()
            .toolsCall("check_drain_cleaner_readiness", Map.of(), response -> {
                assertFalse(response.isError());
                String json = response.content().getFirst().asText().text();
                assertTrue(json.contains("false"));
                assertTrue(json.contains("Drain Cleaner deployed"));
            })
            .thenAssertResults();
    }
}