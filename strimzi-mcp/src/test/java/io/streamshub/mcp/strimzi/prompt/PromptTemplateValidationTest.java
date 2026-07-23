/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.prompt;

import io.quarkiverse.mcp.server.PromptResponse;
import io.quarkiverse.mcp.server.Role;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for prompt template string formatting, null safety, and content
 * validation. These tests instantiate prompt classes directly (no MCP client
 * or Quarkus container needed) to catch formatting bugs early.
 */
class PromptTemplateValidationTest {

    PromptTemplateValidationTest() {
    }

    @Test
    void testDiagnoseClusterIssueWithAllArgs() {
        DiagnoseClusterIssuePrompt prompt = new DiagnoseClusterIssuePrompt();
        PromptResponse response = prompt.diagnoseClusterIssue("my-cluster", "kafka-prod", "high latency");
        String content = extractContent(response);
        assertValidPromptOutput(content, "diagnose-cluster-issue");
        assertSystemMessage(response, "diagnose-cluster-issue");
        assertTrue(content.contains("my-cluster"));
        assertTrue(content.contains("kafka-prod"));
        assertTrue(content.contains("high latency"));
    }

    @Test
    void testDiagnoseClusterIssueWithRequiredOnly() {
        DiagnoseClusterIssuePrompt prompt = new DiagnoseClusterIssuePrompt();
        PromptResponse response = prompt.diagnoseClusterIssue("my-cluster", null, null);
        String content = extractContent(response);
        assertValidPromptOutput(content, "diagnose-cluster-issue");
        assertSystemMessage(response, "diagnose-cluster-issue");
        assertTrue(content.contains("my-cluster"));
    }

    @Test
    void testTroubleshootConnectivityWithAllArgs() {
        TroubleshootConnectivityPrompt prompt = new TroubleshootConnectivityPrompt();
        PromptResponse response = prompt.troubleshootConnectivity("my-cluster", "kafka-prod", "tls-listener");
        String content = extractContent(response);
        assertValidPromptOutput(content, "troubleshoot-connectivity");
        assertSystemMessage(response, "troubleshoot-connectivity");
        assertTrue(content.contains("my-cluster"));
        assertTrue(content.contains("kafka-prod"));
    }

    @Test
    void testTroubleshootConnectivityWithRequiredOnly() {
        TroubleshootConnectivityPrompt prompt = new TroubleshootConnectivityPrompt();
        PromptResponse response = prompt.troubleshootConnectivity("my-cluster", null, null);
        String content = extractContent(response);
        assertValidPromptOutput(content, "troubleshoot-connectivity");
        assertSystemMessage(response, "troubleshoot-connectivity");
        assertTrue(content.contains("my-cluster"));
    }

    @Test
    void testAnalyzeKafkaMetricsWithAllArgs() {
        AnalyzeKafkaMetricsPrompt prompt = new AnalyzeKafkaMetricsPrompt();
        PromptResponse response = prompt.analyzeKafkaMetrics("my-cluster", "kafka-prod", "disk usage");
        String content = extractContent(response);
        assertValidPromptOutput(content, "analyze-kafka-metrics");
        assertSystemMessage(response, "analyze-kafka-metrics");
        assertTrue(content.contains("my-cluster"));
        assertTrue(content.contains("kafka-prod"));
    }

    @Test
    void testAnalyzeKafkaMetricsWithRequiredOnly() {
        AnalyzeKafkaMetricsPrompt prompt = new AnalyzeKafkaMetricsPrompt();
        PromptResponse response = prompt.analyzeKafkaMetrics("my-cluster", null, null);
        String content = extractContent(response);
        assertValidPromptOutput(content, "analyze-kafka-metrics");
        assertSystemMessage(response, "analyze-kafka-metrics");
        assertTrue(content.contains("my-cluster"));
    }

