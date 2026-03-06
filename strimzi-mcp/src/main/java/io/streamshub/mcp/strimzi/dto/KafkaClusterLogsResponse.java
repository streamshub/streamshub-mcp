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
 * Response containing logs from Kafka cluster pods.
 *
 * @param clusterName the Kafka cluster name
 * @param namespace   the Kubernetes namespace
 * @param pods        the list of pod names logs were retrieved from
 * @param hasErrors   whether errors were found in the logs
 * @param errorCount  the number of error lines found
 * @param logLines    the total number of log lines retrieved
 * @param logs        the raw log content
 * @param timestamp   the time this result was generated
 * @param message     a human-readable summary of the result
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record KafkaClusterLogsResponse(
    @JsonProperty("cluster_name") String clusterName,
    @JsonProperty("namespace") String namespace,
    @JsonProperty("pods") List<String> pods,
    @JsonProperty("has_errors") boolean hasErrors,
    @JsonProperty("error_count") int errorCount,
    @JsonProperty("log_lines") int logLines,
    @JsonProperty("logs") String logs,
    @JsonProperty("timestamp") Instant timestamp,
    @JsonProperty("message") String message
) {

    /**
     * Creates a successful result with Kafka pod log data.
     *
     * @param clusterName the Kafka cluster name
     * @param namespace   the Kubernetes namespace
     * @param pods        the list of pod names
     * @param hasErrors   whether errors were found
     * @param errorCount  the number of error lines found
     * @param logLines    the total number of log lines
     * @param logs        the raw log content
     * @return a response with the log data
     */
    public static KafkaClusterLogsResponse of(String clusterName, String namespace, List<String> pods,
                                               boolean hasErrors, int errorCount, int logLines, String logs) {
        String msg = hasErrors
            ? String.format("Found %d errors in logs across %d pods", errorCount, pods.size())
            : String.format("Logs retrieved from %d pods (no errors found)", pods.size());
        return new KafkaClusterLogsResponse(clusterName, namespace, pods, hasErrors, errorCount,
            logLines, logs, Instant.now(), msg);
    }

    /**
     * Creates an empty result when no pods are found for the cluster.
     *
     * @param clusterName the Kafka cluster name
     * @param namespace   the Kubernetes namespace
     * @return an empty response indicating no pods were found
     */
    public static KafkaClusterLogsResponse empty(String clusterName, String namespace) {
        return new KafkaClusterLogsResponse(clusterName, namespace, List.of(), false, 0, 0, null,
            Instant.now(),
            String.format("No Kafka pods found for cluster '%s' in namespace '%s'", clusterName, namespace));
    }
}
