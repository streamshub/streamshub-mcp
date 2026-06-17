/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.tool;

import io.quarkiverse.mcp.server.test.McpAssured;
import io.quarkus.test.junit.QuarkusTest;
import io.streamshub.mcp.common.config.ToolMetaFields;
import io.streamshub.mcp.strimzi.config.StrimziToolResources;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
                    "get_kafka_fleet_overview",
                    "get_kafka_cluster",
                    "get_strimzi_kafka_cluster_overview",
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
                    "diagnose_kafka_connect",
                    "diagnose_kafka_connector",
                    "list_kafka_bridges",
                    "get_kafka_bridge",
                    "get_kafka_bridge_pods",
                    "get_kafka_bridge_logs",
                    "list_kafka_users",
                    "get_kafka_user",
                    "list_kafka_rebalances",
                    "get_kafka_rebalance",
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
                    "get_kafka_bridge_metrics",
                    "get_kafka_connect_metrics",
                    "get_strimzi_operator_metrics",
                    "diagnose_kafka_topic",
                    "assess_upgrade_readiness",
                    "list_kafka_mirror_makers",
                    "get_kafka_mirror_maker",
                    "get_kafka_mirror_maker_pods",
                    "get_kafka_mirror_maker_logs",
                    "diagnose_kafka_mirror_maker"
                );

                for (String toolName : expectedTools) {
                    assertNotNull(page.findByName(toolName), "Tool '" + toolName + "' should be registered");
                }
            })
            .thenAssertResults();
    }

    /**
     * Verify all tools have correct _meta fields (type, resource, composite).
     */
    @Test
    void testToolMetadata() {
        client.when()
            .toolsList(page -> {
                Map<String, String[]> expected = expectedToolMetadata();
                Set<String> compositeTools = compositeToolNames();

                for (Map.Entry<String, String[]> entry : expected.entrySet()) {
                    String toolName = entry.getKey();
                    McpAssured.ToolInfo tool = page.findByName(toolName);
                    assertNotNull(tool, "Tool '" + toolName + "' should be registered");
                    assertNotNull(tool.meta(), "Tool '" + toolName + "' should have _meta");
                    assertEquals(entry.getValue()[0], tool.meta().getString(ToolMetaFields.TYPE),
                        "Tool '" + toolName + "' type mismatch");
                    assertEquals(entry.getValue()[1], tool.meta().getString(ToolMetaFields.RESOURCE),
                        "Tool '" + toolName + "' resource mismatch");
                    if (compositeTools.contains(toolName)) {
                        assertTrue(tool.meta().getBoolean(ToolMetaFields.COMPOSITE),
                            "Tool '" + toolName + "' should be composite");
                    }
                }
            })
            .thenAssertResults();
    }

    @SuppressWarnings("checkstyle:MethodLength")
    private static Map<String, String[]> expectedToolMetadata() {
        Map<String, String[]> map = new HashMap<>();
        map.put("list_kafka_clusters", new String[]{ToolMetaFields.Types.LIST, StrimziToolResources.KAFKA});
        map.put("get_kafka_fleet_overview", new String[]{ToolMetaFields.Types.OVERVIEW, StrimziToolResources.KAFKA});
        map.put("get_kafka_cluster", new String[]{ToolMetaFields.Types.GET, StrimziToolResources.KAFKA});
        map.put("get_strimzi_kafka_cluster_overview", new String[]{ToolMetaFields.Types.OVERVIEW, StrimziToolResources.KAFKA});
        map.put("get_kafka_cluster_pods", new String[]{ToolMetaFields.Types.GET, StrimziToolResources.KAFKA});
        map.put("get_kafka_bootstrap_servers", new String[]{ToolMetaFields.Types.GET, StrimziToolResources.KAFKA});
        map.put("get_kafka_cluster_certificates", new String[]{ToolMetaFields.Types.GET, StrimziToolResources.KAFKA});
        map.put("get_kafka_cluster_logs", new String[]{ToolMetaFields.Types.LOGS, StrimziToolResources.KAFKA});
        map.put("get_kafka_cluster_config", new String[]{ToolMetaFields.Types.GET, StrimziToolResources.KAFKA});
        map.put("list_kafka_topics", new String[]{ToolMetaFields.Types.LIST, StrimziToolResources.KAFKA_TOPIC});
        map.put("get_kafka_topic", new String[]{ToolMetaFields.Types.GET, StrimziToolResources.KAFKA_TOPIC});
        map.put("list_kafka_node_pools", new String[]{ToolMetaFields.Types.LIST, StrimziToolResources.KAFKA_NODE_POOL});
        map.put("get_kafka_node_pool", new String[]{ToolMetaFields.Types.GET, StrimziToolResources.KAFKA_NODE_POOL});
        map.put("get_kafka_node_pool_pods", new String[]{ToolMetaFields.Types.GET, StrimziToolResources.KAFKA_NODE_POOL});
        map.put("list_kafka_users", new String[]{ToolMetaFields.Types.LIST, StrimziToolResources.KAFKA_USER});
        map.put("get_kafka_user", new String[]{ToolMetaFields.Types.GET, StrimziToolResources.KAFKA_USER});
        map.put("list_kafka_rebalances", new String[]{ToolMetaFields.Types.LIST, StrimziToolResources.KAFKA_REBALANCE});
        map.put("get_kafka_rebalance", new String[]{ToolMetaFields.Types.GET, StrimziToolResources.KAFKA_REBALANCE});
        map.put("list_strimzi_operators", new String[]{ToolMetaFields.Types.LIST, StrimziToolResources.STRIMZI_OPERATOR});
        map.put("get_strimzi_operator", new String[]{ToolMetaFields.Types.GET, StrimziToolResources.STRIMZI_OPERATOR});
        map.put("get_strimzi_operator_logs", new String[]{ToolMetaFields.Types.LOGS, StrimziToolResources.STRIMZI_OPERATOR});
        map.put("get_strimzi_operator_pod", new String[]{ToolMetaFields.Types.GET, StrimziToolResources.STRIMZI_OPERATOR});
        map.put("get_strimzi_events", new String[]{ToolMetaFields.Types.EVENTS, StrimziToolResources.STRIMZI_EVENT});
        map.put("list_drain_cleaners", new String[]{ToolMetaFields.Types.LIST, StrimziToolResources.DRAIN_CLEANER});
        map.put("get_drain_cleaner", new String[]{ToolMetaFields.Types.GET, StrimziToolResources.DRAIN_CLEANER});
        map.put("get_drain_cleaner_logs", new String[]{ToolMetaFields.Types.LOGS, StrimziToolResources.DRAIN_CLEANER});
        map.put("check_drain_cleaner_readiness", new String[]{ToolMetaFields.Types.CHECK, StrimziToolResources.DRAIN_CLEANER});
        map.put("list_kafka_connects", new String[]{ToolMetaFields.Types.LIST, StrimziToolResources.KAFKA_CONNECT});
        map.put("get_kafka_connect", new String[]{ToolMetaFields.Types.GET, StrimziToolResources.KAFKA_CONNECT});
        map.put("get_kafka_connect_pods", new String[]{ToolMetaFields.Types.GET, StrimziToolResources.KAFKA_CONNECT});
        map.put("get_kafka_connect_logs", new String[]{ToolMetaFields.Types.LOGS, StrimziToolResources.KAFKA_CONNECT});
        map.put("list_kafka_connectors", new String[]{ToolMetaFields.Types.LIST, StrimziToolResources.KAFKA_CONNECTOR});
        map.put("get_kafka_connector", new String[]{ToolMetaFields.Types.GET, StrimziToolResources.KAFKA_CONNECTOR});
        map.put("list_kafka_bridges", new String[]{ToolMetaFields.Types.LIST, StrimziToolResources.KAFKA_BRIDGE});
        map.put("get_kafka_bridge", new String[]{ToolMetaFields.Types.GET, StrimziToolResources.KAFKA_BRIDGE});
        map.put("get_kafka_bridge_pods", new String[]{ToolMetaFields.Types.GET, StrimziToolResources.KAFKA_BRIDGE});
        map.put("get_kafka_bridge_logs", new String[]{ToolMetaFields.Types.LOGS, StrimziToolResources.KAFKA_BRIDGE});
        map.put("list_kafka_mirror_makers", new String[]{ToolMetaFields.Types.LIST, StrimziToolResources.KAFKA_MIRROR_MAKER_2});
        map.put("get_kafka_mirror_maker", new String[]{ToolMetaFields.Types.GET, StrimziToolResources.KAFKA_MIRROR_MAKER_2});
        map.put("get_kafka_mirror_maker_pods", new String[]{ToolMetaFields.Types.GET, StrimziToolResources.KAFKA_MIRROR_MAKER_2});
        map.put("get_kafka_mirror_maker_logs", new String[]{ToolMetaFields.Types.LOGS, StrimziToolResources.KAFKA_MIRROR_MAKER_2});
        map.put("get_kafka_metrics", new String[]{ToolMetaFields.Types.METRICS, StrimziToolResources.KAFKA});
        map.put("get_kafka_exporter_metrics", new String[]{ToolMetaFields.Types.METRICS, StrimziToolResources.KAFKA});
        map.put("get_kafka_bridge_metrics", new String[]{ToolMetaFields.Types.METRICS, StrimziToolResources.KAFKA_BRIDGE});
        map.put("get_kafka_connect_metrics", new String[]{ToolMetaFields.Types.METRICS, StrimziToolResources.KAFKA_CONNECT});
        map.put("get_strimzi_operator_metrics", new String[]{ToolMetaFields.Types.METRICS, StrimziToolResources.STRIMZI_OPERATOR});
        map.put("diagnose_kafka_connect", new String[]{ToolMetaFields.Types.DIAGNOSE, StrimziToolResources.KAFKA_CONNECT});
        map.put("diagnose_kafka_connector", new String[]{ToolMetaFields.Types.DIAGNOSE, StrimziToolResources.KAFKA_CONNECTOR});
        map.put("diagnose_kafka_cluster", new String[]{ToolMetaFields.Types.DIAGNOSE, StrimziToolResources.KAFKA});
        map.put("compare_kafka_clusters", new String[]{ToolMetaFields.Types.COMPARE, StrimziToolResources.KAFKA});
        map.put("diagnose_kafka_connectivity", new String[]{ToolMetaFields.Types.DIAGNOSE, StrimziToolResources.KAFKA});
        map.put("diagnose_kafka_metrics", new String[]{ToolMetaFields.Types.DIAGNOSE, StrimziToolResources.KAFKA});
        map.put("diagnose_operator_metrics", new String[]{ToolMetaFields.Types.DIAGNOSE, StrimziToolResources.STRIMZI_OPERATOR});
        map.put("diagnose_kafka_topic", new String[]{ToolMetaFields.Types.DIAGNOSE, StrimziToolResources.KAFKA_TOPIC});
        map.put("assess_upgrade_readiness", new String[]{ToolMetaFields.Types.ASSESS, StrimziToolResources.KAFKA});
        map.put("diagnose_kafka_mirror_maker", new String[]{ToolMetaFields.Types.DIAGNOSE, StrimziToolResources.KAFKA_MIRROR_MAKER_2});
        return map;
    }

    private static Set<String> compositeToolNames() {
        return Set.of(
            "get_kafka_fleet_overview",
            "get_strimzi_kafka_cluster_overview",
            "diagnose_kafka_connect",
            "diagnose_kafka_connector",
            "diagnose_kafka_cluster",
            "compare_kafka_clusters",
            "diagnose_kafka_connectivity",
            "diagnose_kafka_metrics",
            "diagnose_operator_metrics",
            "diagnose_kafka_topic",
            "assess_upgrade_readiness",
            "diagnose_kafka_mirror_maker"
        );
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
                assertNotNull(page.findByName("troubleshoot-bridge"),
                    "Prompt 'troubleshoot-bridge' should be registered");
                assertNotNull(page.findByName("troubleshoot-topic"),
                    "Prompt 'troubleshoot-topic' should be registered");
                assertNotNull(page.findByName("analyze-capacity"),
                    "Prompt 'analyze-capacity' should be registered");
                assertNotNull(page.findByName("assess-upgrade-readiness"),
                    "Prompt 'assess-upgrade-readiness' should be registered");
                assertNotNull(page.findByName("troubleshoot-mirror-maker"),
                    "Prompt 'troubleshoot-mirror-maker' should be registered");
                assertNotNull(page.findByName("troubleshoot-connect"),
                    "Prompt 'troubleshoot-connect' should be registered");
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
     * Verify troubleshoot-bridge prompt generates correct instructions.
     */
    @Test
    void testPromptGetTroubleshootBridge() {
        client.when()
            .promptsGet("troubleshoot-bridge")
            .withArguments(Map.of("bridge_name", "my-bridge", "namespace", "kafka-prod"))
            .withAssert(response -> {
                assertFalse(response.messages().isEmpty());
                String content = response.messages().getFirst().content().asText().text();
                assertTrue(content.contains("my-bridge"));
                assertTrue(content.contains("kafka-prod"));
                assertTrue(content.contains("get_kafka_bridge"));
                assertTrue(content.contains("get_kafka_bridge_pods"));
                assertTrue(content.contains("get_kafka_bridge_logs"));
            })
            .send()
            .thenAssertResults();
    }

    /**
     * Verify troubleshoot-topic prompt generates correct instructions.
     */
    @Test
    void testPromptGetTroubleshootTopic() {
        client.when()
            .promptsGet("troubleshoot-topic")
            .withArguments(Map.of("topic_name", "my-topic", "cluster_name", "my-cluster",
                "namespace", "kafka-prod"))
            .withAssert(response -> {
                assertFalse(response.messages().isEmpty());
                String content = response.messages().getFirst().content().asText().text();
                assertTrue(content.contains("my-topic"));
                assertTrue(content.contains("my-cluster"));
                assertTrue(content.contains("kafka-prod"));
                assertTrue(content.contains("get_kafka_topic"));
                assertTrue(content.contains("list_kafka_topics"));
                assertTrue(content.contains("get_strimzi_operator_logs"));
            })
            .send()
            .thenAssertResults();
    }

    /**
     * Verify analyze-capacity prompt generates correct instructions.
     */
    @Test
    void testPromptGetAnalyzeCapacity() {
        client.when()
            .promptsGet("analyze-capacity")
            .withArguments(Map.of("cluster_name", "my-cluster", "namespace", "kafka-prod"))
            .withAssert(response -> {
                assertFalse(response.messages().isEmpty());
                String content = response.messages().getFirst().content().asText().text();
                assertTrue(content.contains("my-cluster"));
                assertTrue(content.contains("kafka-prod"));
                assertTrue(content.contains("get_kafka_cluster"));
                assertTrue(content.contains("get_kafka_metrics"));
                assertTrue(content.contains("list_kafka_topics"));
            })
            .send()
            .thenAssertResults();
    }

    /**
     * Verify assess-upgrade-readiness prompt generates correct instructions.
     */
    @Test
    void testPromptGetAssessUpgradeReadiness() {
        client.when()
            .promptsGet("assess-upgrade-readiness")
            .withArguments(Map.of("cluster_name", "my-cluster", "namespace", "kafka-prod"))
            .withAssert(response -> {
                assertFalse(response.messages().isEmpty());
                String content = response.messages().getFirst().content().asText().text();
                assertTrue(content.contains("my-cluster"));
                assertTrue(content.contains("kafka-prod"));
                assertTrue(content.contains("get_kafka_cluster"));
                assertTrue(content.contains("GO/NO-GO"));
                assertTrue(content.contains("get_kafka_metrics"));
                assertTrue(content.contains("check_drain_cleaner_readiness"));
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
