/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.prompt;

import io.quarkiverse.mcp.server.PromptResponse;
import org.junit.jupiter.api.Test;

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
        String content = extractContent(prompt.diagnoseClusterIssue("my-cluster", "kafka-prod", "high latency"));
        assertValidPromptOutput(content, "diagnose-cluster-issue");
        assertTrue(content.contains("my-cluster"));
        assertTrue(content.contains("kafka-prod"));
        assertTrue(content.contains("high latency"));
    }

    @Test
    void testDiagnoseClusterIssueWithRequiredOnly() {
        DiagnoseClusterIssuePrompt prompt = new DiagnoseClusterIssuePrompt();
        String content = extractContent(prompt.diagnoseClusterIssue("my-cluster", null, null));
        assertValidPromptOutput(content, "diagnose-cluster-issue");
        assertTrue(content.contains("my-cluster"));
    }

    @Test
    void testTroubleshootConnectivityWithAllArgs() {
        TroubleshootConnectivityPrompt prompt = new TroubleshootConnectivityPrompt();
        String content = extractContent(prompt.troubleshootConnectivity("my-cluster", "kafka-prod", "tls-listener"));
        assertValidPromptOutput(content, "troubleshoot-connectivity");
        assertTrue(content.contains("my-cluster"));
        assertTrue(content.contains("kafka-prod"));
    }

    @Test
    void testTroubleshootConnectivityWithRequiredOnly() {
        TroubleshootConnectivityPrompt prompt = new TroubleshootConnectivityPrompt();
        String content = extractContent(prompt.troubleshootConnectivity("my-cluster", null, null));
        assertValidPromptOutput(content, "troubleshoot-connectivity");
        assertTrue(content.contains("my-cluster"));
    }

    @Test
    void testAnalyzeKafkaMetricsWithAllArgs() {
        AnalyzeKafkaMetricsPrompt prompt = new AnalyzeKafkaMetricsPrompt();
        String content = extractContent(prompt.analyzeKafkaMetrics("my-cluster", "kafka-prod", "disk usage"));
        assertValidPromptOutput(content, "analyze-kafka-metrics");
        assertTrue(content.contains("my-cluster"));
        assertTrue(content.contains("kafka-prod"));
    }

    @Test
    void testAnalyzeKafkaMetricsWithRequiredOnly() {
        AnalyzeKafkaMetricsPrompt prompt = new AnalyzeKafkaMetricsPrompt();
        String content = extractContent(prompt.analyzeKafkaMetrics("my-cluster", null, null));
        assertValidPromptOutput(content, "analyze-kafka-metrics");
        assertTrue(content.contains("my-cluster"));
    }

    @Test
    void testAnalyzeStrimziOperatorMetricsWithAllArgs() {
        AnalyzeStrimziOperatorMetricsPrompt prompt = new AnalyzeStrimziOperatorMetricsPrompt();
        String content = extractContent(prompt.analyzeStrimziOperatorMetrics("kafka-system", "slow reconciliation"));
        assertValidPromptOutput(content, "analyze-strimzi-operator-metrics");
        assertTrue(content.contains("kafka-system"));
    }

    @Test
    void testAnalyzeStrimziOperatorMetricsWithNoArgs() {
        AnalyzeStrimziOperatorMetricsPrompt prompt = new AnalyzeStrimziOperatorMetricsPrompt();
        String content = extractContent(prompt.analyzeStrimziOperatorMetrics(null, null));
        assertValidPromptOutput(content, "analyze-strimzi-operator-metrics");
    }

    @Test
    void testCompareClusterConfigsWithAllArgs() {
        CompareClusterConfigsPrompt prompt = new CompareClusterConfigsPrompt();
        String content = extractContent(prompt.compareClusterConfigs("cluster-a", "cluster-b", "ns-a", "ns-b"));
        assertValidPromptOutput(content, "compare-cluster-configs");
        assertTrue(content.contains("cluster-a"));
        assertTrue(content.contains("cluster-b"));
        assertTrue(content.contains("ns-a"));
        assertTrue(content.contains("ns-b"));
    }

    @Test
    void testCompareClusterConfigsWithRequiredOnly() {
        CompareClusterConfigsPrompt prompt = new CompareClusterConfigsPrompt();
        String content = extractContent(prompt.compareClusterConfigs("cluster-a", "cluster-b", null, null));
        assertValidPromptOutput(content, "compare-cluster-configs");
        assertTrue(content.contains("cluster-a"));
        assertTrue(content.contains("cluster-b"));
    }

    @Test
    void testAuditSecurityWithAllArgs() {
        AuditSecurityPrompt prompt = new AuditSecurityPrompt();
        String content = extractContent(prompt.auditSecurity("my-cluster", "kafka-prod"));
        assertValidPromptOutput(content, "audit-security");
        assertTrue(content.contains("my-cluster"));
        assertTrue(content.contains("kafka-prod"));
    }

    @Test
    void testAuditSecurityWithRequiredOnly() {
        AuditSecurityPrompt prompt = new AuditSecurityPrompt();
        String content = extractContent(prompt.auditSecurity("my-cluster", null));
        assertValidPromptOutput(content, "audit-security");
        assertTrue(content.contains("my-cluster"));
    }

    @Test
    void testAssessUpgradeReadinessWithAllArgs() {
        AssessUpgradeReadinessPrompt prompt = new AssessUpgradeReadinessPrompt();
        String content = extractContent(prompt.assessUpgradeReadiness("my-cluster", "kafka-prod", "4.2.0"));
        assertValidPromptOutput(content, "assess-upgrade-readiness");
        assertTrue(content.contains("my-cluster"));
        assertTrue(content.contains("kafka-prod"));
        assertTrue(content.contains("4.2.0"));
    }

    @Test
    void testAssessUpgradeReadinessWithRequiredOnly() {
        AssessUpgradeReadinessPrompt prompt = new AssessUpgradeReadinessPrompt();
        String content = extractContent(prompt.assessUpgradeReadiness("my-cluster", null, null));
        assertValidPromptOutput(content, "assess-upgrade-readiness");
        assertTrue(content.contains("my-cluster"));
    }

    @Test
    void testAnalyzeCapacityWithAllArgs() {
        AnalyzeCapacityPrompt prompt = new AnalyzeCapacityPrompt();
        String content = extractContent(prompt.analyzeCapacity("my-cluster", "kafka-prod", "storage"));
        assertValidPromptOutput(content, "analyze-capacity");
        assertTrue(content.contains("my-cluster"));
        assertTrue(content.contains("kafka-prod"));
    }

    @Test
    void testAnalyzeCapacityWithRequiredOnly() {
        AnalyzeCapacityPrompt prompt = new AnalyzeCapacityPrompt();
        String content = extractContent(prompt.analyzeCapacity("my-cluster", null, null));
        assertValidPromptOutput(content, "analyze-capacity");
        assertTrue(content.contains("my-cluster"));
    }

    @Test
    void testTroubleshootConnectorWithAllArgs() {
        TroubleshootConnectorPrompt prompt = new TroubleshootConnectorPrompt();
        String content = extractContent(prompt.troubleshootConnector("my-connector", "kafka-prod", "my-connect", "failed tasks"));
        assertValidPromptOutput(content, "troubleshoot-connector");
        assertTrue(content.contains("my-connector"));
        assertTrue(content.contains("kafka-prod"));
    }

    @Test
    void testTroubleshootConnectorWithRequiredOnly() {
        TroubleshootConnectorPrompt prompt = new TroubleshootConnectorPrompt();
        String content = extractContent(prompt.troubleshootConnector("my-connector", null, null, null));
        assertValidPromptOutput(content, "troubleshoot-connector");
        assertTrue(content.contains("my-connector"));
    }

    @Test
    void testTroubleshootBridgeWithAllArgs() {
        TroubleshootBridgePrompt prompt = new TroubleshootBridgePrompt();
        String content = extractContent(prompt.troubleshootBridge("my-bridge", "kafka-prod", "connection refused"));
        assertValidPromptOutput(content, "troubleshoot-bridge");
        assertTrue(content.contains("my-bridge"));
        assertTrue(content.contains("kafka-prod"));
    }

    @Test
    void testTroubleshootBridgeWithRequiredOnly() {
        TroubleshootBridgePrompt prompt = new TroubleshootBridgePrompt();
        String content = extractContent(prompt.troubleshootBridge("my-bridge", null, null));
        assertValidPromptOutput(content, "troubleshoot-bridge");
        assertTrue(content.contains("my-bridge"));
    }

    @Test
    void testTroubleshootTopicWithAllArgs() {
        TroubleshootTopicPrompt prompt = new TroubleshootTopicPrompt();
        String content = extractContent(prompt.troubleshootTopic("my-topic", "my-cluster", "kafka-prod", "under-replicated"));
        assertValidPromptOutput(content, "troubleshoot-topic");
        assertTrue(content.contains("my-topic"));
        assertTrue(content.contains("my-cluster"));
        assertTrue(content.contains("kafka-prod"));
    }

    @Test
    void testTroubleshootTopicWithRequiredOnly() {
        TroubleshootTopicPrompt prompt = new TroubleshootTopicPrompt();
        String content = extractContent(prompt.troubleshootTopic("my-topic", "my-cluster", null, null));
        assertValidPromptOutput(content, "troubleshoot-topic");
        assertTrue(content.contains("my-topic"));
        assertTrue(content.contains("my-cluster"));
    }

    @Test
    void testTroubleshootConnectWithAllArgs() {
        TroubleshootConnectPrompt prompt = new TroubleshootConnectPrompt();
        String content = extractContent(prompt.troubleshootConnect("my-connect", "kafka-prod", "OOM restarts"));
        assertValidPromptOutput(content, "troubleshoot-connect");
        assertTrue(content.contains("my-connect"));
        assertTrue(content.contains("kafka-prod"));
    }

    @Test
    void testTroubleshootConnectWithRequiredOnly() {
        TroubleshootConnectPrompt prompt = new TroubleshootConnectPrompt();
        String content = extractContent(prompt.troubleshootConnect("my-connect", null, null));
        assertValidPromptOutput(content, "troubleshoot-connect");
        assertTrue(content.contains("my-connect"));
    }

    @Test
    void testTroubleshootMirrorMakerWithAllArgs() {
        TroubleshootMirrorMakerPrompt prompt = new TroubleshootMirrorMakerPrompt();
        String content = extractContent(prompt.troubleshootMirrorMaker("my-mm2", "kafka-prod", "replication lag"));
        assertValidPromptOutput(content, "troubleshoot-mirror-maker");
        assertTrue(content.contains("my-mm2"));
        assertTrue(content.contains("kafka-prod"));
    }

    @Test
    void testTroubleshootMirrorMakerWithRequiredOnly() {
        TroubleshootMirrorMakerPrompt prompt = new TroubleshootMirrorMakerPrompt();
        String content = extractContent(prompt.troubleshootMirrorMaker("my-mm2", null, null));
        assertValidPromptOutput(content, "troubleshoot-mirror-maker");
        assertTrue(content.contains("my-mm2"));
    }

    private static String extractContent(PromptResponse response) {
        assertFalse(response.messages().isEmpty(), "Prompt response should contain messages");
        return response.messages().getFirst().content().asText().text();
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
        assertTrue(content.contains("IMPORTANT: If any tool call fails"),
            promptName + " missing ERROR_HANDLING_INSTRUCTION");
    }
}
