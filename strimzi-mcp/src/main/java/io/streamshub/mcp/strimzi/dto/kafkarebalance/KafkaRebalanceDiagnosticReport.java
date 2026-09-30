/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.dto.kafkarebalance;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.streamshub.mcp.common.dto.PodLogsResult;
import io.streamshub.mcp.strimzi.dto.operator.StrimziEventsResponse;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Consolidated diagnostic report for a KafkaRebalance instance.
 *
 * @param rebalance         the rebalance resource status and spec details
 * @param progressConfigMap progress ConfigMap contents (if referenced and available)
 * @param cruiseControlLogs Cruise Control pod logs
 * @param events            related Kubernetes events
 * @param analysis          LLM-generated root cause analysis (null if Sampling not supported)
 * @param stepsCompleted    the list of successfully completed diagnostic steps
 * @param stepsFailed       the list of failed diagnostic steps with error messages
 * @param timestamp         the time this report was generated
 * @param message           a human-readable summary
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record KafkaRebalanceDiagnosticReport(
    @JsonProperty("rebalance") KafkaRebalanceResponse rebalance,
    @JsonProperty("progress_config_map") Map<String, Object> progressConfigMap,
    @JsonProperty("cruise_control_logs") PodLogsResult cruiseControlLogs,
    @JsonProperty("events") StrimziEventsResponse events,
    @JsonProperty("analysis") String analysis,
    @JsonProperty("steps_completed") List<String> stepsCompleted,
    @JsonProperty("steps_failed") List<String> stepsFailed,
    @JsonProperty("timestamp") Instant timestamp,
    @JsonProperty("message") String message
) {

    /**
     * Creates a diagnostic report with all gathered data.
     *
     * @param rebalance         the rebalance details
     * @param progressConfigMap the progress ConfigMap data
     * @param cruiseControlLogs the Cruise Control logs
     * @param events            the events
     * @param analysis          the LLM analysis
     * @param stepsCompleted    the completed steps
     * @param stepsFailed       the failed steps
     * @return a new diagnostic report
     */
    @SuppressWarnings("checkstyle:ParameterNumber")
    public static KafkaRebalanceDiagnosticReport of(
            final KafkaRebalanceResponse rebalance,
            final Map<String, Object> progressConfigMap,
            final PodLogsResult cruiseControlLogs,
            final StrimziEventsResponse events,
            final String analysis,
            final List<String> stepsCompleted,
            final List<String> stepsFailed) {
        String msg = String.format("KafkaRebalance diagnostic completed: %d steps succeeded, %d steps failed",
            stepsCompleted.size(), stepsFailed != null ? stepsFailed.size() : 0);
        return new KafkaRebalanceDiagnosticReport(rebalance, progressConfigMap, cruiseControlLogs, events,
            analysis, stepsCompleted, stepsFailed, Instant.now(), msg);
    }
}
