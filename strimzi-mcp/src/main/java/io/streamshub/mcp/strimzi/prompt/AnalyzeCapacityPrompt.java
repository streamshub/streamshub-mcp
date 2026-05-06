/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.prompt;

import io.quarkiverse.mcp.server.Prompt;
import io.quarkiverse.mcp.server.PromptArg;
import io.quarkiverse.mcp.server.PromptMessage;
import io.quarkiverse.mcp.server.PromptResponse;
import io.streamshub.mcp.strimzi.config.StrimziToolsPrompts;
import jakarta.inject.Singleton;

import java.util.List;

/**
 * MCP prompt template for analyzing Kafka cluster capacity.
 *
 * <p>Guides the LLM through checking broker resource utilization,
 * partition distribution, storage usage, topic growth, and JVM health
 * to assess whether the cluster can handle current and projected load.</p>
 */
@Singleton
public class AnalyzeCapacityPrompt {

    AnalyzeCapacityPrompt() {
    }

    /**
     * Generate a capacity analysis prompt for a Kafka cluster.
     *
     * @param clusterName the Kafka cluster to analyze
     * @param namespace   optional Kubernetes namespace
     * @param concern     optional specific capacity concern
     * @return prompt response with capacity analysis instructions
     */
    @SuppressWarnings("checkstyle:MethodLength")
    @Prompt(
        name = "analyze-capacity",
        description = "Capacity analysis of a Kafka cluster."
            + " Assesses broker resources, partition distribution,"
            + " storage, and throughput headroom."
    )
    public PromptResponse analyzeCapacity(
        @PromptArg(
            name = "cluster_name",
            description = "Name of the Kafka cluster to analyze."
        ) final String clusterName,
        @PromptArg(
            name = "namespace",
            description = "Kubernetes namespace where the cluster is deployed.",
            required = false
        ) final String namespace,
        @PromptArg(
            name = "concern",
            description = "Specific capacity concern, e.g. 'storage running low',"
                + " 'need to add partitions', 'planning for traffic increase'.",
            required = false
        ) final String concern
    ) {
        String nsClause = namespace != null && !namespace.isBlank()
            ? " in namespace `" + namespace + "`"
            : "";
        String nsArg = namespace != null && !namespace.isBlank()
            ? ", namespace='" + namespace + "'"
            : "";
        String concernClause = concern != null && !concern.isBlank()
            ? " The specific concern is: " + concern + "."
            : "";

        String instructions = """
            You are performing a capacity analysis of Kafka cluster `%s`%s.%s

            The goal is to assess whether the cluster has sufficient resources for \
            its current workload and identify constraints before they cause outages.

            Follow these steps in order. After each step, analyze the results \
            before proceeding to the next.

            %s

            ## Step 1: Cluster topology and resource allocation
            Use `get_kafka_cluster(cluster_name='%s'%s)` to get the cluster overview.
            Then use `list_kafka_node_pools(cluster_name='%s'%s)` and \
            `get_kafka_node_pool` for each pool to collect:
            - Total broker count and roles (broker, controller, dual-role)
            - CPU and memory requests/limits per node pool
            - Storage type and size per node pool
            - JBOD disk count (if applicable)

            Build a resource inventory:
            | Pool | Replicas | Roles | CPU req/limit | Memory req/limit | Storage |

            ## Step 2: Pod resource utilization
            Use `get_kafka_cluster_pods(cluster_name='%s'%s)` to check actual pod states.
            Look for:
            - Pods with high restart counts (may indicate resource pressure)
            - Resource requests vs limits gaps (risk of throttling or OOM)
            - Uneven pod distribution across nodes

            ## Step 3: Broker performance metrics
            Use `get_kafka_metrics(cluster_name='%s'%s, category='performance')` \
            to assess broker load.
            Key indicators:
            - **brokerrequesthandleravgidle**: <0.5 means brokers are busy, \
            <0.3 means overloaded
            - **networkprocessoravgidle**: <0.3 means network threads saturated
            - **requestqueuetimems**: Rising trend means requests are queuing
            - **responsequeuetimems**: Rising trend means responses are backing up

            Use the `interpretation` field for threshold guidance.

            ## Step 4: JVM and resource metrics
            Use `get_kafka_metrics(cluster_name='%s'%s, category='resources')` \
            to check JVM health.
            Key indicators:
            - **Heap usage**: >80%% of max is a concern, >90%% is critical
            - **GC frequency**: Rapidly increasing GC count indicates memory pressure
            - **CPU usage**: Sustained >80%% limits headroom for traffic spikes

            ## Step 5: Throughput assessment
            Use `get_kafka_metrics(cluster_name='%s'%s, category='throughput')` \
            to assess data volume.
            Key indicators:
            - Bytes in/out per broker (is load balanced across brokers?)
            - Messages per second (current workload volume)
            - Imbalanced throughput across brokers (hot partitions or skewed assignment)

            ## Step 6: Replication health
            Use `get_kafka_metrics(cluster_name='%s'%s, category='replication')` \
            to check replication overhead.
            Key indicators:
            - **Leader count per broker**: Should be roughly equal across brokers
            - **Partition count per broker**: Should be roughly equal
            - **Under-replicated partitions**: >0 means cluster is already strained
            - **Max lag**: Growing lag means replication cannot keep up

            ## Step 7: Topic and partition scale
            Use `list_kafka_topics(cluster_name='%s'%s)` to assess topic scale.
            Check:
            - Total topic count
            - Total partition count (sum across all topics)
            - Topics with high partition counts (hotspots)
            - Topics with high replication factor

            **Partition limits**: Each partition requires memory on every broker \
            that hosts a replica. Recommended limits:
            - Up to 4000 partitions per broker for typical workloads
            - Fewer if brokers have limited memory (<4Gi heap)

            ## Step 8: Kafka Exporter metrics (if available)
            Use `get_kafka_exporter_metrics(cluster_name='%s'%s, \
            category='consumer_lag')` to check consumer lag.
            - High consumer lag may indicate consumers cannot keep up, which \
            could mean the cluster needs more partitions or consumers

            Use `get_kafka_exporter_metrics(cluster_name='%s'%s, \
            category='partitions')` to check partition health.
            - Partition leader and ISR distribution across brokers

            ## Step 9: Capacity assessment summary
            Produce a structured capacity report:

            **Current utilization:**
            | Resource | Allocated | Used | Headroom |
            Fill in for: CPU, Memory, Storage, Network, Partitions

            **Bottlenecks** (resources with <20%% headroom):
            - List each bottleneck with current value and threshold

            **Imbalances** (uneven resource distribution):
            - Broker load imbalance (throughput, partition count, leader count)
            - Node pool sizing mismatches

            **Scaling recommendations** (prioritized):
            1. Immediate actions (address critical constraints)
            2. Short-term improvements (optimize current resources)
            3. Long-term planning (prepare for growth)

            For each recommendation, specify:
            - What to change (add brokers, increase memory, add partitions, etc.)
            - Expected impact (how much headroom it adds)
            - Trade-offs (cost, complexity, downtime required)\
            """.formatted(
                clusterName, nsClause, concernClause,
                StrimziToolsPrompts.ERROR_HANDLING_INSTRUCTION,
                clusterName, nsArg,
                clusterName, nsArg,
                clusterName, nsArg,
                clusterName, nsArg,
                clusterName, nsArg,
                clusterName, nsArg,
                clusterName, nsArg,
                clusterName, nsArg,
                clusterName, nsArg,
                clusterName, nsArg);

        return PromptResponse.withMessages(List.of(
            PromptMessage.withUserRole(instructions)
        ));
    }
}