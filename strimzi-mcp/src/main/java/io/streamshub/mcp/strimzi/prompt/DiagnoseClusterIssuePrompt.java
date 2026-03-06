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
 * MCP prompt template for diagnosing Kafka cluster issues.
 *
 * <p>Guides the LLM through a structured diagnostic workflow:
 * check cluster status, node pools, operator logs, pod health,
 * and pod logs to identify root causes.</p>
 */
@Singleton
public class DiagnoseClusterIssuePrompt {

    DiagnoseClusterIssuePrompt() {
    }

    /**
     * Generate a diagnostic prompt for a Kafka cluster issue.
     *
     * @param clusterName the name of the Kafka cluster
     * @param namespace   the Kubernetes namespace of the cluster
     * @param symptom     optional description of the observed symptom
     * @return prompt response with diagnostic instructions
     */
    @Prompt(
        name = "diagnose-cluster-issue",
        description = "Step-by-step diagnosis of a Kafka cluster issue."
            + " Guides through status checks, operator logs, and pod inspection."
    )
    public PromptResponse diagnoseClusterIssue(
        @PromptArg(
            name = "cluster_name",
            description = "Name of the Kafka cluster to diagnose."
        ) final String clusterName,
        @PromptArg(
            name = "namespace",
            description = "Kubernetes namespace where the Kafka cluster is deployed.",
            required = false
        ) final String namespace,
        @PromptArg(
            name = "symptom",
            description = "Observed symptom, e.g. 'NotReady for 15 minutes' or 'pods restarting'.",
            required = false
        ) final String symptom
    ) {
        String nsClause = namespace != null && !namespace.isBlank()
            ? " in namespace `" + namespace + "`"
            : "";
        String symptomClause = symptom != null && !symptom.isBlank()
            ? " The reported symptom is: " + symptom + "."
            : "";

        String instructions = "You are diagnosing a Kafka cluster issue for cluster `"
            + clusterName + "`" + nsClause + "." + symptomClause
            + "\n\nFollow these steps in order. After each step, analyze the results"
            + " before proceeding to the next."
            + "\n\n## Step 1: Check Kafka cluster status"
            + "\nUse `get_kafka_cluster` to retrieve the cluster status and conditions."
            + "\nLook for: NotReady conditions, stalled reconciliation,"
            + " mismatched observed/expected generation, warning conditions."
            + "\n\n## Step 2: Check KafkaNodePool statuses"
            + "\nUse `list_kafka_node_pools` to list all node pools for this cluster."
            + "\nFor any pool that looks unhealthy, use `get_kafka_node_pool` for details."
            + "\nLook for: pools with fewer ready replicas than expected,"
            + " pools in non-Ready state, role mismatches."
            + "\n\n## Step 3: Check Strimzi operator"
            + "\nUse `list_strimzi_operators` to find the operator managing this cluster."
            + "\nUse `get_strimzi_operator_logs` to read operator logs."
            + "\nLook for: reconciliation errors, exceptions, warnings related to `"
            + clusterName + "`, repeated error patterns."
            + "\n\n## Step 4: Check pod health"
            + "\nUse `get_kafka_cluster_pods` to check all pods for the cluster."
            + "\nLook for: CrashLoopBackOff, Pending pods, high restart counts,"
            + " pods not in Running phase, containers not ready."
            + "\n\n## Step 5: Investigate unhealthy pods"
            + "\nFor any unhealthy pods found in Step 4, use `get_strimzi_operator_pod`"
            + " or the appropriate pod detail tool to get environment, resources, and conditions."
            + "\nLook for: OOMKilled termination reason, resource limits that are too low,"
            + " missing volumes, failed liveness/readiness probes."
            + "\n\n## Step 6: Correlate and summarize"
            + "\nCorrelate the findings from all steps."
            + "\nDistinguish between:"
            + "\n- Operator-initiated changes (rolling updates, certificate renewal,"
            + " configuration changes)"
            + "\n- Infrastructure failures (OOM, disk full, node issues)"
            + "\n- Configuration errors (invalid resource specs, missing secrets)"
            + "\n\nProvide a clear summary of the root cause and actionable recommendations.";

        return PromptResponse.withMessages(List.of(
            PromptMessage.withUserRole(instructions)
        ));
    }
}