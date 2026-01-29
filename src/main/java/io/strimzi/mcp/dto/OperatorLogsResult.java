package io.strimzi.mcp.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;

/**
 * Structured result for operator logs query.
 * Following the pattern from realtime-context-demo.
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