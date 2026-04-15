/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;

/**
 * Consolidated connectivity diagnostic report from a multi-step investigation.
 * Composes existing DTOs covering listeners, TLS certificates, authentication,
 * pod health, and connection-related logs.
 *
 * <p>Each field corresponds to one diagnostic step. Fields are null when the
 * step was skipped (Sampling-guided selective investigation) or failed
 * (recorded in {@code stepsFailed}).</p>
 *
 * @param cluster          Kafka cluster status and conditions
 * @param bootstrapServers listener addresses, types, and ports
 * @param certificates     TLS certificate metadata and listener authentication
 * @param pods             pod health summaries (phase, restarts, termination reasons)
 * @param clusterLogs      Kafka pod logs filtered for connectivity errors
 * @param analysis         LLM-generated connectivity analysis via Sampling, or null
 * @param stepsCompleted   diagnostic steps that completed successfully
 * @param stepsFailed      diagnostic steps that failed with error descriptions
 * @param timestamp        when this report was generated
 * @param message          human-readable summary of the diagnostic run
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record KafkaConnectivityDiagnosticReport(
    @JsonProperty("cluster") KafkaClusterResponse cluster,
    @JsonProperty("bootstrap_servers") KafkaBootstrapResponse bootstrapServers,
    @JsonProperty("certificates") KafkaCertificateResponse certificates,
    @JsonProperty("pods") KafkaClusterPodsResponse pods,
    @JsonProperty("cluster_logs") KafkaClusterLogsResponse clusterLogs,
    @JsonProperty("analysis") String analysis,
    @JsonProperty("steps_completed") List<String> stepsCompleted,
    @JsonProperty("steps_failed") List<String> stepsFailed,
    @JsonProperty("timestamp") Instant timestamp,
    @JsonProperty("message") String message
) {

    /**
     * Creates a connectivity diagnostic report with gathered data and step tracking.
     *
     * @param cluster          cluster status response
     * @param bootstrapServers bootstrap server addresses
     * @param certificates     certificate and authentication response
     * @param pods             pod health response
     * @param clusterLogs      cluster logs response
     * @param analysis         LLM analysis text or null
     * @param stepsCompleted   steps that succeeded
     * @param stepsFailed      steps that failed with reasons
     * @return a new connectivity diagnostic report
     */
    @SuppressWarnings("checkstyle:ParameterNumber")
    public static KafkaConnectivityDiagnosticReport of(KafkaClusterResponse cluster,
                                                       KafkaBootstrapResponse bootstrapServers,
                                                       KafkaCertificateResponse certificates,
                                                       KafkaClusterPodsResponse pods,
                                                       KafkaClusterLogsResponse clusterLogs,
                                                       String analysis,
                                                       List<String> stepsCompleted,
                                                       List<String> stepsFailed) {
        int completed = stepsCompleted != null ? stepsCompleted.size() : 0;
        int failed = stepsFailed != null ? stepsFailed.size() : 0;
        String clusterName = cluster != null ? cluster.name() : "unknown";

        String msg;
        if (failed == 0) {
            msg = String.format("Connectivity diagnostic completed for cluster '%s': %d steps succeeded",
                clusterName, completed);
        } else {
            msg = String.format(
                "Connectivity diagnostic completed for cluster '%s': %d steps succeeded, %d steps failed",
                clusterName, completed, failed);
        }

        return new KafkaConnectivityDiagnosticReport(cluster, bootstrapServers, certificates,
            pods, clusterLogs, analysis, stepsCompleted, stepsFailed, Instant.now(), msg);
    }
}
