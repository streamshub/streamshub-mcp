/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.dto.kafkamirrormaker2;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;
/**
 * Response containing logs from KafkaMirrorMaker2 pods.
 *
 * @param mirrorMakerName the KafkaMirrorMaker2 name
 * @param namespace       the Kubernetes namespace
 * @param pods            the list of pod names logs were retrieved from
 * @param hasErrors       whether errors were found in the logs or pod fetches failed
 * @param errorCount      the number of error lines found
 * @param failedPods      the number of pods whose log fetch failed
 * @param logLines        the total number of log lines retrieved
 * @param hasMore         whether more log lines are available
 * @param logs            the raw log content
 * @param warnings        per-pod failure details (omitted when empty)
 * @param timestamp       the time this result was generated
 * @param message         a human-readable summary
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record KafkaMirrorMaker2LogsResponse(
    @JsonProperty("mirror_maker_name") String mirrorMakerName,
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
     * Creates a response with log data.
     *
     * @param mirrorMakerName the MM2 name
     * @param namespace       the namespace
     * @param pods            the pod names
     * @param hasErrors       whether errors were found or pod fetches failed
     * @param errorCount      the error count
     * @param failedPods      the number of pods whose log fetch failed
     * @param logLines        the total lines
     * @param hasMore         whether more lines are available
     * @param logs            the raw logs
     * @param warnings        per-pod failure details
     * @return a logs response
     */
    @SuppressWarnings("checkstyle:ParameterNumber")
    public static KafkaMirrorMaker2LogsResponse of(final String mirrorMakerName, final String namespace,
                                                    final List<String> pods, final boolean hasErrors,
                                                    final int errorCount, final int failedPods,
                                                    final int logLines, final boolean hasMore,
                                                    final String logs, final List<String> warnings) {
        String msg = buildMessage(pods.size(), failedPods, errorCount, hasErrors);
        return new KafkaMirrorMaker2LogsResponse(mirrorMakerName, namespace, pods, hasErrors,
            errorCount, failedPods, logLines, hasMore, logs, warnings, Instant.now(), msg);
    }

    /**
     * Creates an empty response when no pods are found.
     *
     * @param mirrorMakerName the MM2 name
     * @param namespace       the namespace
     * @return an empty logs response
     */
    public static KafkaMirrorMaker2LogsResponse empty(final String mirrorMakerName,
                                                       final String namespace) {
        return new KafkaMirrorMaker2LogsResponse(mirrorMakerName, namespace, List.of(), false, 0,
            0, 0, false, null, List.of(), Instant.now(),
            String.format("No MirrorMaker2 pods found for '%s' in namespace '%s'",
                mirrorMakerName, namespace));
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
