/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.dto.kafkanodepool;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.streamshub.mcp.strimzi.dto.operator.StrimziEventsResponse;

import java.time.Instant;
import java.util.List;
/**
 * Consolidated diagnostic report for a KafkaNodePool.
 *
 * @param nodePool       the node pool status: roles, replicas, node IDs, storage, and conditions
 * @param pods           the node pool pod health
 * @param pvcs           the PersistentVolumeClaim binding status for the pool's storage
 * @param events         the Kubernetes events for the pool's pods
 * @param logs           the node pool pod logs
 * @param analysis       LLM-generated root cause analysis (null if Sampling not supported)
 * @param stepsCompleted the list of successfully completed diagnostic steps
 * @param stepsFailed    the list of failed diagnostic steps with error messages
 * @param timestamp      the time this report was generated
 * @param message        a human-readable summary
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record KafkaNodePoolDiagnosticReport(
    @JsonProperty("node_pool") KafkaNodePoolResponse nodePool,
    @JsonProperty("pods") KafkaNodePoolPodsResponse pods,
    @JsonProperty("pvcs") List<KafkaNodePoolPvcStatus> pvcs,
    @JsonProperty("events") StrimziEventsResponse events,
    @JsonProperty("logs") KafkaNodePoolLogsResponse logs,
    @JsonProperty("analysis") String analysis,
    @JsonProperty("steps_completed") List<String> stepsCompleted,
    @JsonProperty("steps_failed") List<String> stepsFailed,
    @JsonProperty("timestamp") Instant timestamp,
    @JsonProperty("message") String message
) {

    /**
     * Creates a diagnostic report with all gathered data.
     *
     * @param nodePool       the node pool status
     * @param pods           the pod health
     * @param pvcs           the PVC binding status
     * @param events         the events
     * @param logs           the node pool pod logs
     * @param analysis       the LLM analysis
     * @param stepsCompleted the completed steps
     * @param stepsFailed    the failed steps
     * @return a new diagnostic report
     */
    @SuppressWarnings("checkstyle:ParameterNumber")
    public static KafkaNodePoolDiagnosticReport of(
            final KafkaNodePoolResponse nodePool,
            final KafkaNodePoolPodsResponse pods,
            final List<KafkaNodePoolPvcStatus> pvcs,
            final StrimziEventsResponse events,
            final KafkaNodePoolLogsResponse logs,
            final String analysis,
            final List<String> stepsCompleted,
            final List<String> stepsFailed) {
        String msg = String.format("KafkaNodePool diagnostic completed: %d steps succeeded, %d steps failed",
            stepsCompleted.size(), stepsFailed != null ? stepsFailed.size() : 0);
        return new KafkaNodePoolDiagnosticReport(nodePool, pods, pvcs, events, logs,
            analysis, stepsCompleted, stepsFailed, Instant.now(), msg);
    }
}
