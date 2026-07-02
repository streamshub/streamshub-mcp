/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.dto.operator;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;
/**
 * Strimzi operator logs resource.
 * Follows Kubernetes resource naming convention.
 *
 * @param namespace    the Kubernetes namespace
 * @param operatorPods the list of operator pod names
 * @param hasErrors    whether errors were found in the logs or pod fetches failed
 * @param errorCount   the number of errors found
 * @param failedPods   the number of pods whose log fetch failed
 * @param logLines     the number of log lines retrieved
 * @param hasMore      whether more log lines are available beyond the requested tail limit
 * @param logs         the raw log content
 * @param warnings     per-pod failure details (omitted when empty)
 * @param timestamp    the time this result was generated
 * @param message      a human-readable message describing the result
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record StrimziOperatorLogsResponse(
    @JsonProperty("namespace") String namespace,
    @JsonProperty("operator_pods") List<String> operatorPods,
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
     * Creates a result with operator log data.
     *
     * @param namespace  the Kubernetes namespace
     * @param logs       the raw log content
     * @param podNames   the list of operator pod names
     * @param hasErrors  whether errors were found or pod fetches failed
     * @param errorCount the number of errors found
     * @param failedPods the number of pods whose log fetch failed
     * @param logLines   the number of log lines retrieved
     * @param hasMore    whether more log lines are available
     * @param warnings   per-pod failure details
     * @return a StrimziOperatorLogsResponse with the log data
     */
    @SuppressWarnings("checkstyle:ParameterNumber")
    public static StrimziOperatorLogsResponse of(String namespace, String logs, List<String> podNames,
                                                 boolean hasErrors, int errorCount, int failedPods,
                                                 int logLines, boolean hasMore, List<String> warnings) {
        return new StrimziOperatorLogsResponse(
            namespace,
            podNames,
            hasErrors,
            errorCount,
            failedPods,
            logLines,
            hasMore,
            logs,
            warnings,
            Instant.now(),
            buildMessage(podNames.size(), failedPods, errorCount, hasErrors)
        );
    }

    /**
     * Creates a not-found result when no operator pods exist.
     *
     * @param namespace the Kubernetes namespace
     * @return a not-found StrimziOperatorLogsResponse
     */
    public static StrimziOperatorLogsResponse notFound(String namespace) {
        return new StrimziOperatorLogsResponse(
            namespace,
            List.of(),
            false,
            0,
            0,
            0,
            false,
            null,
            List.of(),
            Instant.now(),
            String.format("No Strimzi operator pods found in namespace '%s'. Ensure the operator is deployed.", namespace)
        );
    }

    private static String buildMessage(int totalPods, int failedPods, int errorCount, boolean hasErrors) {
        if (failedPods > 0 && errorCount > 0) {
            return String.format("Operator logs retrieved from %d pods (%d failed); found %d errors",
                totalPods - failedPods, failedPods, errorCount);
        }
        if (failedPods > 0) {
            return String.format("Operator logs retrieved from %d pods (%d failed); no content errors found",
                totalPods - failedPods, failedPods);
        }
        if (hasErrors) {
            return String.format("Found %d errors in operator logs across %d pods", errorCount, totalPods);
        }
        return String.format("Operator logs retrieved from %d pods (no errors found)", totalPods);
    }
}
