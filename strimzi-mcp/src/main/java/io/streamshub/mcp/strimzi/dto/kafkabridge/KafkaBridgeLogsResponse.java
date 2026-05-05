/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.dto.kafkabridge;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;

/**
 * Response containing logs from KafkaBridge pods.
 *
 * @param bridgeName the KafkaBridge name
 * @param namespace  the Kubernetes namespace
 * @param pods       the list of pod names logs were retrieved from
 * @param hasErrors  whether errors were found in the logs
 * @param errorCount the number of error lines found
 * @param logLines   the total number of log lines retrieved
 * @param hasMore    whether more log lines are available beyond the requested tail limit
 * @param logs       the raw log content
 * @param timestamp  the time this result was generated
 * @param message    a human-readable summary of the result
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record KafkaBridgeLogsResponse(
    @JsonProperty("bridge_name") String bridgeName,
    @JsonProperty("namespace") String namespace,
    @JsonProperty("pods") List<String> pods,
    @JsonProperty("has_errors") boolean hasErrors,
    @JsonProperty("error_count") int errorCount,
    @JsonProperty("log_lines") int logLines,
    @JsonProperty("has_more") boolean hasMore,
    @JsonProperty("logs") String logs,
    @JsonProperty("timestamp") Instant timestamp,
    @JsonProperty("message") String message
) {

    /**
     * Creates a successful result with KafkaBridge pod log data.
     *
     * @param bridgeName the KafkaBridge name
     * @param namespace  the Kubernetes namespace
     * @param pods       the list of pod names
     * @param hasErrors  whether errors were found
     * @param errorCount the number of error lines found
     * @param logLines   the total number of log lines
     * @param hasMore    whether more log lines are available
     * @param logs       the raw log content
     * @return a response with the log data
     */
    public static KafkaBridgeLogsResponse of(String bridgeName, String namespace, List<String> pods,
                                              boolean hasErrors, int errorCount, int logLines,
                                              boolean hasMore, String logs) {
        String msg = hasErrors
            ? String.format("Found %d errors in logs across %d pods", errorCount, pods.size())
            : String.format("Logs retrieved from %d pods (no errors found)", pods.size());
        return new KafkaBridgeLogsResponse(bridgeName, namespace, pods, hasErrors, errorCount,
            logLines, hasMore, logs, Instant.now(), msg);
    }

    /**
     * Creates an empty result when no pods are found for the KafkaBridge.
     *
     * @param bridgeName the KafkaBridge name
     * @param namespace  the Kubernetes namespace
     * @return an empty response indicating no pods were found
     */
    public static KafkaBridgeLogsResponse empty(String bridgeName, String namespace) {
        return new KafkaBridgeLogsResponse(bridgeName, namespace, List.of(), false, 0, 0, false, null,
            Instant.now(),
            String.format("No KafkaBridge pods found for bridge '%s' in namespace '%s'", bridgeName, namespace));
    }
}
