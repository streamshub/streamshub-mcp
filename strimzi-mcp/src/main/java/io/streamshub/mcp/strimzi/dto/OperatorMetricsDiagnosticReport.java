/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.streamshub.mcp.strimzi.dto.metrics.StrimziOperatorMetricsResponse;

import java.time.Instant;
import java.util.List;

/**
 * Consolidated diagnostic report from a multi-step Strimzi operator metrics analysis.
 * Composes operator status with metrics from each category and operator logs
 * into a single result for LLM-driven diagnosis.
 *
 * <p>Each field corresponds to one diagnostic step. Fields are null when the
 * step was skipped (Sampling-guided selective investigation) or failed
 * (recorded in {@code stepsFailed}).</p>
 *
 * @param operator              Strimzi operator deployment status
 * @param reconciliationMetrics reconciliation metrics (success/failure rates, durations)
 * @param resourceMetrics       resource metrics (managed resource counts and states)
 * @param jvmMetrics            JVM metrics (heap, GC, threads)
 * @param operatorLogs          operator pod logs filtered for errors
 * @param analysis              LLM-generated operator metrics analysis via Sampling, or null
 * @param stepsCompleted        diagnostic steps that completed successfully
 * @param stepsFailed           diagnostic steps that failed with error descriptions
 * @param timestamp             when this report was generated
 * @param message               human-readable summary of the diagnostic run
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OperatorMetricsDiagnosticReport(
    @JsonProperty("operator") StrimziOperatorResponse operator,
    @JsonProperty("reconciliation_metrics") StrimziOperatorMetricsResponse reconciliationMetrics,
    @JsonProperty("resource_metrics") StrimziOperatorMetricsResponse resourceMetrics,
    @JsonProperty("jvm_metrics") StrimziOperatorMetricsResponse jvmMetrics,
    @JsonProperty("operator_logs") StrimziOperatorLogsResponse operatorLogs,
    @JsonProperty("analysis") String analysis,
    @JsonProperty("steps_completed") List<String> stepsCompleted,
    @JsonProperty("steps_failed") List<String> stepsFailed,
    @JsonProperty("timestamp") Instant timestamp,
    @JsonProperty("message") String message
) {

    /**
     * Creates an operator metrics diagnostic report with gathered data and step tracking.
     *
     * @param operator              operator status response
     * @param reconciliationMetrics reconciliation metrics response
     * @param resourceMetrics       resource metrics response
     * @param jvmMetrics            JVM metrics response
     * @param operatorLogs          operator logs response
     * @param analysis              LLM analysis text or null
     * @param stepsCompleted        steps that succeeded
     * @param stepsFailed           steps that failed with reasons
     * @return a new operator metrics diagnostic report
     */
    @SuppressWarnings("checkstyle:ParameterNumber")
    public static OperatorMetricsDiagnosticReport of(StrimziOperatorResponse operator,
                                                     StrimziOperatorMetricsResponse reconciliationMetrics,
                                                     StrimziOperatorMetricsResponse resourceMetrics,
                                                     StrimziOperatorMetricsResponse jvmMetrics,
                                                     StrimziOperatorLogsResponse operatorLogs,
                                                     String analysis,
                                                     List<String> stepsCompleted,
                                                     List<String> stepsFailed) {
        String operatorName = operator != null ? operator.name() : "unknown";
        int completed = stepsCompleted != null ? stepsCompleted.size() : 0;
        int failed = stepsFailed != null ? stepsFailed.size() : 0;

        String msg;
        if (failed == 0) {
            msg = String.format(
                "Operator metrics diagnostic completed for '%s': %d steps succeeded",
                operatorName, completed);
        } else {
            msg = String.format(
                "Operator metrics diagnostic completed for '%s': %d steps succeeded, %d steps failed",
                operatorName, completed, failed);
        }

        return new OperatorMetricsDiagnosticReport(operator,
            reconciliationMetrics, resourceMetrics, jvmMetrics, operatorLogs,
            analysis, stepsCompleted, stepsFailed, Instant.now(), msg);
    }
}