    @Test
    void testAnalyzeStrimziOperatorMetricsWithAllArgs() {
        AnalyzeStrimziOperatorMetricsPrompt prompt = new AnalyzeStrimziOperatorMetricsPrompt();
        PromptResponse response = prompt.analyzeStrimziOperatorMetrics("kafka-system", "slow reconciliation");
        String content = extractContent(response);
        assertValidPromptOutput(content, "analyze-strimzi-operator-metrics");
        assertSystemMessage(response, "analyze-strimzi-operator-metrics");
        assertTrue(content.contains("kafka-system"));
    }

    @Test
    void testAnalyzeStrimziOperatorMetricsWithNoArgs() {
        AnalyzeStrimziOperatorMetricsPrompt prompt = new AnalyzeStrimziOperatorMetricsPrompt();
        PromptResponse response = prompt.analyzeStrimziOperatorMetrics(null, null);
        String content = extractContent(response);
        assertValidPromptOutput(content, "analyze-strimzi-operator-metrics");
        assertSystemMessage(response, "analyze-strimzi-operator-metrics");
    }

    @Test
    void testCompareClusterConfigsWithAllArgs() {
        CompareClusterConfigsPrompt prompt = new CompareClusterConfigsPrompt();
        PromptResponse response = prompt.compareClusterConfigs("cluster-a", "cluster-b", "ns-a", "ns-b");
        String content = extractContent(response);
        assertValidPromptOutput(content, "compare-cluster-configs");
        assertSystemMessage(response, "compare-cluster-configs");
        assertTrue(content.contains("cluster-a"));
        assertTrue(content.contains("cluster-b"));
        assertTrue(content.contains("ns-a"));
        assertTrue(content.contains("ns-b"));
    }

    @Test
    void testCompareClusterConfigsWithRequiredOnly() {
        CompareClusterConfigsPrompt prompt = new CompareClusterConfigsPrompt();
        PromptResponse response = prompt.compareClusterConfigs("cluster-a", "cluster-b", null, null);
        String content = extractContent(response);
        assertValidPromptOutput(content, "compare-cluster-configs");
        assertSystemMessage(response, "compare-cluster-configs");
        assertTrue(content.contains("cluster-a"));
        assertTrue(content.contains("cluster-b"));
    }

    @Test
    void testAuditSecurityWithAllArgs() {
        AuditSecurityPrompt prompt = new AuditSecurityPrompt();
        PromptResponse response = prompt.auditSecurity("my-cluster", "kafka-prod");
        String content = extractContent(response);
        assertValidPromptOutput(content, "audit-security");
        assertSystemMessage(response, "audit-security");
        assertTrue(content.contains("my-cluster"));
        assertTrue(content.contains("kafka-prod"));
    }

    @Test
    void testAuditSecurityWithRequiredOnly() {
        AuditSecurityPrompt prompt = new AuditSecurityPrompt();
        PromptResponse response = prompt.auditSecurity("my-cluster", null);
        String content = extractContent(response);
        assertValidPromptOutput(content, "audit-security");
        assertSystemMessage(response, "audit-security");
        assertTrue(content.contains("my-cluster"));
    }

    @Test
    void testAssessUpgradeReadinessWithAllArgs() {
        AssessUpgradeReadinessPrompt prompt = new AssessUpgradeReadinessPrompt();
        PromptResponse response = prompt.assessUpgradeReadiness("my-cluster", "kafka-prod", "4.2.0");
        String content = extractContent(response);
        assertValidPromptOutput(content, "assess-upgrade-readiness");
        assertSystemMessage(response, "assess-upgrade-readiness");
        assertTrue(content.contains("my-cluster"));
        assertTrue(content.contains("kafka-prod"));
        assertTrue(content.contains("4.2.0"));
    }

