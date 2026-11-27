/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.dto.kafka;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.streamshub.mcp.common.service.DiagnosticSteps;
import io.streamshub.mcp.strimzi.dto.kafkauser.KafkaUserResponse;

import java.time.Instant;
import java.util.List;
/**
 * Consolidated connectivity diagnostic report from a multi-step investigation.
 * Composes existing DTOs covering listeners, TLS certificates, authentication,
 * pod health, and connection-related logs.
 *
 * <p>Each field corresponds to one diagnostic step. Fields are null when the
 * step was skipped (Sampling-guided selective investigation) or failed
 * (recorded in {@code stepsFailed}). A step that returned data but hit errors on part of
 * it, such as certificate Secrets the service account cannot read, is recorded in
 * {@code stepsDegraded} instead of {@code stepsCompleted}.</p>
 *
 * @param cluster          Kafka cluster status and conditions
 * @param listenerFilter   the listener the report is narrowed to, or null for all listeners
 * @param bootstrapServers listener addresses, types, and ports
 * @param certificates     TLS certificate metadata and listener authentication
 * @param pods             pod health summaries (phase, restarts, termination reasons)
 * @param clusterLogs      Kafka pod logs filtered for connectivity errors
 * @param users            KafkaUser summaries with authentication types and ACL counts
 * @param analysis         LLM-generated connectivity analysis via Sampling, or null
 * @param stepsCompleted   diagnostic steps that completed successfully
 * @param stepsDegraded    diagnostic steps that returned data but hit errors on part of it
 * @param stepsFailed      diagnostic steps that failed with error descriptions
 * @param timestamp        when this report was generated
 * @param message          human-readable summary of the diagnostic run
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record KafkaConnectivityDiagnosticReport(
    @JsonProperty("cluster") KafkaClusterResponse cluster,
    @JsonProperty("listener_filter") String listenerFilter,
    @JsonProperty("bootstrap_servers") KafkaBootstrapResponse bootstrapServers,
    @JsonProperty("certificates") KafkaCertificateResponse certificates,
    @JsonProperty("pods") KafkaClusterPodsResponse pods,
    @JsonProperty("cluster_logs") KafkaClusterLogsResponse clusterLogs,
    @JsonProperty("users") List<KafkaUserResponse> users,
    @JsonProperty("analysis") String analysis,
    @JsonProperty("steps_completed") List<String> stepsCompleted,
    @JsonProperty("steps_degraded") List<String> stepsDegraded,
    @JsonProperty("steps_failed") List<String> stepsFailed,
    @JsonProperty("timestamp") Instant timestamp,
    @JsonProperty("message") String message
) {

    /**
     * Creates a connectivity diagnostic report with gathered data and step tracking.
     *
     * @param cluster          cluster status response
     * @param listenerFilter   the listener the report is narrowed to, or null
     * @param bootstrapServers bootstrap server addresses
     * @param certificates     certificate and authentication response
     * @param pods             pod health response
     * @param clusterLogs      cluster logs response
     * @param users            KafkaUser summaries
     * @param analysis         LLM analysis text or null
     * @param stepsCompleted   steps that succeeded
     * @param stepsDegraded    steps that returned partial data, with reasons
     * @param stepsFailed      steps that failed with reasons
     * @return a new connectivity diagnostic report
     */
    @SuppressWarnings("checkstyle:ParameterNumber")
    public static KafkaConnectivityDiagnosticReport of(KafkaClusterResponse cluster,
                                                       String listenerFilter,
                                                       KafkaBootstrapResponse bootstrapServers,
                                                       KafkaCertificateResponse certificates,
                                                       KafkaClusterPodsResponse pods,
                                                       KafkaClusterLogsResponse clusterLogs,
                                                       List<KafkaUserResponse> users,
                                                       String analysis,
                                                       List<String> stepsCompleted,
                                                       List<String> stepsDegraded,
                                                       List<String> stepsFailed) {
        String clusterName = cluster != null ? cluster.name() : "unknown";
        String scope = listenerFilter != null ? " (listener '" + listenerFilter + "')" : "";
        String msg = String.format("Connectivity diagnostic completed for cluster '%s'%s: %s",
            clusterName, scope, DiagnosticSteps.summary(stepsCompleted, stepsDegraded, stepsFailed));

        return new KafkaConnectivityDiagnosticReport(cluster, listenerFilter, bootstrapServers, certificates,
            pods, clusterLogs, users, analysis, stepsCompleted,
            DiagnosticSteps.nonEmptyOrNull(stepsDegraded), DiagnosticSteps.nonEmptyOrNull(stepsFailed),
            Instant.now(), msg);
    }
}
