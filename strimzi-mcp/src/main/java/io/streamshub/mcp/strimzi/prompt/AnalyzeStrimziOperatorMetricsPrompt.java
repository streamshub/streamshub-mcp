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

            Each `get_strimzi_operator_metrics` response includes an `interpretation` field \
            with metric descriptions and thresholds — use it to interpret values.

            ## Step 1: Reconciliation health
            Call `get_strimzi_operator_metrics(%scategory='reconciliation')`.
            Use the `interpretation` field for threshold guidance. Key checks:
            - strimzi_reconciliations_failed_total increasing = operator errors, \
            check operator logs for details
            - Divide duration sum by count for average reconciliation time
            - Compare successful + failed with total to verify consistency

            ## Step 2: Managed resources
            Call `get_strimzi_operator_metrics(%scategory='resources')`.
            Look for:
            - strimzi_resources count changes = resources added/removed
            - strimzi_resource_state != 1 = unhealthy resource
            - Cross-reference with `get_kafka_cluster` for details

            ## Step 3: Operator JVM health
            Call `get_strimzi_operator_metrics(%scategory='jvm')`.
            Use the `interpretation` field for JVM guidance. \
            Cross-reference with `get_strimzi_operator_pod` for restart counts \
            before flagging memory concerns.

            ## Step 4: Correlate with logs
            Use `get_strimzi_operator_pod` to check for pod restarts.
            Use `get_strimzi_operator_logs` to check for errors or warnings.
            Cross-reference:
            - Failed reconciliations → specific error messages in logs
            - Slow reconciliation → timeout or retry patterns in logs
            - Pod restarts + high GC → possible OOM, check for OutOfMemoryError

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
