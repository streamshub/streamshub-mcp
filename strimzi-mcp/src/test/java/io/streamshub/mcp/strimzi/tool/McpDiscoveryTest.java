/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.tool;

import io.quarkiverse.mcp.server.test.McpAssured;
import io.quarkus.test.junit.QuarkusTest;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MCP integration tests for tool, prompt, and resource template discovery.
 */
@QuarkusTest
class McpDiscoveryTest {

    @ConfigProperty(name = "quarkus.http.test-port")
    int testPort;

    private McpAssured.McpSseTestClient client;

    McpDiscoveryTest() {
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
     * Verify all tools are registered.
     */
    @Test
    void testToolDiscovery() {
        client.when()
            .toolsList(page -> {
                List<String> expectedTools = List.of(
                    "list_kafka_clusters",
                    "get_kafka_cluster",
                    "get_kafka_cluster_pods",
                    "get_kafka_bootstrap_servers",
                    "get_kafka_cluster_certificates",
                    "get_kafka_cluster_logs",
                    "get_kafka_cluster_config",
                    "list_kafka_topics",
                    "get_kafka_topic",
                    "list_kafka_node_pools",
                    "get_kafka_node_pool",
                    "get_kafka_node_pool_pods",
                    "list_strimzi_operators",
                    "get_strimzi_operator",
                    "get_strimzi_operator_logs",
                    "get_strimzi_operator_pod",
                    "get_strimzi_events",
                    "list_kafka_connects",
                    "get_kafka_connect",
                    "get_kafka_connect_pods",
                    "get_kafka_connect_logs",
                    "list_kafka_connectors",
                    "get_kafka_connector",
                    "diagnose_kafka_connector",
                    "list_kafka_bridges",
                    "get_kafka_bridge",
                    "get_kafka_bridge_pods",
                    "get_kafka_bridge_logs",
                    "list_kafka_users",
                    "get_kafka_user",
                    "diagnose_kafka_cluster",
                    "diagnose_kafka_connectivity",
                    "diagnose_kafka_metrics",
                    "diagnose_operator_metrics",
                    "compare_kafka_clusters",
                    "list_drain_cleaners",
                    "get_drain_cleaner",
                    "get_drain_cleaner_logs",
                    "check_drain_cleaner_readiness",
                    "get_kafka_metrics",
                    "get_kafka_exporter_metrics",
                    "get_strimzi_operator_metrics"
                );

                for (String toolName : expectedTools) {
                    assertNotNull(page.findByName(toolName), "Tool '" + toolName + "' should be registered");
                }
            })
            .thenAssertResults();
    }

    /**
     * Verify both prompt templates are registered.
     */
    @Test
    void testPromptDiscovery() {
        client.when()
            .promptsList()
            .withAssert(page -> {
                assertNotNull(page.findByName("diagnose-cluster-issue"),
                    "Prompt 'diagnose-cluster-issue' should be registered");
                assertNotNull(page.findByName("troubleshoot-connectivity"),
                    "Prompt 'troubleshoot-connectivity' should be registered");
                assertNotNull(page.findByName("analyze-kafka-metrics"),
                    "Prompt 'analyze-kafka-metrics' should be registered");
                assertNotNull(page.findByName("analyze-strimzi-operator-metrics"),
                    "Prompt 'analyze-strimzi-operator-metrics' should be registered");
                assertNotNull(page.findByName("troubleshoot-connector"),
                    "Prompt 'troubleshoot-connector' should be registered");
                assertNotNull(page.findByName("compare-cluster-configs"),
                    "Prompt 'compare-cluster-configs' should be registered");
                assertNotNull(page.findByName("audit-security"),
                    "Prompt 'audit-security' should be registered");
            })
            .send()
            .thenAssertResults();
    }

    /**
     * Verify all 6 resource templates are registered.
     */
    @Test
    void testResourceTemplateDiscovery() {
        client.when()
            .resourcesTemplatesList()
            .withAssert(page -> {
                List<String> expectedUris = List.of(
                    "strimzi://kafka.strimzi.io/namespaces/{namespace}/kafkas/{name}/status",
                    "strimzi://kafka.strimzi.io/namespaces/{namespace}/kafkas/{name}/topology",
                    "strimzi://kafka.strimzi.io/namespaces/{namespace}/kafkanodepools/{name}/status",
                    "strimzi://kafka.strimzi.io/namespaces/{namespace}/kafkatopics/{name}/status",
                    "strimzi://kafka.strimzi.io/namespaces/{namespace}/kafkausers/{name}/status",
                    "strimzi://operator.strimzi.io/namespaces/{namespace}/clusteroperator/{name}/status"
                );

                for (String uri : expectedUris) {
                    assertNotNull(page.findByUriTemplate(uri),
                        "Resource template '" + uri + "' should be registered");
                }
            })
            .send()
            .thenAssertResults();
    }

    /**
     * Verify diagnose-cluster-issue prompt generates correct instructions.
     */
    @Test
    void testPromptGetDiagnoseClusterIssue() {
        client.when()
            .promptsGet("diagnose-cluster-issue")
            .withArguments(Map.of("cluster_name", "my-cluster", "namespace", "kafka-prod"))
            .withAssert(response -> {
                assertFalse(response.messages().isEmpty());
                String content = response.messages().getFirst().content().asText().text();
                assertTrue(content.contains("my-cluster"));
                assertTrue(content.contains("kafka-prod"));
                assertTrue(content.contains("get_kafka_cluster"));
            })
            .send()
            .thenAssertResults();
    }

    /**
     * Verify analyze-kafka-metrics prompt generates correct instructions.
     */
    @Test
    void testPromptGetAnalyzeKafkaMetrics() {
        client.when()
            .promptsGet("analyze-kafka-metrics")
            .withArguments(Map.of("cluster_name", "my-cluster", "namespace", "kafka-prod"))
            .withAssert(response -> {
                assertFalse(response.messages().isEmpty());
                String content = response.messages().getFirst().content().asText().text();
                assertTrue(content.contains("my-cluster"));
                assertTrue(content.contains("get_kafka_metrics"));
                assertTrue(content.contains("replication"));
                assertTrue(content.contains("throughput"));
                assertTrue(content.contains("performance"));
            })
            .send()
            .thenAssertResults();
    }

    /**
     * Verify compare-cluster-configs prompt generates correct instructions.
     */
    @Test
    void testPromptGetCompareClusterConfigs() {
        client.when()
            .promptsGet("compare-cluster-configs")
            .withArguments(Map.of(
                "cluster_name_1", "cluster-a",
                "cluster_name_2", "cluster-b",
                "namespace_1", "ns-a"))
            .withAssert(response -> {
                assertFalse(response.messages().isEmpty());
                String content = response.messages().getFirst().content().asText().text();
                assertTrue(content.contains("cluster-a"));
                assertTrue(content.contains("cluster-b"));
                assertTrue(content.contains("get_kafka_cluster_config"));
            })
            .send()
            .thenAssertResults();
    }

    /**
     * Verify troubleshoot-connector prompt generates correct instructions.
     */
    @Test
    void testPromptGetTroubleshootConnector() {
        client.when()
            .promptsGet("troubleshoot-connector")
            .withArguments(Map.of("connector_name", "my-debezium", "namespace", "kafka-prod"))
            .withAssert(response -> {
                assertFalse(response.messages().isEmpty());
                String content = response.messages().getFirst().content().asText().text();
                assertTrue(content.contains("my-debezium"));
                assertTrue(content.contains("kafka-prod"));
                assertTrue(content.contains("get_kafka_connector"));
                assertTrue(content.contains("get_kafka_connect"));
                assertTrue(content.contains("get_kafka_connect_logs"));
            })
            .send()
            .thenAssertResults();
    }

    /**
     * Verify audit-security prompt generates correct instructions.
     */
    @Test
    void testPromptGetAuditSecurity() {
        client.when()
            .promptsGet("audit-security")
            .withArguments(Map.of("cluster_name", "my-cluster", "namespace", "kafka-prod"))
            .withAssert(response -> {
                assertFalse(response.messages().isEmpty());
                String content = response.messages().getFirst().content().asText().text();
                assertTrue(content.contains("my-cluster"));
                assertTrue(content.contains("kafka-prod"));
                assertTrue(content.contains("list_kafka_users"));
                assertTrue(content.contains("get_kafka_user"));
                assertTrue(content.contains("get_kafka_cluster"));
                assertTrue(content.contains("get_kafka_cluster_certificates"));
            })
            .send()
            .thenAssertResults();
    }

    /**
     * Verify analyze-strimzi-operator-metrics prompt generates correct instructions.
     */
    @Test
    void testPromptGetAnalyzeStrimziOperatorMetrics() {
        client.when()
            .promptsGet("analyze-strimzi-operator-metrics")
            .withArguments(Map.of("namespace", "kafka-system"))
            .withAssert(response -> {
                assertFalse(response.messages().isEmpty());
                String content = response.messages().getFirst().content().asText().text();
                assertTrue(content.contains("kafka-system"));
                assertTrue(content.contains("get_strimzi_operator_metrics"));
                assertTrue(content.contains("reconciliation"));
            })
            .send()
            .thenAssertResults();
    }
}
