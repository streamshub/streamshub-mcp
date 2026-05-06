/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.prompt;

import io.quarkiverse.mcp.server.Prompt;
import io.quarkiverse.mcp.server.PromptArg;
import io.quarkiverse.mcp.server.PromptMessage;
import io.quarkiverse.mcp.server.PromptResponse;
import io.streamshub.mcp.common.util.InputUtils;
import io.streamshub.mcp.strimzi.config.StrimziToolsPrompts;
import jakarta.inject.Singleton;

import java.util.List;

/**
 * MCP prompt template for troubleshooting KafkaTopic issues.
 *
 * <p>Focuses on topic-specific diagnosis: CR status, configuration,
 * Topic Operator reconciliation, and whether the issue is isolated
 * or cluster-wide. Delegates to {@code diagnose-cluster-issue} or
 * {@code analyze-kafka-metrics} for deeper cluster-level analysis.</p>
 */
@Singleton
public class TroubleshootTopicPrompt {

    TroubleshootTopicPrompt() {
    }

    /**
     * Generate a topic troubleshooting prompt.
     *
     * @param topicName   the KafkaTopic name
     * @param clusterName optional Kafka cluster name
     * @param namespace   optional Kubernetes namespace
     * @param symptom     optional observed symptom
     * @return prompt response with troubleshooting instructions
     */
    @Prompt(
        name = "troubleshoot-topic",
        description = "Step-by-step troubleshooting of a KafkaTopic issue."
            + " Checks topic status, configuration, operator reconciliation,"
            + " and whether the issue is isolated or cluster-wide."
    )
    public PromptResponse troubleshootTopic(
        @PromptArg(
            name = "topic_name",
            description = "Name of the KafkaTopic to troubleshoot."
        ) final String topicName,
        @PromptArg(
            name = "cluster_name",
            description = "Kafka cluster name. Omit to auto-discover from topic labels.",
            required = false
        ) final String clusterName,
        @PromptArg(
            name = "namespace",
            description = "Kubernetes namespace where the topic is deployed.",
            required = false
        ) final String namespace,
        @PromptArg(
            name = "symptom",
            description = "Observed symptom, e.g. 'NotReady', 'config mismatch',"
                + " 'reconciliation stalled'.",
            required = false
        ) final String symptom
    ) {
        String instructions = buildInstructions(topicName,
            InputUtils.normalizeInput(clusterName),
            InputUtils.normalizeInput(namespace),
            InputUtils.normalizeInput(symptom));

        return PromptResponse.withMessages(List.of(
            PromptMessage.withUserRole(instructions)
        ));
    }

    private static String buildInstructions(final String topicName,
                                            final String cluster,
                                            final String ns,
                                            final String symptom) {
        String nsClause = ns != null ? " in namespace `" + ns + "`" : "";
        String clusterClause = cluster != null ? " on cluster `" + cluster + "`" : "";
        String symptomClause = symptom != null ? " The reported symptom is: " + symptom + "." : "";
        String nsArg = ns != null ? ", namespace='" + ns + "'" : "";
        String clusterArg = cluster != null ? ", cluster_name='" + cluster + "'" : "";
        String clusterLead = cluster != null ? "cluster_name='" + cluster + "'" : "";

        return """
            You are troubleshooting KafkaTopic `%s`%s%s.%s

            This workflow focuses on **topic-specific** diagnosis. For cluster-wide \
            issues, use `diagnose-cluster-issue`. For metrics analysis, use \
            `analyze-kafka-metrics`.

            Follow these steps in order. After each step, analyze the results \
            before proceeding to the next.

            %s

            ## Step 1: Check topic status and configuration
            Use `get_kafka_topic(topic_name='%s'%s%s)` to retrieve the \
            topic's current state.
            Check for:
            - **Ready condition**: Is the topic Ready or NotReady?
            - **Condition messages**: Error or warning messages from the Topic Operator
            - **Partition count and replication factor**: Do they match expectations?
            - **observedGeneration vs generation**: If they differ, reconciliation \
            is in progress or stalled
            - **Topic configuration overrides**: Any non-default config values

            **Common NotReady reasons:**
            - Replication factor exceeds available brokers
            - Invalid configuration values (e.g., invalid cleanup.policy)
            - Topic already exists in Kafka with different configuration
            - Conflicting topic operator instances managing the same topic

            ## Step 2: Determine scope -- isolated or cluster-wide?
            Use `list_kafka_topics(%s%s)` to get an overview of all topics.
            Check:
            - How many topics are NotReady? If **many topics** are NotReady, \
            this is a **cluster-wide issue** -- stop here and recommend \
            the `diagnose-cluster-issue` prompt instead.
            - Are NotReady topics sharing a pattern (same prefix, same config)?
            - Is the total topic count very high (>4000 per broker)? \
            Topic Operator may be overloaded.

            ## Step 3: Quick cluster health gate
            Use `get_kafka_cluster(%s%s)` to check the parent cluster status.
            - **If the cluster is NotReady**: Topic issues are a symptom, not \
            the root cause. Stop and recommend `diagnose-cluster-issue`.
            - **If the cluster is Ready**: Note the broker count (needed to \
            validate replication factor) and continue.

            ## Step 4: Check Topic Operator reconciliation
            Use `get_strimzi_operator_logs(%s)` and look specifically for \
            Topic Operator log entries mentioning `%s`.
            Look for:
            - Reconciliation errors or exceptions for this topic
            - "Topic already exists" -- topic was created outside Strimzi \
            with different settings
            - "Replication factor" errors -- not enough brokers
            - Configuration validation failures -- invalid config values
            - Repeated reconciliation attempts -- operator stuck in a loop
            - "Conflict" or "already managed" -- multiple operators competing

            ## Step 5: Check Kubernetes events
            Use `get_strimzi_events(%s%s)` to look for warning events \
            related to the topic or its parent cluster.

            ## Step 6: Diagnose and recommend
            Based on findings from Steps 1-5, identify the root cause:

            **Topic configuration issues** (fix the KafkaTopic CR):
            - Invalid config values -- correct the spec
            - Replication factor > broker count -- reduce RF or add brokers
            - Topic exists externally with different config -- align CR \
            with actual topic, or delete the Kafka-side topic and let \
            the operator recreate it

            **Topic Operator issues** (check entity operator):
            - Operator not reconciling -- check entity operator pod health \
            via `get_kafka_cluster_pods`
            - Operator overloaded -- too many topics, check operator resource limits
            - Permission errors -- check operator RBAC

            **Cluster-wide issues** (use other prompts):
            - Cluster NotReady -- run `diagnose-cluster-issue`
            - Replication problems -- run `analyze-kafka-metrics` with \
            category 'replication'
            - Many topics affected -- run `diagnose-cluster-issue`

            Provide:
            1. **Root cause** -- the specific reason the topic is unhealthy
            2. **Severity** -- CRITICAL / HIGH / MEDIUM / LOW
            3. **Remediation steps** -- specific changes to fix the issue
            4. **Prevention** -- how to avoid this in the future\
            """.formatted(
                topicName, clusterClause, nsClause, symptomClause,
                StrimziToolsPrompts.ERROR_HANDLING_INSTRUCTION,
                topicName, clusterArg, nsArg,
                clusterLead, nsArg,
                clusterLead, nsArg,
                nsArg,
                topicName,
                clusterLead, nsArg);
    }
}