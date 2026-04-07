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
    @SuppressWarnings("checkstyle:MethodLength")
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

            **CRITICAL CONTEXT FOR CLUSTER HEALTH:**
            - Kafka is a distributed system where failures cascade
            - Replication metrics directly impact data availability (CRITICAL)
            - Performance metrics predict imminent failures (HIGH)
            - Resource metrics indicate long-term trends (MEDIUM)
            
            **Analysis Priority (check in this order):**
            1. **CRITICAL**: Replication health → affects data availability NOW
            2. **HIGH**: Performance bottlenecks → will cause replication issues SOON
            3. **MEDIUM**: Resource exhaustion → will cause performance issues LATER
            4. **LOW**: Throughput trends → informational only

            Use `get_kafka_metrics` to query each category below. The response includes \
            an `interpretation` field with metric descriptions, thresholds, and severity tags — \
            use it to interpret the values and prioritize findings.

            ## Step 1: Replication health [CRITICAL - affects data availability]
            Call `get_kafka_metrics(cluster_name='%s'%s, category='replication')`.
            
            **STOP AND ESCALATE IMMEDIATELY IF:**
            - offlinepartitionscount > 0 → partitions unavailable (CRITICAL)
            - underreplicatedpartitions > 0 for >5 minutes → data loss risk (CRITICAL)
            
            **TIME-SENSITIVE CHECKS:**
            - underreplicatedpartitions > 0: Expected during rolling restarts (2-3 min per broker). \
            If sustained >5 minutes during normal operations → broker overload, network issues, or disk I/O problems.
            - maxlag: <1000 = healthy, 1000-10000 = monitor, >10000 = investigate broker load
            - leadercount: >20%% variance across brokers = uneven load, needs partition reassignment
            
            **If replication issues found, immediately check Step 3 (performance) to identify root cause.**

            ## Step 2: Throughput analysis [LOW - informational]
            Call `get_kafka_metrics(cluster_name='%s'%s, category='throughput')`.
            
            Look for patterns:
            - Sudden drops in messagesin_total rate = producer issues
            - bytesin/bytesout imbalance across brokers = hot partitions
            - Compare produce vs fetch request rates for workload characterization
            
            **Note:** Throughput metrics are informational. Only concerning if combined with \
            replication or performance issues.

            ## Step 3: Request performance [HIGH - predicts failures]
            Call `get_kafka_metrics(cluster_name='%s'%s, category='performance')`.
            
            **CRITICAL THRESHOLDS:**
            - brokerrequesthandleravgidle_percent:
              * >0.5 = healthy headroom
              * 0.3-0.5 = monitor closely
              * <0.3 = overloaded (add capacity)
              * <0.1 = critical (clients experiencing timeouts)
            
            - requestqueuetimems:
              * <50ms = good
              * 50-100ms = acceptable
              * >100ms = bottleneck
              * >500ms = severe (clients timing out)
            
            - networkprocessoravgidle_percent:
              * >0.5 = healthy
              * 0.3-0.5 = monitor
              * <0.3 = network bottleneck
            
            **CASCADING FAILURE PATTERN:**
            If brokerrequesthandleravgidle < 0.3 AND requestqueuetimems increasing \
            → broker overload will cause replication lag (check Step 1 for confirmation)

            ## Step 4: JVM and resource usage [MEDIUM - long-term health]
            Call `get_kafka_metrics(cluster_name='%s'%s, category='resources')`.
            
            **IMPORTANT - AVOID FALSE POSITIVES:**
            Java normally uses most of its allocated heap. High heap usage alone is NOT a problem. \
            Only flag JVM memory as concerning if combined with:
            - Pod restarts (check with `get_kafka_cluster_pods`)
            - OOM errors in pod logs (check with `get_kafka_logs`)
            - Excessive GC overhead (rapidly increasing GC count)
            
            **GC THRESHOLDS:**
            - <5%% of CPU time = healthy
            - 5-10%% = monitor
            - >10%% = investigate heap sizing
            
            **THREAD HEALTH:**
            - Stable thread count = normal
            - Rapid growth (>50%% in <5 min) = connection storms or thread leaks
            
            **CASCADING FAILURE PATTERN:**
            If GC count rapidly increasing AND CPU high AND brokerrequesthandleravgidle dropping \
            → GC thrashing causing broker overload

            ## Step 5: Correlate and diagnose
            Cross-reference findings from all steps to identify root causes:
            
            **Common Cascading Failure Patterns:**
            
            1. **Broker Overload Cascade:**
               - brokerrequesthandleravgidle < 0.3 (Step 3)
               → requestqueuetimems increasing (Step 3)
               → underreplicatedpartitions > 0 (Step 1)
               → maxlag growing (Step 1)
               **Action**: Scale horizontally or reduce producer/consumer load
            
            2. **Network Bottleneck Cascade:**
               - networkprocessoravgidle < 0.3 (Step 3)
               → responsequeuetimems increasing (Step 3)
               → maxlag growing (Step 1)
               **Action**: Increase network threads or reduce connection count
            
            3. **GC Thrashing Cascade:**
               - GC count rapidly increasing (Step 4)
               → CPU high (Step 4)
               → brokerrequesthandleravgidle dropping (Step 3)
               → requestqueuetimems increasing (Step 3)
               **Action**: Check for OOM in logs, increase heap, or investigate memory leak
            
            4. **Controller Failure Cascade:**
               - offlinepartitionscount > 0 (Step 1)
               + leadercount severely imbalanced (Step 1)
               **Action**: Check controller logs, verify ZooKeeper/KRaft health
            
            5. **Disk I/O Cascade:**
               - maxlag growing steadily (Step 1)
               + underreplicatedpartitions increasing (Step 1)
               + brokerrequesthandleravgidle normal (Step 3)
               **Action**: Check disk metrics, consider faster storage or more brokers
            
            **Verification Steps:**
            - If JVM memory looked high, check `get_kafka_cluster_pods` for restart counts \
            before raising it as an issue
            - If replication lag found, verify broker load in Step 3 to identify root cause
            - If performance degraded, check Step 4 for resource exhaustion

            ## Final Summary
            Provide a clear summary with:
            1. **Overall cluster health assessment** (CRITICAL/HIGH/MEDIUM/LOW severity)
            2. **Any metrics that are concerning or critical** (with specific values and thresholds)
            3. **Root cause analysis** (identify cascading failure patterns if present)
            4. **Actionable recommendations** (prioritized by severity)
            5. **Verification steps** (what to check next to confirm diagnosis)
            
            **Remember:** All metrics healthy = cluster is operating normally. \
            Don't raise false alarms based on high heap usage alone.\
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