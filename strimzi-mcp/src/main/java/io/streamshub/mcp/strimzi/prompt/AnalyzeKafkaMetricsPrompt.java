/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.prompt;

import io.quarkiverse.mcp.server.Prompt;
import io.quarkiverse.mcp.server.PromptArg;
import io.quarkiverse.mcp.server.PromptMessage;
import io.quarkiverse.mcp.server.PromptResponse;
import jakarta.inject.Singleton;

import java.util.List;

/**
 * MCP prompt template for analyzing Kafka cluster metrics.
 *
 * <p>Guides the LLM through a structured metrics analysis workflow:
 * query metrics by category, interpret values against known thresholds,
 * and correlate findings to diagnose cluster health issues.</p>
 */
@Singleton
public class AnalyzeKafkaMetricsPrompt {

    AnalyzeKafkaMetricsPrompt() {
    }

    /**
     * Generate a metrics analysis prompt for a Kafka cluster.
     *
     * @param clusterName the name of the Kafka cluster
     * @param namespace   the Kubernetes namespace of the cluster
     * @param concern     optional description of the concern to investigate
     * @return prompt response with metrics analysis instructions
     */
    @Prompt(
        name = "analyze-kafka-metrics",
        description = "Step-by-step analysis of Kafka cluster metrics."
            + " Guides through replication, throughput, performance, and resource metrics."
    )
    public PromptResponse analyzeKafkaMetrics(
        @PromptArg(
            name = "cluster_name",
            description = "Name of the Kafka cluster to analyze."
        ) final String clusterName,
        @PromptArg(
            name = "namespace",
            description = "Kubernetes namespace where the Kafka cluster is deployed.",
            required = false
        ) final String namespace,
        @PromptArg(
            name = "concern",
            description = "Specific concern to investigate, e.g. 'high latency', "
                + "'replication lag', 'consumer lag'.",
            required = false
        ) final String concern
    ) {
        String nsArg = namespace != null && !namespace.isBlank()
            ? ", namespace='" + namespace + "'"
            : "";
        String concernClause = concern != null && !concern.isBlank()
            ? " The specific concern is: " + concern + "."
            : "";

        String instructions = """
            You are analyzing metrics for Kafka cluster `%s`.%s

            **Analysis Priority (check in this order):**
            1. **CRITICAL**: Replication health → data availability
            2. **HIGH**: Performance bottlenecks → predicts replication issues
            3. **MEDIUM**: Resource exhaustion → predicts performance issues
            4. **LOW**: Throughput trends → informational

            Each `get_kafka_metrics` response includes an `interpretation` field with \
            per-metric descriptions, thresholds, and severity — use it to interpret values.

            ## Step 1: Replication health [CRITICAL]
            Call `get_kafka_metrics(cluster_name='%s'%s, category='replication')`.

            **STOP AND ESCALATE IF:**
            - offlinepartitionscount > 0 → partitions unavailable
            - underreplicatedpartitions > 0 for >5 minutes → data loss risk

            Use the `interpretation` field for threshold guidance on maxlag and leadercount. \
            If replication issues found, immediately check Step 3 to identify root cause.

            ## Step 2: Throughput [LOW - informational]
            Call `get_kafka_metrics(cluster_name='%s'%s, category='throughput')`.
            Look for rate drops (producer issues) and cross-broker imbalance (hot partitions). \
            Only concerning if combined with replication or performance issues.

            ## Step 3: Request performance [HIGH]
            Call `get_kafka_metrics(cluster_name='%s'%s, category='performance')`.
            Use the `interpretation` field for threshold guidance. \
            If broker request handler idle or network processor idle are in concerning ranges, \
            correlate with Step 1 — performance degradation causes replication lag.

            ## Step 4: JVM and resource usage [MEDIUM]
            Call `get_kafka_metrics(cluster_name='%s'%s, category='resources')`.
            Use the `interpretation` field for JVM guidance. \
            Cross-reference with `get_kafka_cluster_pods` for restart counts \
            before flagging memory concerns.

            ## Step 5: Correlate and diagnose
            Cross-reference findings from all steps to identify root causes:

            **Common Cascading Failure Patterns:**

            1. **Broker Overload Cascade:**
               - brokerrequesthandleravgidle < 0.3 (Step 3)
               → requestqueuetimems increasing (Step 3)
               → underreplicatedpartitions > 0 (Step 1)
               **Action**: Scale horizontally or reduce load

            2. **Network Bottleneck Cascade:**
               - networkprocessoravgidle < 0.3 (Step 3)
               → responsequeuetimems increasing (Step 3)
               → maxlag growing (Step 1)
               **Action**: Increase network threads or reduce connections

            3. **GC Thrashing Cascade:**
               - GC count rapidly increasing (Step 4)
               → CPU high (Step 4)
               → brokerrequesthandleravgidle dropping (Step 3)
               **Action**: Check for OOM in logs, increase heap

            4. **Controller Failure Cascade:**
               - offlinepartitionscount > 0 (Step 1)
               + leadercount severely imbalanced (Step 1)
               **Action**: Check controller logs, verify KRaft health

            5. **Disk I/O Cascade:**
               - maxlag growing steadily (Step 1)
               + underreplicatedpartitions increasing (Step 1)
               + brokerrequesthandleravgidle normal (Step 3)
               **Action**: Check disk metrics, consider faster storage

            ## Final Summary
            Provide a clear summary with:
            1. **Overall health** (CRITICAL/HIGH/MEDIUM/LOW severity)
            2. **Concerning metrics** (with specific values)
            3. **Root cause analysis** (identify cascading patterns if present)
            4. **Actionable recommendations** (prioritized by severity)
            5. **Verification steps** (what to check next)\
            """.formatted(
                clusterName, concernClause,
                clusterName, nsArg,
                clusterName, nsArg,
                clusterName, nsArg,
                clusterName, nsArg);

        return PromptResponse.withMessages(List.of(
            PromptMessage.withUserRole(instructions)
        ));
    }
}