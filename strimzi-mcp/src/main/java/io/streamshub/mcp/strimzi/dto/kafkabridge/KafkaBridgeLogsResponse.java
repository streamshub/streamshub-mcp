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
 * @param hasErrors  whether errors were found in the logs or pod fetches failed
 * @param errorCount the number of error lines found
 * @param failedPods the number of pods whose log fetch failed
 * @param logLines   the total number of log lines retrieved
 * @param hasMore    whether more log lines are available beyond the requested tail limit
 * @param logs       the raw log content
 * @param warnings   per-pod failure details (omitted when empty)
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
    @JsonProperty("failed_pods") int failedPods,
    @JsonProperty("log_lines") int logLines,
    @JsonProperty("has_more") boolean hasMore,
    @JsonProperty("logs") String logs,
    @JsonInclude(JsonInclude.Include.NON_EMPTY) @JsonProperty("warnings") List<String> warnings,
    @JsonProperty("timestamp") Instant timestamp,
    @JsonProperty("message") String message
) {

    /**
     * Creates a result with KafkaBridge pod log data.
     *
     * @param bridgeName the KafkaBridge name
     * @param namespace  the Kubernetes namespace
     * @param pods       the list of pod names
     * @param hasErrors  whether errors were found or pod fetches failed
     * @param errorCount the number of error lines found
     * @param failedPods the number of pods whose log fetch failed
     * @param logLines   the total number of log lines
     * @param hasMore    whether more log lines are available
     * @param logs       the raw log content
     * @param warnings   per-pod failure details
     * @return a response with the log data
     */
    @SuppressWarnings("checkstyle:ParameterNumber")
    public static KafkaBridgeLogsResponse of(String bridgeName, String namespace, List<String> pods,
                                              boolean hasErrors, int errorCount, int failedPods,
                                              int logLines, boolean hasMore, String logs,
                                              List<String> warnings) {
        String msg = buildMessage(pods.size(), failedPods, errorCount, hasErrors);
        return new KafkaBridgeLogsResponse(bridgeName, namespace, pods, hasErrors, errorCount, failedPods,
            logLines, hasMore, logs, warnings, Instant.now(), msg);
    }

    /**
     * Creates an empty result when no pods are found for the KafkaBridge.
     *
     * @param bridgeName the KafkaBridge name
     * @param namespace  the Kubernetes namespace
     * @return an empty response indicating no pods were found
     */
    public static KafkaBridgeLogsResponse empty(String bridgeName, String namespace) {
        return new KafkaBridgeLogsResponse(bridgeName, namespace, List.of(), false, 0, 0, 0, false, null,
            List.of(), Instant.now(),
            String.format("No KafkaBridge pods found for bridge '%s' in namespace '%s'", bridgeName, namespace));
    }

    private static String buildMessage(int totalPods, int failedPods, int errorCount, boolean hasErrors) {
        if (failedPods > 0 && errorCount > 0) {
            return String.format("Logs retrieved from %d pods (%d failed); found %d errors",
                totalPods - failedPods, failedPods, errorCount);
        }
        if (failedPods > 0) {
            return String.format("Logs retrieved from %d pods (%d failed); no content errors found",
                totalPods - failedPods, failedPods);
        }
        if (hasErrors) {
            return String.format("Found %d errors in logs across %d pods", errorCount, totalPods);
        }
        return String.format("Logs retrieved from %d pods (no errors found)", totalPods);
    }
}
