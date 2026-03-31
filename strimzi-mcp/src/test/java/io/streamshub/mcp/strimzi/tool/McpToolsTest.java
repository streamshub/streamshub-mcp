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
import io.streamshub.mcp.common.service.PodsService;
import io.streamshub.mcp.strimzi.dto.KafkaBootstrapResponse;
import io.streamshub.mcp.strimzi.dto.KafkaClusterPodsResponse;
import io.streamshub.mcp.strimzi.dto.KafkaClusterResponse;
import io.streamshub.mcp.strimzi.dto.KafkaNodePoolResponse;
import io.streamshub.mcp.strimzi.dto.KafkaTopicResponse;
import io.streamshub.mcp.strimzi.dto.ListenerInfo;
import io.streamshub.mcp.strimzi.dto.StrimziOperatorLogsResponse;
import io.streamshub.mcp.strimzi.dto.StrimziOperatorResponse;
import io.streamshub.mcp.strimzi.dto.metrics.KafkaMetricsResponse;
import io.streamshub.mcp.strimzi.dto.metrics.StrimziOperatorMetricsResponse;
import io.streamshub.mcp.strimzi.service.KafkaNodePoolService;
import io.streamshub.mcp.strimzi.service.KafkaService;
import io.streamshub.mcp.strimzi.service.KafkaTopicService;
import io.streamshub.mcp.strimzi.service.StrimziOperatorService;
import io.streamshub.mcp.strimzi.service.metrics.KafkaMetricsService;
import io.streamshub.mcp.strimzi.service.metrics.StrimziOperatorMetricsService;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * MCP integration tests verifying tool discovery, parameter binding,
 * response serialization, and error handling through the MCP protocol layer.
 */
@QuarkusTest
class McpToolsTest {

    @ConfigProperty(name = "quarkus.http.test-port")
    int testPort;

    @InjectMock
    KafkaService kafkaService;

    @InjectMock
    KafkaTopicService topicService;

    @InjectMock
    KafkaNodePoolService nodePoolService;

    @InjectMock
    StrimziOperatorService operatorService;

    @InjectMock
    PodsService podsService;

    @InjectMock
    KafkaMetricsService kafkaMetricsService;

    @InjectMock
    StrimziOperatorMetricsService strimziOperatorMetricsService;

    private McpAssured.McpSseTestClient client;

    McpToolsTest() {
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
    void testToolDiscovery() {
        client.when()
            .toolsList(page -> {
                List<String> expectedTools = List.of(
                    "list_kafka_clusters",
                    "get_kafka_cluster",
                    "get_kafka_cluster_pods",
                    "get_kafka_bootstrap_servers",
                    "get_kafka_cluster_logs",
                    "list_kafka_topics",
                    "get_kafka_topic",
                    "list_kafka_node_pools",
                    "get_kafka_node_pool",
                    "get_kafka_node_pool_pods",
                    "list_strimzi_operators",
                    "get_strimzi_operator",
                    "get_strimzi_operator_logs",
                    "get_strimzi_operator_pod",
                    "get_kafka_metrics",
                    "get_strimzi_operator_metrics"
                );

                for (String toolName : expectedTools) {
                    assertNotNull(page.findByName(toolName), "Tool '" + toolName + "' should be registered");
                }
            })
            .thenAssertResults();
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
    void testListKafkaTopics() {
        when(topicService.listTopics(null, "my-cluster")).thenReturn(List.of(
            new KafkaTopicResponse("user-events", "my-cluster", 12, 3, "Ready")
        ));

        client.when()
            .toolsCall("list_kafka_topics", Map.of("clusterName", "my-cluster"), response -> {
                assertFalse(response.isError());
                String json = response.content().getFirst().asText().text();
                assertTrue(json.contains("user-events"));
                assertTrue(json.contains("my-cluster"));
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

    @Test
    void testListKafkaNodePools() {
        when(nodePoolService.listNodePools(null, "my-cluster")).thenReturn(List.of(
            new KafkaNodePoolResponse("broker", "kafka", "my-cluster",
                List.of("broker"), 3, "jbod", "100Gi")
        ));

        client.when()
            .toolsCall("list_kafka_node_pools", Map.of("clusterName", "my-cluster"), response -> {
                assertFalse(response.isError());
                String json = response.content().getFirst().asText().text();
                assertTrue(json.contains("broker"));
                assertTrue(json.contains("my-cluster"));
            })
            .thenAssertResults();
    }

    @Test
    void testGetKafkaNodePool() {
        when(nodePoolService.getNodePool(null, "my-cluster", "broker")).thenReturn(
            new KafkaNodePoolResponse("broker", "kafka", "my-cluster",
                List.of("broker"), 3, "jbod", "100Gi")
        );

        client.when()
            .toolsCall("get_kafka_node_pool",
                Map.of("clusterName", "my-cluster", "nodePoolName", "broker"), response -> {
                    assertFalse(response.isError());
                    String json = response.content().getFirst().asText().text();
                    assertTrue(json.contains("broker"));
                    assertTrue(json.contains("100Gi"));
                })
            .thenAssertResults();
    }

    @Test
    void testGetKafkaNodePoolPods() {
        when(nodePoolService.getNodePoolPods(null, "my-cluster", "broker")).thenReturn(List.of(
            PodSummaryResponse.PodInfo.summary("my-cluster-broker-0", "Running", true, "kafka", 0, 60)
        ));

        client.when()
            .toolsCall("get_kafka_node_pool_pods",
                Map.of("clusterName", "my-cluster", "nodePoolName", "broker"), response -> {
                    assertFalse(response.isError());
                    String json = response.content().getFirst().asText().text();
                    assertTrue(json.contains("my-cluster-broker-0"));
                    assertTrue(json.contains("Running"));
                })
            .thenAssertResults();
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
        when(operatorService.getOperatorLogs(any(), any(), any())).thenReturn(
            StrimziOperatorLogsResponse.of("kafka-system",
                "INFO: Operator running normally", List.of("strimzi-operator-abc123"),
                false, 0, 1, false)
        );

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
    void testGetKafkaMetrics() {
        when(kafkaMetricsService.getKafkaMetrics(null, "my-cluster", null, null, null, null))
            .thenReturn(KafkaMetricsResponse.of("my-cluster", "kafka", "pod-scraping",
                List.of("replication"), List.of(), null));

        client.when()
            .toolsCall("get_kafka_metrics", Map.of("clusterName", "my-cluster"), response -> {
                assertFalse(response.isError());
                String json = response.content().getFirst().asText().text();
                assertTrue(json.contains("my-cluster"));
                assertTrue(json.contains("pod-scraping"));
            })
            .thenAssertResults();
    }

    @Test
    void testGetStrimziOperatorMetrics() {
        when(strimziOperatorMetricsService.getOperatorMetrics(null, null, null, null, null, null))
            .thenReturn(StrimziOperatorMetricsResponse.of("cluster-operator", "kafka-system",
                "pod-scraping", List.of("reconciliation"), List.of(), null));

        client.when()
            .toolsCall("get_strimzi_operator_metrics", Map.of(), response -> {
                assertFalse(response.isError());
                String json = response.content().getFirst().asText().text();
                assertTrue(json.contains("cluster-operator"));
                assertTrue(json.contains("pod-scraping"));
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
