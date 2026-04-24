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
 * MCP prompt template for comparing two Kafka cluster configurations.
 *
 * <p>Guides the LLM through retrieving the effective configuration of
 * both clusters and comparing them across all configuration areas.</p>
 */
@Singleton
public class CompareClusterConfigsPrompt {

    CompareClusterConfigsPrompt() {
    }

    /**
     * Generate a configuration comparison prompt for two Kafka clusters.
     *
     * @param clusterName1 the name of the first Kafka cluster
     * @param clusterName2 the name of the second Kafka cluster
     * @param namespace1   optional namespace for the first cluster
     * @param namespace2   optional namespace for the second cluster
     * @return prompt response with comparison instructions
     */
    @Prompt(
        name = "compare-cluster-configs",
        description = "Step-by-step comparison of two Kafka cluster configurations."
            + " Guides through retrieving and comparing broker config,"
            + " resources, listeners, and component settings."
    )
    public PromptResponse compareClusterConfigs(
        @PromptArg(
            name = "cluster_name_1",
            description = "Name of the first Kafka cluster."
        ) final String clusterName1,
        @PromptArg(
            name = "cluster_name_2",
            description = "Name of the second Kafka cluster."
        ) final String clusterName2,
        @PromptArg(
            name = "namespace_1",
            description = "Kubernetes namespace for the first cluster.",
            required = false
        ) final String namespace1,
        @PromptArg(
            name = "namespace_2",
            description = "Kubernetes namespace for the second cluster.",
            required = false
        ) final String namespace2
    ) {
        String ns1Clause = namespace1 != null && !namespace1.isBlank()
            ? " (namespace: `" + namespace1 + "`)"
            : "";
        String ns2Clause = namespace2 != null && !namespace2.isBlank()
            ? " (namespace: `" + namespace2 + "`)"
            : "";

        String ns1Arg = namespace1 != null && !namespace1.isBlank()
            ? ", namespace='" + namespace1 + "'"
            : "";
        String ns2Arg = namespace2 != null && !namespace2.isBlank()
            ? ", namespace='" + namespace2 + "'"
            : "";

        String instructions = """
            You are comparing the configuration of two Kafka clusters: \
            `%s`%s and `%s`%s.

            Follow these steps in order. After each step, analyze the results \
            before proceeding to the next.

            %s

            ## Step 1: Get configuration for cluster 1
            Use `get_kafka_cluster_config(clusterName='%s'%s)` to retrieve \
            the effective configuration of the first cluster.

            ## Step 2: Get configuration for cluster 2
            Use `get_kafka_cluster_config(clusterName='%s'%s)` to retrieve \
            the effective configuration of the second cluster.

            ## Step 3: Compare broker configuration (CRITICAL)
            Compare the `broker_config` maps. Focus on data-safety settings:
            - `min.insync.replicas`, `default.replication.factor`
            - `log.retention.hours`, `log.retention.bytes`
            - `message.max.bytes`, `compression.type`
            - Any setting present in one cluster but missing in the other

            ## Step 4: Compare resources and JVM options (HIGH)
            Compare `resources` (CPU/memory requests and limits) and \
            `jvm_options` (heap sizes, GC flags) between both clusters.
            Flag significant differences in resource allocation.

            ## Step 5: Compare listeners and authorization (HIGH)
            Compare the `listeners` list and `authorization` config.
            Check for differences in:
            - Listener types and TLS settings
            - Authentication types per listener
            - Authorization type

            ## Step 6: Compare component settings (MEDIUM)
            Compare `entity_operator`, `cruise_control`, and `kafka_exporter`:
            - Entity Operator: reconciliation intervals, resources, watched namespaces
            - Cruise Control: goals, broker capacity, auto-rebalance mode
            - Kafka Exporter: topic/group regex filters

            ## Step 7: Compare metrics and logging (MEDIUM)
            Compare `metrics_config` and `logging` settings.
            Check for differences in metrics collection and log levels.

            ## Step 8: Summarize differences by impact
            Provide a structured summary:
            - **CRITICAL**: Differences affecting data safety or availability
            - **HIGH**: Differences affecting performance or operational behavior
            - **MEDIUM**: Differences affecting monitoring or observability
            - **LOW**: Cosmetic or environment-specific differences (namespace, rack key)

            Include recommendations for aligning the clusters where appropriate.\
            """.formatted(
                clusterName1, ns1Clause, clusterName2, ns2Clause,
                StrimziToolsPrompts.ERROR_HANDLING_INSTRUCTION,
                clusterName1, ns1Arg, clusterName2, ns2Arg);

        return PromptResponse.withMessages(List.of(
            PromptMessage.withUserRole(instructions)
        ));
    }
}
