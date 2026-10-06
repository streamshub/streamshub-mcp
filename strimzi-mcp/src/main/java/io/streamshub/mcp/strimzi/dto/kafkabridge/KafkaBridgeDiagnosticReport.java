/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.dto.kafkabridge;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.streamshub.mcp.strimzi.dto.metrics.KafkaBridgeMetricsResponse;
import io.streamshub.mcp.strimzi.dto.operator.StrimziEventsResponse;

import java.time.Instant;
import java.util.List;
/**
 * Consolidated diagnostic report for a KafkaBridge instance.
 *
 * @param bridge         the bridge status, HTTP listener, and client configuration
 * @param pods           the bridge pod health
 * @param logs           the bridge logs
 * @param events         the related Kubernetes events
 * @param metrics        the {@code kafka_bridge_*} HTTP metrics
 * @param analysis       LLM-generated root cause analysis (null if Sampling not supported)
 * @param stepsCompleted the list of successfully completed diagnostic steps
 * @param stepsFailed    the list of failed diagnostic steps with error messages
 * @param timestamp      the time this report was generated
 * @param message        a human-readable summary
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record KafkaBridgeDiagnosticReport(
    @JsonProperty("bridge") KafkaBridgeResponse bridge,
    @JsonProperty("pods") KafkaBridgePodsResponse pods,
    @JsonProperty("logs") KafkaBridgeLogsResponse logs,
    @JsonProperty("events") StrimziEventsResponse events,
    @JsonProperty("metrics") KafkaBridgeMetricsResponse metrics,
    @JsonProperty("analysis") String analysis,
    @JsonProperty("steps_completed") List<String> stepsCompleted,
    @JsonProperty("steps_failed") List<String> stepsFailed,
    @JsonProperty("timestamp") Instant timestamp,
    @JsonProperty("message") String message
) {

    /**
     * Creates a diagnostic report with all gathered data.
     *
     * @param bridge         the bridge status
     * @param pods           the pod health
     * @param logs           the bridge logs
     * @param events         the events
     * @param metrics        the bridge HTTP metrics
     * @param analysis       the LLM analysis
     * @param stepsCompleted the completed steps
     * @param stepsFailed    the failed steps
     * @return a new diagnostic report
     */
    @SuppressWarnings("checkstyle:ParameterNumber")
    public static KafkaBridgeDiagnosticReport of(
            final KafkaBridgeResponse bridge,
            final KafkaBridgePodsResponse pods,
            final KafkaBridgeLogsResponse logs,
            final StrimziEventsResponse events,
            final KafkaBridgeMetricsResponse metrics,
            final String analysis,
            final List<String> stepsCompleted,
            final List<String> stepsFailed) {
        String msg = String.format("KafkaBridge diagnostic completed: %d steps succeeded, %d steps failed",
            stepsCompleted.size(), stepsFailed != null ? stepsFailed.size() : 0);
        return new KafkaBridgeDiagnosticReport(bridge, pods, logs, events, metrics,
            analysis, stepsCompleted, stepsFailed, Instant.now(), msg);
    }
}
