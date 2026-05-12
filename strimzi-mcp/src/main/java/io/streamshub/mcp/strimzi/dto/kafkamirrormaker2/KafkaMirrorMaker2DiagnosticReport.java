/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.dto.kafkamirrormaker2;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.streamshub.mcp.strimzi.dto.StrimziEventsResponse;

import java.time.Instant;
import java.util.List;

/**
 * Consolidated diagnostic report for a KafkaMirrorMaker2 instance.
 *
 * @param mirrorMaker    the MM2 status and configuration
 * @param pods           the MM2 pod health
 * @param logs           the MM2 logs
 * @param events         the related Kubernetes events
 * @param analysis       LLM-generated root cause analysis (null if Sampling not supported)
 * @param stepsCompleted the list of successfully completed diagnostic steps
 * @param stepsFailed    the list of failed diagnostic steps with error messages
 * @param timestamp      the time this report was generated
 * @param message        a human-readable summary
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record KafkaMirrorMaker2DiagnosticReport(
    @JsonProperty("mirror_maker") KafkaMirrorMaker2Response mirrorMaker,
    @JsonProperty("pods") KafkaMirrorMaker2PodsResponse pods,
    @JsonProperty("logs") KafkaMirrorMaker2LogsResponse logs,
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
     * @param mirrorMaker    the MM2 status
     * @param pods           the pod health
     * @param logs           the MM2 logs
     * @param events         the events
     * @param analysis       the LLM analysis
     * @param stepsCompleted the completed steps
     * @param stepsFailed    the failed steps
     * @return a new diagnostic report
     */
    @SuppressWarnings("checkstyle:ParameterNumber")
    public static KafkaMirrorMaker2DiagnosticReport of(
            final KafkaMirrorMaker2Response mirrorMaker,
            final KafkaMirrorMaker2PodsResponse pods,
            final KafkaMirrorMaker2LogsResponse logs,
            final StrimziEventsResponse events,
            final String analysis,
            final List<String> stepsCompleted,
            final List<String> stepsFailed) {
        String msg = String.format("MirrorMaker2 diagnostic completed: %d steps succeeded, %d steps failed",
            stepsCompleted.size(), stepsFailed != null ? stepsFailed.size() : 0);
        return new KafkaMirrorMaker2DiagnosticReport(mirrorMaker, pods, logs, events,
            analysis, stepsCompleted, stepsFailed, Instant.now(), msg);
    }
}
