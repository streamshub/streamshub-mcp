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
 * Strimzi operator logs resource.
 * Follows Kubernetes resource naming convention.
 *
 * @param namespace    the Kubernetes namespace
 * @param operatorPods the list of operator pod names
 * @param hasErrors    whether errors were found in the logs
 * @param errorCount   the number of errors found
 * @param logLines     the number of log lines retrieved
 * @param hasMore      whether more log lines are available beyond the requested tail limit
 * @param logs         the raw log content
 * @param timestamp    the time this result was generated
 * @param message      a human-readable message describing the result
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record StrimziOperatorLogsResponse(
    @JsonProperty("namespace") String namespace,
    @JsonProperty("operator_pods") List<String> operatorPods,
    @JsonProperty("has_errors") boolean hasErrors,
    @JsonProperty("error_count") int errorCount,
    @JsonProperty("log_lines") int logLines,
    @JsonProperty("has_more") boolean hasMore,
    @JsonProperty("logs") String logs,
    @JsonProperty("timestamp") Instant timestamp,
    @JsonProperty("message") String message
) {

    /**
     * Creates a successful result with operator log data.
     *
     * @param namespace  the Kubernetes namespace
     * @param logs       the raw log content
     * @param podNames   the list of operator pod names
     * @param hasErrors  whether errors were found
     * @param errorCount the number of errors found
     * @param logLines   the number of log lines retrieved
     * @param hasMore    whether more log lines are available
     * @return a StrimziOperatorLogs with the log data
     */
    public static StrimziOperatorLogsResponse of(String namespace, String logs, List<String> podNames,
                                                 boolean hasErrors, int errorCount, int logLines,
                                                 boolean hasMore) {
        return new StrimziOperatorLogsResponse(
            namespace,
            podNames,
            hasErrors,
            errorCount,
            logLines,
            hasMore,
            logs,
            Instant.now(),
            hasErrors ?
                String.format("Found %d errors in operator logs across %d pods", errorCount, podNames.size()) :
                String.format("Operator logs retrieved from %d pods (no errors found)", podNames.size())
        );
    }

    /**
     * Creates a not-found result when no operator pods exist.
     *
     * @param namespace the Kubernetes namespace
     * @return a not-found StrimziOperatorLogs
     */
    public static StrimziOperatorLogsResponse notFound(String namespace) {
        return new StrimziOperatorLogsResponse(
            namespace,
            List.of(),
            false,
            0,
            0,
            false,
            null,
            Instant.now(),
            String.format("No Strimzi operator pods found in namespace '%s'. Ensure the operator is deployed.", namespace)
        );
    }

}
