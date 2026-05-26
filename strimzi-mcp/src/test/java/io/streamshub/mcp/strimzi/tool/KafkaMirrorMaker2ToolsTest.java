/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.tool;

import io.quarkiverse.mcp.server.test.McpAssured;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.streamshub.mcp.common.dto.ConditionInfo;
import io.streamshub.mcp.common.dto.ReplicasInfo;
import io.streamshub.mcp.strimzi.dto.kafkamirrormaker2.KafkaMirrorMaker2Response;
import io.streamshub.mcp.strimzi.dto.kafkamirrormaker2.KafkaMirrorMaker2Response.MirrorInfo;
import io.streamshub.mcp.strimzi.service.kafkamirrormaker2.KafkaMirrorMaker2Service;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;
/**
 * MCP integration tests for KafkaMirrorMaker2 tools.
 */
@QuarkusTest
class KafkaMirrorMaker2ToolsTest {

    @ConfigProperty(name = "quarkus.http.test-port")
    int testPort;

    @InjectMock
    KafkaMirrorMaker2Service mirrorMakerService;

    private McpAssured.McpSseTestClient client;

    KafkaMirrorMaker2ToolsTest() {
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
     * Verify list_kafka_mirror_makers returns MM2 summaries.
     */
    @Test
    void testListKafkaMirrorMakers() {
        when(mirrorMakerService.listMirrorMakers(null)).thenReturn(List.of(
            KafkaMirrorMaker2Response.summary("my-mm2", "kafka", "Ready",
                ReplicasInfo.of(2, 2), "target-cluster",
                List.of("source-cluster"),
                List.of(ConditionInfo.of("Ready", "True", null, null, null)))
        ));

        client.when()
            .toolsCall("list_kafka_mirror_makers", Map.of(), response -> {
                assertFalse(response.isError());
                String json = response.content().getFirst().asText().text();
                assertTrue(json.contains("my-mm2"));
                assertTrue(json.contains("target-cluster"));
                assertTrue(json.contains("source-cluster"));
            })
            .thenAssertResults();
    }

    /**
     * Verify list returns empty when no MM2 instances exist.
     */
    @Test
    void testListKafkaMirrorMakersEmpty() {
        when(mirrorMakerService.listMirrorMakers(null)).thenReturn(List.of());

        client.when()
            .toolsCall("list_kafka_mirror_makers", Map.of(), response -> {
                assertFalse(response.isError());
            })
            .thenAssertResults();
    }

    /**
     * Verify get_kafka_mirror_maker returns detailed MM2 info.
     */
    @Test
    void testGetKafkaMirrorMaker() {
        when(mirrorMakerService.getMirrorMaker(null, "my-mm2")).thenReturn(
            KafkaMirrorMaker2Response.of("my-mm2", "kafka", "Ready",
                ReplicasInfo.of(2, 2), "target-cluster",
                List.of("source-cluster"),
                List.of(new MirrorInfo("source-cluster", ".*", "__strimzi.*",
                    ".*", null, null, null)),
                null, null, "4.2.0",
                "target-cluster-kafka-bootstrap:9092",
                List.of(ConditionInfo.of("Ready", "True", null, null, null)),
                Instant.now(), 120L)
        );

        client.when()
            .toolsCall("get_kafka_mirror_maker",
                Map.of("mirrorMakerName", "my-mm2"), response -> {
                    assertFalse(response.isError());
                    String json = response.content().getFirst().asText().text();
                    assertTrue(json.contains("my-mm2"));
                    assertTrue(json.contains("source-cluster"));
                    assertTrue(json.contains("target-cluster"));
                    assertTrue(json.contains("__strimzi.*"));
                    assertTrue(json.contains("4.2.0"));
                })
            .thenAssertResults();
    }
}
