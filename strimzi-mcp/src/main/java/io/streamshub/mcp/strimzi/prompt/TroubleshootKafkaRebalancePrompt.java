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
 * MCP prompt template for troubleshooting a stuck or failed KafkaRebalance.
 *
 * <p>Guides the LLM through the KafkaRebalance state machine, goal-violation
 * conditions, Cruise Control pod health, logs and metrics, Kubernetes events,
 * and remediation for common stuck states. This is the client-driven twin of
 * the server-driven {@code diagnose_kafka_rebalance} composite tool.</p>
 */
@Singleton
public class TroubleshootKafkaRebalancePrompt {

    TroubleshootKafkaRebalancePrompt() {
    }

    /**
     * Generate a rebalance troubleshooting prompt for a KafkaRebalance.
     *
     * @param rebalanceName the name of the KafkaRebalance to troubleshoot
     * @param namespace     optional Kubernetes namespace
     * @param symptom       optional observed symptom
     * @return prompt response with troubleshooting instructions
     */
    @Prompt(
        name = "troubleshoot-kafka-rebalance",
        description = "Step-by-step troubleshooting of a stuck or failed KafkaRebalance."
            + " Walks the rebalance state machine, goal-violation conditions, Cruise Control"
            + " pod health, logs and metrics, events, and remediation for common stuck states."
    )
    public PromptResponse troubleshootKafkaRebalance(
        @PromptArg(
            name = "rebalance_name",
            description = StrimziToolsPrompts.REBALANCE_NAME_DESC
        ) final String rebalanceName,
        @PromptArg(
            name = "namespace",
            description = StrimziToolsPrompts.NS_DESC,
            required = false
        ) final String namespace,
        @PromptArg(
            name = "symptom",
            description = StrimziToolsPrompts.SYMPTOM_DESC,
            required = false
        ) final String symptom
    ) {
        String instructions = buildInstructions(rebalanceName, namespace, symptom);

        return PromptResponse.withMessages(List.of(
            PromptMessage.withAssistantRole(
                StrimziToolsPrompts.systemMessage("Kafka rebalance (Cruise Control) troubleshooting")),
            PromptMessage.withUserRole(instructions)
        ));
    }

