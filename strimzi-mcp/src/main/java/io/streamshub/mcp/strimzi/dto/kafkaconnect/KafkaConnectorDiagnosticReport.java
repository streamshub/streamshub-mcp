/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.dto.kafkaconnect;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.streamshub.mcp.strimzi.dto.StrimziEventsResponse;

import java.time.Instant;
import java.util.List;

/**
 * Consolidated diagnostic report for a KafkaConnector issue.
 * Composes data from multiple services gathered during a single diagnostic workflow.
 *
 * @param connector      the connector status and configuration
 * @param connectCluster the parent KafkaConnect cluster status
 * @param connectPods    the Connect cluster pod health
 * @param connectLogs    the Connect cluster logs (filtered for errors)
 * @param events         related Kubernetes events
 * @param analysis       LLM-generated root cause analysis (null if Sampling not supported)
 * @param stepsCompleted the list of successfully completed diagnostic steps
 * @param stepsFailed    the list of failed diagnostic steps with error messages
 * @param timestamp      the time this report was generated
 * @param message        a human-readable summary
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record KafkaConnectorDiagnosticReport(
    @JsonProperty("connector") KafkaConnectorResponse connector,
    @JsonProperty("connect_cluster") KafkaConnectResponse connectCluster,
    @JsonProperty("connect_pods") KafkaConnectPodsResponse connectPods,
    @JsonProperty("connect_logs") KafkaConnectLogsResponse connectLogs,
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
     * @param connector      the connector status
     * @param connectCluster the Connect cluster status
     * @param connectPods    the Connect pod health
     * @param connectLogs    the Connect logs
     * @param events         the related events
     * @param analysis       the LLM analysis
     * @param stepsCompleted the completed steps
     * @param stepsFailed    the failed steps (null if none)
     * @return a new diagnostic report
     */
    @SuppressWarnings("checkstyle:ParameterNumber")
    public static KafkaConnectorDiagnosticReport of(KafkaConnectorResponse connector,
                                                     KafkaConnectResponse connectCluster,
                                                     KafkaConnectPodsResponse connectPods,
                                                     KafkaConnectLogsResponse connectLogs,
                                                     StrimziEventsResponse events,
                                                     String analysis,
                                                     List<String> stepsCompleted,
                                                     List<String> stepsFailed) {
        String msg = String.format("Diagnostic completed: %d steps succeeded, %d steps failed",
            stepsCompleted.size(), stepsFailed != null ? stepsFailed.size() : 0);
        return new KafkaConnectorDiagnosticReport(connector, connectCluster, connectPods, connectLogs,
            events, analysis, stepsCompleted, stepsFailed, Instant.now(), msg);
    }
}
