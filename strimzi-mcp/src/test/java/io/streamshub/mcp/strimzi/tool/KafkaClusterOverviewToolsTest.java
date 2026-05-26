/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.tool;

import io.quarkiverse.mcp.server.test.McpAssured;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.streamshub.mcp.strimzi.dto.kafka.KafkaClusterOverviewResponse;
import io.streamshub.mcp.strimzi.dto.kafka.KafkaClusterOverviewResponse.ClusterSummary;
import io.streamshub.mcp.strimzi.dto.kafka.KafkaClusterOverviewResponse.ConnectSummary;
import io.streamshub.mcp.strimzi.dto.kafka.KafkaClusterOverviewResponse.DrainCleanerSummary;
import io.streamshub.mcp.strimzi.dto.kafka.KafkaClusterOverviewResponse.NodePoolSummary;
import io.streamshub.mcp.strimzi.dto.kafka.KafkaClusterOverviewResponse.RebalanceSummary;
import io.streamshub.mcp.strimzi.dto.kafka.KafkaClusterOverviewResponse.ResourceCount;
import io.streamshub.mcp.strimzi.service.kafka.KafkaClusterOverviewService;
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
 * MCP integration tests for Kafka cluster overview tool.
 */
@QuarkusTest
class KafkaClusterOverviewToolsTest {

    @ConfigProperty(name = "quarkus.http.test-port")
    int testPort;

    @InjectMock
    KafkaClusterOverviewService overviewService;

    private McpAssured.McpSseTestClient client;

    KafkaClusterOverviewToolsTest() {
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
     * Verify full overview with all resource types.
     */
    @Test
    void testGetOverview() {
        when(overviewService.getOverview(null, "my-cluster")).thenReturn(
            new KafkaClusterOverviewResponse(
                new ClusterSummary("my-cluster", "kafka", "4.2.0", "Ready", 6, 6),
                new KafkaClusterOverviewResponse.OperatorSummary(
                    "strimzi-cluster-operator", "kafka", "0.45.0", "Available"),
                List.of(
                    new NodePoolSummary("broker", List.of("broker"), 3, 3, "Ready"),
                    new NodePoolSummary("controller", List.of("controller"), 3, 3, "Ready")),
                ResourceCount.of(42, 40, 2),
                ResourceCount.of(5, 5, 0),
                new RebalanceSummary(1, 0, Map.of("Ready", 1)),
                List.of(new ConnectSummary("my-connect", "kafka", "Ready", 2, 3)),
                List.of(),
                List.of(),
                new DrainCleanerSummary("strimzi-drain-cleaner", true),
                Instant.now())
        );

        client.when()
            .toolsCall("get_strimzi_kafka_cluster_overview",
                Map.of("clusterName", "my-cluster"), response -> {
                    assertFalse(response.isError());
                    String json = response.content().getFirst().asText().text();
                    assertTrue(json.contains("my-cluster"));
                    assertTrue(json.contains("4.2.0"));
                    assertTrue(json.contains("strimzi-cluster-operator"));
                    assertTrue(json.contains("broker"));
                    assertTrue(json.contains("controller"));
                    assertTrue(json.contains("\"total\":42"));
                    assertTrue(json.contains("\"ready\":40"));
                    assertTrue(json.contains("my-connect"));
                    assertTrue(json.contains("\"connector_count\":3"));
                    assertTrue(json.contains("strimzi-drain-cleaner"));
                })
            .thenAssertResults();
    }

    /**
     * Verify overview for cluster with no related resources.
     */
    @Test
    void testGetOverviewEmpty() {
        when(overviewService.getOverview(null, "empty-cluster")).thenReturn(
            new KafkaClusterOverviewResponse(
                new ClusterSummary("empty-cluster", "kafka", "4.2.0", "Ready", 3, 3),
                null,
                List.of(),
                ResourceCount.of(0, 0, 0),
                ResourceCount.of(0, 0, 0),
                new RebalanceSummary(0, 0, null),
                List.of(),
                List.of(),
                List.of(),
                null,
                Instant.now())
        );

        client.when()
            .toolsCall("get_strimzi_kafka_cluster_overview",
                Map.of("clusterName", "empty-cluster"), response -> {
                    assertFalse(response.isError());
                    String json = response.content().getFirst().asText().text();
                    assertTrue(json.contains("empty-cluster"));
                    assertTrue(json.contains("\"total\":0"));
                })
            .thenAssertResults();
    }

    /**
     * Verify overview with namespace parameter.
     */
    @Test
    void testGetOverviewWithNamespace() {
        when(overviewService.getOverview("kafka", "my-cluster")).thenReturn(
            new KafkaClusterOverviewResponse(
                new ClusterSummary("my-cluster", "kafka", "4.2.0", "Ready", 3, 3),
                null, List.of(),
                ResourceCount.of(10, 10, 0),
                ResourceCount.of(2, 2, 0),
                new RebalanceSummary(0, 0, null),
                List.of(), List.of(), List.of(), null,
                Instant.now())
        );

        client.when()
            .toolsCall("get_strimzi_kafka_cluster_overview",
                Map.of("clusterName", "my-cluster", "namespace", "kafka"), response -> {
                    assertFalse(response.isError());
                    String json = response.content().getFirst().asText().text();
                    assertTrue(json.contains("my-cluster"));
                })
            .thenAssertResults();
    }
}
