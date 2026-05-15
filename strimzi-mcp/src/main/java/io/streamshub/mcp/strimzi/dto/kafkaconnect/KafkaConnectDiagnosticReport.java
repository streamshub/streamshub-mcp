/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.dto.kafkaconnect;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.streamshub.mcp.strimzi.dto.StrimziEventsResponse;
import io.streamshub.mcp.strimzi.dto.metrics.KafkaConnectMetricsResponse;

import java.time.Instant;
import java.util.List;

/**
 * Consolidated diagnostic report for a KafkaConnect cluster.
 * Focuses on cluster-level health rather than individual connector issues.
 *
 * @param connectCluster the Connect cluster status and configuration
 * @param connectors     the connectors deployed on this cluster (scope detection)
 * @param pods           the Connect pod health
 * @param logs           the Connect logs
 * @param connectMetrics the Connect worker metrics
 * @param events         the related Kubernetes events
 * @param analysis       LLM-generated root cause analysis (null if Sampling not supported)
 * @param stepsCompleted the list of successfully completed diagnostic steps
 * @param stepsFailed    the list of failed diagnostic steps with error messages
 * @param timestamp      the time this report was generated
 * @param message        a human-readable summary
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record KafkaConnectDiagnosticReport(
    @JsonProperty("connect_cluster") KafkaConnectResponse connectCluster,
    @JsonProperty("connectors") List<KafkaConnectorResponse> connectors,
    @JsonProperty("pods") KafkaConnectPodsResponse pods,
    @JsonProperty("logs") KafkaConnectLogsResponse logs,
    @JsonProperty("connect_metrics") KafkaConnectMetricsResponse connectMetrics,
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
     * @param connectCluster the Connect cluster status
     * @param connectors     the connector inventory
     * @param pods           the pod health
     * @param logs           the Connect logs
     * @param connectMetrics the Connect metrics
     * @param events         the events
     * @param analysis       the LLM analysis
     * @param stepsCompleted the completed steps
     * @param stepsFailed    the failed steps
     * @return a new diagnostic report
     */
    @SuppressWarnings("checkstyle:ParameterNumber")
    public static KafkaConnectDiagnosticReport of(
            final KafkaConnectResponse connectCluster,
            final List<KafkaConnectorResponse> connectors,
            final KafkaConnectPodsResponse pods,
            final KafkaConnectLogsResponse logs,
            final KafkaConnectMetricsResponse connectMetrics,
            final StrimziEventsResponse events,
            final String analysis,
            final List<String> stepsCompleted,
            final List<String> stepsFailed) {
        String msg = String.format(
            "KafkaConnect cluster diagnostic completed: %d steps succeeded, %d steps failed",
            stepsCompleted.size(), stepsFailed != null ? stepsFailed.size() : 0);
        return new KafkaConnectDiagnosticReport(connectCluster, connectors, pods, logs,
            connectMetrics, events, analysis, stepsCompleted, stepsFailed, Instant.now(), msg);
    }
}
