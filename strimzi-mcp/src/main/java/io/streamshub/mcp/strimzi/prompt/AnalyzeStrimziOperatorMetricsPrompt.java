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
 * MCP prompt template for analyzing Strimzi cluster operator metrics.
 *
 * <p>Guides the LLM through a structured metrics analysis workflow:
 * query reconciliation, resource, and JVM metrics from the operator,
 * interpret values, and correlate with operator logs.</p>
 */
@Singleton
public class AnalyzeStrimziOperatorMetricsPrompt {

    AnalyzeStrimziOperatorMetricsPrompt() {
    }

    /**
     * Generate a metrics analysis prompt for a Strimzi cluster operator.
     *
     * @param namespace the Kubernetes namespace of the operator
     * @param concern   optional description of the concern to investigate
     * @return prompt response with operator metrics analysis instructions
     */
    @Prompt(
        name = "analyze-strimzi-operator-metrics",
        description = "Step-by-step analysis of Strimzi cluster operator metrics."
            + " Guides through reconciliation, resource, and JVM metrics."
    )
    public PromptResponse analyzeStrimziOperatorMetrics(
        @PromptArg(
            name = "namespace",
            description = "Kubernetes namespace where the Strimzi operator is deployed.",
            required = false
        ) final String namespace,
        @PromptArg(
            name = "concern",
            description = "Specific concern to investigate, e.g. 'reconciliation failures', "
                + "'slow reconciliation', 'operator high memory'.",
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
            You are analyzing metrics for the Strimzi cluster operator.%s

            Use `get_strimzi_operator_metrics` to query each category below. The response \
            includes an `interpretation` field with metric descriptions and thresholds — \
            use it to interpret the values.

            ## Step 1: Reconciliation health
            Call `get_strimzi_operator_metrics(%scategory='reconciliation')`.
            Critical checks:
            - strimzi_reconciliations_failed_total increasing = operator errors, \
            check operator logs for details
            - strimzi_reconciliations_duration_seconds: divide sum by count for average. \
            >60s average = slow reconciliation, may indicate resource contention
            - Compare successful_total + failed_total with total to verify consistency

            ## Step 2: Managed resources
            Call `get_strimzi_operator_metrics(%scategory='resources')`.
            Look for:
            - strimzi_resources count changes = resources added/removed
            - strimzi_resource_state: any value != 1 = unhealthy resource that needs attention
            - Cross-reference unhealthy resources with cluster status using `get_kafka_cluster`

            ## Step 3: Operator JVM health
            Call `get_strimzi_operator_metrics(%scategory='jvm')`.
            Important: Java normally uses most of its allocated heap. \
            High heap usage alone is NOT a problem. Only flag JVM memory \
            as concerning if combined with:
            - Pod restarts (check with `get_strimzi_operator_pod`)
            - OOM errors in operator logs
            - Excessive GC overhead (rapidly increasing GC count)
            Also watch for rising thread count = possible resource leak.

            ## Step 4: Check pod restarts and correlate with logs
            Use `get_strimzi_operator_pod` to check for pod restarts.
            Use `get_strimzi_operator_logs` to check for errors or warnings.
            Cross-reference:
            - Failed reconciliations in metrics → specific error messages in logs
            - Slow reconciliation → look for timeout or retry patterns in logs
            - Pod restarts + high GC → possible OOM, check logs for OutOfMemoryError

            ## Step 5: Summarize findings
            Provide a clear summary with:
            1. Operator health assessment
            2. Reconciliation success rate and performance
            3. Any resources in unhealthy state
            4. JVM resource utilization concerns
            5. Actionable recommendations if issues are found\
            """.formatted(
                concernClause,
                nsArg.isEmpty() ? "" : "namespace='" + namespace + "', ",
                nsArg.isEmpty() ? "" : "namespace='" + namespace + "', ",
                nsArg.isEmpty() ? "" : "namespace='" + namespace + "', ");

        return PromptResponse.withMessages(List.of(
            PromptMessage.withUserRole(instructions)
        ));
    }
}
