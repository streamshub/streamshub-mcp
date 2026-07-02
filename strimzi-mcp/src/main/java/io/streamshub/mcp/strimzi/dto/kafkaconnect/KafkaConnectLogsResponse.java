/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.dto.kafkaconnect;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;
/**
 * Response containing logs from KafkaConnect pods.
 *
 * @param connectName the KafkaConnect cluster name
 * @param namespace   the Kubernetes namespace
 * @param pods        the list of pod names logs were retrieved from
 * @param hasErrors   whether errors were found in the logs or pod fetches failed
 * @param errorCount  the number of error lines found
 * @param failedPods  the number of pods whose log fetch failed
 * @param logLines    the total number of log lines retrieved
 * @param hasMore     whether more log lines are available beyond the requested tail limit
 * @param logs        the raw log content
 * @param warnings    per-pod failure details (omitted when empty)
 * @param timestamp   the time this result was generated
 * @param message     a human-readable summary of the result
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record KafkaConnectLogsResponse(
    @JsonProperty("connect_name") String connectName,
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
     * Creates a result with KafkaConnect pod log data.
     *
     * @param connectName the KafkaConnect cluster name
     * @param namespace   the Kubernetes namespace
     * @param pods        the list of pod names
     * @param hasErrors   whether errors were found or pod fetches failed
     * @param errorCount  the number of error lines found
     * @param failedPods  the number of pods whose log fetch failed
     * @param logLines    the total number of log lines
     * @param hasMore     whether more log lines are available
     * @param logs        the raw log content
     * @param warnings    per-pod failure details
     * @return a response with the log data
     */
    @SuppressWarnings("checkstyle:ParameterNumber")
    public static KafkaConnectLogsResponse of(String connectName, String namespace, List<String> pods,
                                               boolean hasErrors, int errorCount, int failedPods,
                                               int logLines, boolean hasMore, String logs,
                                               List<String> warnings) {
        String msg = buildMessage(pods.size(), failedPods, errorCount, hasErrors);
        return new KafkaConnectLogsResponse(connectName, namespace, pods, hasErrors, errorCount, failedPods,
            logLines, hasMore, logs, warnings, Instant.now(), msg);
    }

    /**
     * Creates an empty result when no pods are found for the KafkaConnect cluster.
     *
     * @param connectName the KafkaConnect cluster name
     * @param namespace   the Kubernetes namespace
     * @return an empty response indicating no pods were found
     */
    public static KafkaConnectLogsResponse empty(String connectName, String namespace) {
        return new KafkaConnectLogsResponse(connectName, namespace, List.of(), false, 0, 0, 0, false, null,
            List.of(), Instant.now(),
            String.format("No KafkaConnect pods found for cluster '%s' in namespace '%s'", connectName, namespace));
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