    @Test
    void testAssessUpgradeReadinessWithRequiredOnly() {
        AssessUpgradeReadinessPrompt prompt = new AssessUpgradeReadinessPrompt();
        PromptResponse response = prompt.assessUpgradeReadiness("my-cluster", null, null);
        String content = extractContent(response);
        assertValidPromptOutput(content, "assess-upgrade-readiness");
        assertSystemMessage(response, "assess-upgrade-readiness");
        assertTrue(content.contains("my-cluster"));
    }

    @Test
    void testAnalyzeCapacityWithAllArgs() {
        AnalyzeCapacityPrompt prompt = new AnalyzeCapacityPrompt();
        PromptResponse response = prompt.analyzeCapacity("my-cluster", "kafka-prod", "storage");
        String content = extractContent(response);
        assertValidPromptOutput(content, "analyze-capacity");
        assertSystemMessage(response, "analyze-capacity");
        assertTrue(content.contains("my-cluster"));
        assertTrue(content.contains("kafka-prod"));
    }

    @Test
    void testAnalyzeCapacityWithRequiredOnly() {
        AnalyzeCapacityPrompt prompt = new AnalyzeCapacityPrompt();
        PromptResponse response = prompt.analyzeCapacity("my-cluster", null, null);
        String content = extractContent(response);
        assertValidPromptOutput(content, "analyze-capacity");
        assertSystemMessage(response, "analyze-capacity");
        assertTrue(content.contains("my-cluster"));
    }

    @Test
    void testTroubleshootConnectorWithAllArgs() {
        TroubleshootConnectorPrompt prompt = new TroubleshootConnectorPrompt();
        PromptResponse response = prompt.troubleshootConnector("my-connector", "kafka-prod", "my-connect", "failed tasks");
        String content = extractContent(response);
        assertValidPromptOutput(content, "troubleshoot-connector");
        assertSystemMessage(response, "troubleshoot-connector");
        assertTrue(content.contains("my-connector"));
        assertTrue(content.contains("kafka-prod"));
    }

    @Test
    void testTroubleshootConnectorWithRequiredOnly() {
        TroubleshootConnectorPrompt prompt = new TroubleshootConnectorPrompt();
        PromptResponse response = prompt.troubleshootConnector("my-connector", null, null, null);
        String content = extractContent(response);
        assertValidPromptOutput(content, "troubleshoot-connector");
        assertSystemMessage(response, "troubleshoot-connector");
        assertTrue(content.contains("my-connector"));
    }

    @Test
    void testTroubleshootBridgeWithAllArgs() {
        TroubleshootBridgePrompt prompt = new TroubleshootBridgePrompt();
        PromptResponse response = prompt.troubleshootBridge("my-bridge", "kafka-prod", "connection refused");
        String content = extractContent(response);
        assertValidPromptOutput(content, "troubleshoot-bridge");
        assertSystemMessage(response, "troubleshoot-bridge");
        assertTrue(content.contains("my-bridge"));
        assertTrue(content.contains("kafka-prod"));
    }

    @Test
    void testTroubleshootBridgeWithRequiredOnly() {
        TroubleshootBridgePrompt prompt = new TroubleshootBridgePrompt();
        PromptResponse response = prompt.troubleshootBridge("my-bridge", null, null);
        String content = extractContent(response);
        assertValidPromptOutput(content, "troubleshoot-bridge");
        assertSystemMessage(response, "troubleshoot-bridge");
        assertTrue(content.contains("my-bridge"));
    }

    @Test
    void testTroubleshootTopicWithAllArgs() {
        TroubleshootTopicPrompt prompt = new TroubleshootTopicPrompt();
        PromptResponse response = prompt.troubleshootTopic("my-topic", "my-cluster", "kafka-prod", "under-replicated");
        String content = extractContent(response);
        assertValidPromptOutput(content, "troubleshoot-topic");
        assertSystemMessage(response, "troubleshoot-topic");
        assertTrue(content.contains("my-topic"));
        assertTrue(content.contains("my-cluster"));
        assertTrue(content.contains("kafka-prod"));
    }

