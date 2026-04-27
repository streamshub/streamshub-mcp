/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.streamshub.mcp.strimzi.dto.metrics.KafkaMetricsResponse;

import java.time.Instant;
import java.util.List;

/**
 * Consolidated diagnostic report from a multi-step Kafka cluster investigation.
 * Composes existing DTOs from individual tool responses into a single result.
 *
 * <p>Each field corresponds to one diagnostic step. Fields are null when the
 * step was skipped (Sampling-guided selective investigation) or failed
 * (recorded in {@code stepsFailed}).</p>
 *
 * @param cluster        Kafka cluster status and conditions
 * @param nodePools      KafkaNodePool summaries for the cluster
 * @param pods           pod health summaries (phase, restarts, termination reasons)
 * @param operator       Strimzi operator deployment status
 * @param operatorLogs   operator pod logs filtered for errors
 * @param clusterLogs    Kafka broker pod logs filtered for errors
 * @param events         Kubernetes events for the cluster and related resources
 * @param users          KafkaUser summaries with authentication types and readiness
 * @param metrics        replication metrics from Kafka broker pods
 * @param analysis       LLM-generated root cause analysis via Sampling, or null
 * @param stepsCompleted diagnostic steps that completed successfully
 * @param stepsFailed    diagnostic steps that failed with error descriptions
 * @param timestamp      when this report was generated
 * @param message        human-readable summary of the diagnostic run
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record KafkaClusterDiagnosticReport(
    @JsonProperty("cluster") KafkaClusterResponse cluster,
    @JsonProperty("node_pools") List<KafkaNodePoolResponse> nodePools,
    @JsonProperty("pods") KafkaClusterPodsResponse pods,
    @JsonProperty("operator") StrimziOperatorResponse operator,
    @JsonProperty("operator_logs") StrimziOperatorLogsResponse operatorLogs,
    @JsonProperty("cluster_logs") KafkaClusterLogsResponse clusterLogs,
    @JsonProperty("events") StrimziEventsResponse events,
    @JsonProperty("users") List<KafkaUserResponse> users,
    @JsonProperty("metrics") KafkaMetricsResponse metrics,
    @JsonProperty("analysis") String analysis,
    @JsonProperty("steps_completed") List<String> stepsCompleted,
    @JsonProperty("steps_failed") List<String> stepsFailed,
    @JsonProperty("timestamp") Instant timestamp,
    @JsonProperty("message") String message
) {

    /**
     * Creates a diagnostic report with gathered data and step tracking.
     *
     * @param cluster        cluster status response
     * @param nodePools      node pool responses
     * @param pods           pod health response
     * @param operator       operator status response
     * @param operatorLogs   operator logs response
     * @param clusterLogs    cluster logs response
     * @param events         events response
     * @param users          KafkaUser summaries
     * @param metrics        metrics response
     * @param analysis       LLM analysis text or null
     * @param stepsCompleted steps that succeeded
     * @param stepsFailed    steps that failed with reasons
     * @return a new diagnostic report
     */
    @SuppressWarnings("checkstyle:ParameterNumber")
    public static KafkaClusterDiagnosticReport of(KafkaClusterResponse cluster,
                                                  List<KafkaNodePoolResponse> nodePools,
                                                  KafkaClusterPodsResponse pods,
                                                  StrimziOperatorResponse operator,
                                                  StrimziOperatorLogsResponse operatorLogs,
                                                  KafkaClusterLogsResponse clusterLogs,
                                                  StrimziEventsResponse events,
                                                  List<KafkaUserResponse> users,
                                                  KafkaMetricsResponse metrics,
                                                  String analysis,
                                                  List<String> stepsCompleted,
                                                  List<String> stepsFailed) {
        int completed = stepsCompleted != null ? stepsCompleted.size() : 0;
        int failed = stepsFailed != null ? stepsFailed.size() : 0;
        String clusterName = cluster != null ? cluster.name() : "unknown";

        String msg;
        if (failed == 0) {
            msg = String.format("Diagnostic completed for cluster '%s': %d steps succeeded",
                clusterName, completed);
        } else {
            msg = String.format("Diagnostic completed for cluster '%s': %d steps succeeded, %d steps failed",
                clusterName, completed, failed);
        }

        return new KafkaClusterDiagnosticReport(cluster, nodePools, pods, operator, operatorLogs,
            clusterLogs, events, users, metrics, analysis, stepsCompleted, stepsFailed,
            Instant.now(), msg);
    }
}
