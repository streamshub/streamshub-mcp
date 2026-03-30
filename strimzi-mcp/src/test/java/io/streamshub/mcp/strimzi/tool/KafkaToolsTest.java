/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.tool;

import io.quarkiverse.mcp.server.ToolCallException;
import io.quarkiverse.mcp.server.test.McpAssured;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.streamshub.mcp.common.dto.ConditionInfo;
import io.streamshub.mcp.common.dto.PodSummaryResponse;
import io.streamshub.mcp.common.dto.ReplicasInfo;
import io.streamshub.mcp.strimzi.dto.KafkaBootstrapResponse;
import io.streamshub.mcp.strimzi.dto.KafkaClusterPodsResponse;
import io.streamshub.mcp.strimzi.dto.KafkaClusterResponse;
import io.streamshub.mcp.strimzi.dto.ListenerInfo;
import io.streamshub.mcp.strimzi.service.KafkaService;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * MCP integration tests for Kafka cluster tools.
 */
@QuarkusTest
class KafkaToolsTest {

    @ConfigProperty(name = "quarkus.http.test-port")
    int testPort;

    @InjectMock
    KafkaService kafkaService;

    private McpAssured.McpSseTestClient client;

    KafkaToolsTest() {
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
    void testListKafkaClusters() {
        when(kafkaService.listClusters(null)).thenReturn(List.of(
            new KafkaClusterResponse("my-cluster", "kafka", "Kafka", "4.2.0",
                "Ready",
                List.of(new ConditionInfo("Ready", "True", null, null, null)),
                List.of(new ListenerInfo("plain", "internal",
                    "my-cluster-kafka-bootstrap.kafka.svc:9092"),
                    new ListenerInfo("tls", "internal",
                    "my-cluster-kafka-bootstrap.kafka.svc:9093")),
                new ReplicasInfo(3, 3),
                "jbod", "100Gi",
                false, true, false, Instant.parse("2025-01-01T00:00:00Z"), 60L, "strimzi")
        ));

        client.when()
            .toolsCall("list_kafka_clusters", Map.of(), response -> {
                assertFalse(response.isError());
                String json = response.content().getFirst().asText().text();
                assertTrue(json.contains("my-cluster"));
                assertTrue(json.contains("kafka"));
                assertTrue(json.contains("Ready"));
            })
            .thenAssertResults();
    }

    @Test
    void testGetKafkaCluster() {
        when(kafkaService.getCluster(null, "my-cluster")).thenReturn(
            new KafkaClusterResponse("my-cluster", "kafka", "Kafka", "4.2.0",
                "Ready",
                List.of(new ConditionInfo("Ready", "True", null, null, null)),
                List.of(new ListenerInfo("plain", "internal",
                    "my-cluster-kafka-bootstrap.kafka.svc:9092")),
                new ReplicasInfo(3, 3),
                "jbod", "100Gi",
                false, true, false, Instant.parse("2025-01-01T00:00:00Z"), 60L, "strimzi")
        );

        client.when()
            .toolsCall("get_kafka_cluster", Map.of("clusterName", "my-cluster"), response -> {
                assertFalse(response.isError());
                String json = response.content().getFirst().asText().text();
                assertTrue(json.contains("my-cluster"));
                assertTrue(json.contains("4.2.0"));
            })
            .thenAssertResults();
    }

    @Test
    void testGetKafkaClusterPods() {
        PodSummaryResponse podSummary = PodSummaryResponse.of("kafka", List.of(
            PodSummaryResponse.PodInfo.summary("my-cluster-kafka-0", "Running", true, "kafka", 0, 120)
        ));
        when(kafkaService.getClusterPods(null, "my-cluster")).thenReturn(
            KafkaClusterPodsResponse.of("my-cluster", "kafka", podSummary)
        );

        client.when()
            .toolsCall("get_kafka_cluster_pods", Map.of("clusterName", "my-cluster"), response -> {
                assertFalse(response.isError());
                String json = response.content().getFirst().asText().text();
                assertTrue(json.contains("my-cluster-kafka-0"));
                assertTrue(json.contains("Running"));
            })
            .thenAssertResults();
    }

    @Test
    void testGetKafkaBootstrapServers() {
        when(kafkaService.getBootstrapServers(null, "my-cluster")).thenReturn(
            KafkaBootstrapResponse.of("kafka", "my-cluster", List.of(
                new KafkaBootstrapResponse.BootstrapServerInfo(
                    "my-cluster-kafka-bootstrap.kafka.svc", 9092, "plain", "internal",
                    "my-cluster-kafka-bootstrap.kafka.svc:9092")
            ))
        );

        client.when()
            .toolsCall("get_kafka_bootstrap_servers", Map.of("clusterName", "my-cluster"), response -> {
                assertFalse(response.isError());
                String json = response.content().getFirst().asText().text();
                assertTrue(json.contains("my-cluster-kafka-bootstrap"));
                assertTrue(json.contains("9092"));
            })
            .thenAssertResults();
    }

    @Test
    void testToolCallExceptionWrappedAsError() {
        when(kafkaService.getCluster(any(), eq("nonexistent"))).thenThrow(
            new ToolCallException("Kafka cluster 'nonexistent' not found in any namespace")
        );

        client.when()
            .toolsCall("get_kafka_cluster", Map.of("clusterName", "nonexistent"), response -> {
                assertTrue(response.isError());
                String text = response.content().getFirst().asText().text();
                assertTrue(text.contains("not found"));
            })
            .thenAssertResults();
    }

    @Test
    void testGenericExceptionWrappedAsError() {
        when(kafkaService.listClusters(any())).thenThrow(
            new RuntimeException("Connection refused")
        );

        client.when()
            .toolsCall("list_kafka_clusters", Map.of(), response -> {
                assertTrue(response.isError());
                String text = response.content().getFirst().asText().text();
                assertTrue(text.contains("Connection refused"));
            })
            .thenAssertResults();
    }

    @Test
    void testEmptyListResult() {
        when(kafkaService.listClusters(null)).thenReturn(List.of());

        client.when()
            .toolsCall("list_kafka_clusters", Map.of(), response -> {
                assertFalse(response.isError());
                assertTrue(response.content().isEmpty());
            })
            .thenAssertResults();
    }

    @Test
    void testNamespaceParameterPassedThrough() {
        when(kafkaService.listClusters("production")).thenReturn(List.of(
            new KafkaClusterResponse("prod-cluster", "production", "Kafka", "4.2.0",
                "Ready",
                List.of(new ConditionInfo("Ready", "True", null, null, null)),
                List.of(new ListenerInfo("tls", "internal",
                    "prod-cluster-kafka-bootstrap.production.svc:9093")),
                new ReplicasInfo(3, 3),
                "jbod", "100Gi",
                false, true, true, Instant.parse("2025-01-01T00:00:00Z"), 120L, "strimzi")
        ));

        client.when()
            .toolsCall("list_kafka_clusters", Map.of("namespace", "production"), response -> {
                assertFalse(response.isError());
                String json = response.content().getFirst().asText().text();
                assertTrue(json.contains("prod-cluster"));
                assertTrue(json.contains("production"));
            })
            .thenAssertResults();
    }
}
