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
 * @param hasErrors       whether errors were found in the logs
 * @param errorCount      the number of error lines found
 * @param logLines        the total number of log lines retrieved
 * @param hasMore         whether more log lines are available
 * @param logs            the raw log content
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
    @JsonProperty("log_lines") int logLines,
    @JsonProperty("has_more") boolean hasMore,
    @JsonProperty("logs") String logs,
    @JsonProperty("timestamp") Instant timestamp,
    @JsonProperty("message") String message
) {

    /**
     * Creates a response with log data.
     *
     * @param mirrorMakerName the MM2 name
     * @param namespace       the namespace
     * @param pods            the pod names
     * @param hasErrors       whether errors were found
     * @param errorCount      the error count
     * @param logLines        the total lines
     * @param hasMore         whether more lines are available
     * @param logs            the raw logs
     * @return a logs response
     */
    @SuppressWarnings("checkstyle:ParameterNumber")
    public static KafkaMirrorMaker2LogsResponse of(final String mirrorMakerName, final String namespace,
                                                    final List<String> pods, final boolean hasErrors,
                                                    final int errorCount, final int logLines,
                                                    final boolean hasMore, final String logs) {
        String msg = hasErrors
            ? String.format("Found %d errors in logs across %d pods", errorCount, pods.size())
            : String.format("Logs retrieved from %d pods (no errors found)", pods.size());
        return new KafkaMirrorMaker2LogsResponse(mirrorMakerName, namespace, pods, hasErrors,
            errorCount, logLines, hasMore, logs, Instant.now(), msg);
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
            0, false, null, Instant.now(),
            String.format("No MirrorMaker2 pods found for '%s' in namespace '%s'",
                mirrorMakerName, namespace));
    }
}
