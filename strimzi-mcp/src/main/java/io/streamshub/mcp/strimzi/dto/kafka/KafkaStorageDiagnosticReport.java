/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.dto.kafka;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.streamshub.mcp.strimzi.dto.kafkanodepool.KafkaNodePoolResponse;
import io.streamshub.mcp.strimzi.dto.metrics.KafkaMetricsResponse;
import io.streamshub.mcp.strimzi.dto.operator.StrimziEventsResponse;

import java.time.Instant;
import java.util.List;

/**
 * Consolidated diagnostic report for Kafka cluster storage health.
 *
 * @param cluster        the Kafka cluster summary
 * @param nodePools      the list of node pools defining storage layout
 * @param pvcs           the PVC status and capacity details
 * @param pods           the Kafka cluster pod health
 * @param storageMetrics storage-related metrics (e.g. log size, disk I/O)
 * @param events         Kubernetes events relevant to storage and pods
 * @param analysis       LLM-generated root cause analysis (null if Sampling not supported)
 * @param stepsCompleted the list of successfully completed diagnostic steps
 * @param stepsFailed    the list of failed diagnostic steps with error messages
 * @param timestamp      the time this report was generated
 * @param message        a human-readable summary
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record KafkaStorageDiagnosticReport(
    @JsonProperty("cluster") KafkaClusterResponse cluster,
    @JsonProperty("node_pools") List<KafkaNodePoolResponse> nodePools,
    @JsonProperty("pvcs") KafkaPvcResponse pvcs,
    @JsonProperty("pods") KafkaClusterPodsResponse pods,
    @JsonProperty("storage_metrics") KafkaMetricsResponse storageMetrics,
    @JsonProperty("events") StrimziEventsResponse events,
    @JsonProperty("analysis") String analysis,
    @JsonProperty("steps_completed") List<String> stepsCompleted,
    @JsonProperty("steps_failed") List<String> stepsFailed,
    @JsonProperty("timestamp") Instant timestamp,
    @JsonProperty("message") String message
) {

    /**
     * Creates a new Kafka storage diagnostic report.
     *
     * @param cluster        the Kafka cluster summary
     * @param nodePools      the list of node pools
     * @param pvcs           the PVC details
     * @param pods           the pod health
     * @param storageMetrics the storage metrics
     * @param events         the Kubernetes events
     * @param analysis       the LLM analysis
     * @param stepsCompleted the completed steps
     * @param stepsFailed    the failed steps
     * @return a new report record
     */
    @SuppressWarnings("checkstyle:ParameterNumber")
    public static KafkaStorageDiagnosticReport of(
            final KafkaClusterResponse cluster,
            final List<KafkaNodePoolResponse> nodePools,
            final KafkaPvcResponse pvcs,
            final KafkaClusterPodsResponse pods,
            final KafkaMetricsResponse storageMetrics,
            final StrimziEventsResponse events,
            final String analysis,
            final List<String> stepsCompleted,
            final List<String> stepsFailed) {
        String msg = String.format("Kafka storage diagnostic completed: %d steps succeeded, %d steps failed",
            stepsCompleted.size(), stepsFailed != null ? stepsFailed.size() : 0);
        return new KafkaStorageDiagnosticReport(
            cluster,
            nodePools,
            pvcs,
            pods,
            storageMetrics,
            events,
            analysis,
            stepsCompleted,
            stepsFailed,
            Instant.now(),
            msg);
    }
}
