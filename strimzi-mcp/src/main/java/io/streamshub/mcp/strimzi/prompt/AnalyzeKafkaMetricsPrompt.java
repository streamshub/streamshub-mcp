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

            Use `get_kafka_metrics` to query each category below. The response includes \
            an `interpretation` field with metric descriptions and thresholds — use it \
            to interpret the values.

            ## Step 1: Replication health
            Call `get_kafka_metrics(cluster_name='%s'%s, category='replication')`.
            Critical checks:
            - underreplicatedpartitions > 0 = data loss risk
            - offlinepartitionscount > 0 = partitions unavailable (critical)
            - maxlag growing = followers falling behind
            - leadercount imbalanced across brokers = uneven load

            ## Step 2: Throughput analysis
            Call `get_kafka_metrics(cluster_name='%s'%s, category='throughput')`.
            Look for:
            - Sudden drops in messagesin_total rate = producer issues
            - bytesin/bytesout imbalance across brokers = hot partitions
            - Compare produce vs fetch request rates

            ## Step 3: Request performance
            Call `get_kafka_metrics(cluster_name='%s'%s, category='performance')`.
            Key thresholds:
            - brokerrequesthandleravgidle_percent < 0.3 = overloaded broker
            - requestqueuetimems increasing = broker can't keep up
            - networkprocessoravgidle_percent < 0.3 = network bottleneck

            ## Step 4: JVM and resource usage
            Call `get_kafka_metrics(cluster_name='%s'%s, category='resources')`.
            Important: Java normally uses most of its allocated heap. \
            High heap usage alone is NOT a problem. Only flag JVM memory \
            as concerning if combined with:
            - Pod restarts (check with `get_kafka_cluster_pods`)
            - OOM errors in pod logs
            - Excessive GC overhead (rapidly increasing GC count)
            Also watch for rising thread count = possible connection storms.

            ## Step 5: Correlate and diagnose
            Cross-reference findings from all steps. If JVM memory looked \
            high, check `get_kafka_cluster_pods` for restart counts before \
            raising it as an issue.
            - High replication lag + high request queue time = broker overload
            - Pod restarts + high GC = possible OOM, needs more heap
            - Uneven leader distribution + throughput imbalance = needs partition reassignment
            - All metrics healthy = cluster is operating normally

            Provide a clear summary with:
            1. Overall cluster health assessment
            2. Any metrics that are concerning or critical
            3. Actionable recommendations if issues are found\
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
