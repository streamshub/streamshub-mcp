/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.mcp.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;

/**
 * Structured result for operator logs query.
 * Following the pattern from realtime-context-demo.
 *
 * @param namespace    the Kubernetes namespace
 * @param operatorPods the list of operator pod names
 * @param hasErrors    whether errors were found in the logs
 * @param errorCount   the number of errors found
 * @param logLines     the number of log lines retrieved
 * @param logs         the raw log content
 * @param timestamp    the time this result was generated
 * @param message      a human-readable message describing the result
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OperatorLogsResult(
    @JsonProperty("namespace") String namespace,
    @JsonProperty("operator_pods") List<String> operatorPods,
    @JsonProperty("has_errors") boolean hasErrors,
    @JsonProperty("error_count") int errorCount,
    @JsonProperty("log_lines") int logLines,
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
     * @return an OperatorLogsResult with the log data
     */
    public static OperatorLogsResult of(String namespace, String logs, List<String> podNames,
                                        boolean hasErrors, int errorCount, int logLines) {
        return new OperatorLogsResult(
            namespace,
            podNames,
            hasErrors,
            errorCount,
            logLines,
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
     * @return a not-found OperatorLogsResult
     */
    public static OperatorLogsResult notFound(String namespace) {
        return new OperatorLogsResult(
            namespace,
            List.of(),
            false,
            0,
            0,
            null,
            Instant.now(),
            String.format("No Strimzi operator pods found in namespace '%s'. Ensure the operator is deployed.", namespace)
        );
    }

    /**
     * Creates an error result when log retrieval fails.
     *
     * @param namespace    the Kubernetes namespace
     * @param errorMessage the error description
     * @return an error OperatorLogsResult
     */
    public static OperatorLogsResult error(String namespace, String errorMessage) {
        return new OperatorLogsResult(
            namespace,
            null,
            false,
            0,
            0,
            null,
            Instant.now(),
            String.format("Error retrieving operator logs from namespace '%s': %s", namespace, errorMessage)
        );
    }
}