    @Test
    void testTroubleshootTopicWithRequiredOnly() {
        TroubleshootTopicPrompt prompt = new TroubleshootTopicPrompt();
        PromptResponse response = prompt.troubleshootTopic("my-topic", "my-cluster", null, null);
        String content = extractContent(response);
        assertValidPromptOutput(content, "troubleshoot-topic");
        assertSystemMessage(response, "troubleshoot-topic");
        assertTrue(content.contains("my-topic"));
        assertTrue(content.contains("my-cluster"));
    }

    @Test
    void testTroubleshootConnectWithAllArgs() {
        TroubleshootConnectPrompt prompt = new TroubleshootConnectPrompt();
        PromptResponse response = prompt.troubleshootConnect("my-connect", "kafka-prod", "OOM restarts");
        String content = extractContent(response);
        assertValidPromptOutput(content, "troubleshoot-connect");
        assertSystemMessage(response, "troubleshoot-connect");
        assertTrue(content.contains("my-connect"));
        assertTrue(content.contains("kafka-prod"));
    }

    @Test
    void testTroubleshootConnectWithRequiredOnly() {
        TroubleshootConnectPrompt prompt = new TroubleshootConnectPrompt();
        PromptResponse response = prompt.troubleshootConnect("my-connect", null, null);
        String content = extractContent(response);
        assertValidPromptOutput(content, "troubleshoot-connect");
        assertSystemMessage(response, "troubleshoot-connect");
        assertTrue(content.contains("my-connect"));
    }

    @Test
    void testTroubleshootMirrorMakerWithAllArgs() {
        TroubleshootMirrorMakerPrompt prompt = new TroubleshootMirrorMakerPrompt();
        PromptResponse response = prompt.troubleshootMirrorMaker("my-mm2", "kafka-prod", "replication lag");
        String content = extractContent(response);
        assertValidPromptOutput(content, "troubleshoot-mirror-maker");
        assertSystemMessage(response, "troubleshoot-mirror-maker");
        assertTrue(content.contains("my-mm2"));
        assertTrue(content.contains("kafka-prod"));
    }

    @Test
    void testTroubleshootMirrorMakerWithRequiredOnly() {
        TroubleshootMirrorMakerPrompt prompt = new TroubleshootMirrorMakerPrompt();
        PromptResponse response = prompt.troubleshootMirrorMaker("my-mm2", null, null);
        String content = extractContent(response);
        assertValidPromptOutput(content, "troubleshoot-mirror-maker");
        assertSystemMessage(response, "troubleshoot-mirror-maker");
        assertTrue(content.contains("my-mm2"));
    }

    private static String extractContent(PromptResponse response) {
        assertEquals(2, response.messages().size(),
            "Prompt response should contain assistant and user messages");
        assertEquals(Role.ASSISTANT, response.messages().get(0).role(),
            "First message should have assistant role");
        assertEquals(Role.USER, response.messages().get(1).role(),
            "Second message should have user role");
        return response.messages().get(1).content().asText().text();
    }

    private static void assertValidPromptOutput(String content, String promptName) {
        assertFalse(content.isBlank(),
            promptName + " produced blank output");
        assertFalse(content.contains("%s"),
            promptName + " contains unresolved %s placeholder");
        assertFalse(content.contains("'null'"),
            promptName + " contains literal 'null' value in tool argument");
        assertFalse(content.contains("namespace='null'"),
            promptName + " injects null namespace into tool call");
    }

    private static void assertSystemMessage(PromptResponse response, String promptName) {
        String systemContent = response.messages().get(0).content().asText().text();
        assertTrue(systemContent.contains("expert Kafka and Strimzi SRE assistant"),
            promptName + " system message missing persona");
        assertTrue(systemContent.contains("IMPORTANT: If any tool call fails"),
            promptName + " system message missing ERROR_HANDLING_INSTRUCTION");
    }
}