    private static String buildInstructions(final String rebalanceName,
                                            final String namespace,
                                            final String symptom) {
        String nsClause = namespace != null && !namespace.isBlank()
            ? " in namespace `" + namespace + "`"
            : "";
        String nsArg = namespace != null && !namespace.isBlank()
            ? ", namespace='" + namespace + "'"
            : "";
        String symptomClause = symptom != null && !symptom.isBlank()
            ? " The reported symptom is: " + symptom + "."
            : "";

        return """
            Troubleshoot the KafkaRebalance `%s`%s.%s

            This workflow diagnoses a stuck or failed Cruise Control rebalance by walking \
            the KafkaRebalance state machine. Also available as the server-driven \
            `diagnose_kafka_rebalance` composite tool for a single-call investigation.

            ## Step 1: Read the KafkaRebalance state
            Use `get_kafka_rebalance(rebalanceName='%s'%s)` to retrieve the current state, \
            mode, conditions, optimization result, session ID, auto-approval status, and the \
            progress ConfigMap name.
            Note the **`cluster` field** from the response -- it names the Kafka cluster and \
            is required as `clusterName` in the Cruise Control steps below.
            The progress ConfigMap is surfaced here by name only; its contents are not \
            readable through a tool -- use the Cruise Control logs and metrics (Steps 4-6) \
            for live progress detail.

            ## Step 2: Locate the current state in the rebalance state machine
            A KafkaRebalance advances through \
            `New` -> `PendingProposal` -> `ProposalReady` -> `Rebalancing` -> `Ready`, \
            with `Stopped` and `NotReady` as aborted or failed outcomes.
            - **New** -- the operator accepted the resource and is about to request a proposal
            - **PendingProposal** -- Cruise Control is computing an optimization proposal
            - **ProposalReady** -- a proposal is available and (unless auto-approved) awaiting approval
            - **Rebalancing** -- partition reassignment is in progress
            - **Ready** -- the rebalance completed successfully
            - **Stopped** -- the rebalance was stopped by annotation
            - **NotReady** -- the rebalance failed; inspect conditions for the reason

            ## Step 3: Inspect conditions for goal violations and errors
            From the `conditions` gathered in Step 1, read the reason and message of any \
            `NotReady` condition. Look for:
            - **Hard goal violations** -- the proposal cannot satisfy a required hard goal
            - **Invalid broker IDs** -- `spec.brokers` references brokers that do not exist
            - **Excluded-topics conflicts** -- `spec.excludedTopicsRegex` conflicts with the request
            - **Insufficient metrics** -- Cruise Control has not collected enough windows \
            to compute a proposal

            ## Step 4: Check Cruise Control pod health
            Use `get_kafka_cluster_pods(clusterName='<cluster from Step 1>'%s)` and locate \
            the `*-cruise-control-*` pod. Check for `CrashLoopBackOff`, repeated restarts, or \
            a not-ready pod -- a rebalance cannot progress while Cruise Control is down.

            ## Step 5: Check Cruise Control logs
            Use `get_kafka_cluster_logs(clusterName='<cluster from Step 1>'%s)` and focus on \
            the Cruise Control pod. Look for insufficient-metric-samples warnings, \
            goal-computation errors, proposal-calculation timeouts, and reassignment \
            execution errors.

            ## Step 6: Check Cruise Control metrics
            Use `get_cruise_control_metrics(clusterName='<cluster from Step 1>'%s, category='sampling')` \
            and `get_cruise_control_metrics(clusterName='<cluster from Step 1>'%s, category='anomaly')` \
            to confirm whether Cruise Control has enough monitored partitions and valid \
            metric windows. Too few monitored partitions is the most common cause of a \
            rebalance stuck in `PendingProposal`.

            ## Step 7: Check Kubernetes events on the KafkaRebalance
            Use `get_strimzi_events(resourceName='%s'%s, resourceKind='KafkaRebalance')` to \
            look for warnings the operator emitted while reconciling the rebalance.
            If you did not supply a `namespace`, pass the one reported by `get_kafka_rebalance` \
            in Step 1 -- KafkaRebalance events require a namespace (this step does not \
            auto-discover it the way the cluster-scoped steps above do).

            ## Step 8: Remediation for common stuck states
            - **PendingProposal** -- Cruise Control is not ready, has too few metric samples, \
            or the calculation timed out. Verify Cruise Control health (Steps 4-5) and wait \
            for enough metric windows; a persistently stuck proposal may need \
            `strimzi.io/rebalance=refresh`.
            - **ProposalReady** -- the proposal is awaiting manual approval. Approve it by \
            annotating the KafkaRebalance with `strimzi.io/rebalance=approve` \
            (use `strimzi.io/rebalance=refresh` to regenerate a stale proposal, or \
            `strimzi.io/rebalance=stop` to abort).
            - **Rebalancing** -- movements are throttled or a broker is unresponsive. Check \
            Cruise Control logs and the reassignment throttle; confirm all brokers are Ready.
            - **NotReady** -- for a hard goal violation, relax the goals or set \
            `spec.skipHardGoalCheck: true` after confirming the trade-off; otherwise fix the \
            invalid spec (broker IDs, excluded topics) or restore Cruise Control health, then \
            annotate with `strimzi.io/rebalance=refresh`.

            ## Step 9: Summarize and recommend
            Produce a structured rebalance diagnosis:
            - **Root cause** (one sentence)
            - **Severity**: CRITICAL / HIGH / MEDIUM / LOW
            - **State-machine status**: the current state and what blocks the next transition
            - **Evidence**: key findings from conditions, optimization result, Cruise Control \
            logs and metrics, and events
            - **Remediation** (prioritized): specific, actionable steps (annotation to apply, \
            goals to adjust, Cruise Control fix)\
            """.formatted(
                rebalanceName, nsClause, symptomClause,
                rebalanceName, nsArg,
                nsArg,
                nsArg,
                nsArg,
                nsArg,
                rebalanceName, nsArg);
    }
}
