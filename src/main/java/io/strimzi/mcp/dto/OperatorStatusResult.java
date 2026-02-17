/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.mcp.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

/**
 * Structured result for operator status query.
 *
 * @param namespace      the Kubernetes namespace
 * @param deploymentName the name of the operator deployment
 * @param status         the operator status string
 * @param ready          whether the operator is ready
 * @param replicas       the desired number of replicas
 * @param readyReplicas  the number of ready replicas
 * @param version        the operator version
 * @param image          the container image used by the operator
 * @param uptimeHours    the operator uptime in hours
 * @param timestamp      the time this result was generated
 * @param message        a human-readable message describing the result
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OperatorStatusResult(
    @JsonProperty("namespace") String namespace,
    @JsonProperty("deployment_name") String deploymentName,
    @JsonProperty("status") String status,
    @JsonProperty("ready") boolean ready,
    @JsonProperty("replicas") int replicas,
    @JsonProperty("ready_replicas") int readyReplicas,
    @JsonProperty("version") String version,
    @JsonProperty("image") String image,
    @JsonProperty("uptime_hours") String uptimeHours,
    @JsonProperty("timestamp") Instant timestamp,
    @JsonProperty("message") String message
) {

    /**
     * Creates a successful result with operator status information.
     *
     * @param namespace      the Kubernetes namespace
     * @param deploymentName the name of the operator deployment
     * @param ready          whether the operator is ready
     * @param replicas       the desired number of replicas
     * @param readyReplicas  the number of ready replicas
     * @param version        the operator version
     * @param image          the container image
     * @param uptimeMinutes  the uptime in minutes
     * @return an OperatorStatusResult with the status data
     */
    public static OperatorStatusResult of(String namespace, String deploymentName,
                                          boolean ready, int replicas, int readyReplicas,
                                          String version, String image, Long uptimeMinutes) {
        String status = determineStatus(ready, replicas, readyReplicas);
        String uptimeHours = uptimeMinutes != null ? String.format("%.1f", uptimeMinutes / 60.0) : "unknown";
        String message = generateMessage(deploymentName, status, replicas, readyReplicas);

        return new OperatorStatusResult(
            namespace,
            deploymentName,
            status,
            ready,
            replicas,
            readyReplicas,
            version,
            image,
            uptimeHours,
            Instant.now(),
            message
        );
    }

    /**
     * Creates a not-found result when no operator deployment exists.
     *
     * @param namespace the Kubernetes namespace
     * @return a not-found OperatorStatusResult
     */
    public static OperatorStatusResult notFound(String namespace) {
        return new OperatorStatusResult(
            namespace,
            null,
            "NOT_FOUND",
            false,
            0,
            0,
            null,
            null,
            null,
            Instant.now(),
            String.format("No Strimzi operator deployment found in namespace '%s'. " +
                "Ensure the operator is deployed.", namespace)
        );
    }

    /**
     * Creates an error result when status check fails.
     *
     * @param namespace    the Kubernetes namespace
     * @param errorMessage the error description
     * @return an error OperatorStatusResult
     */
    public static OperatorStatusResult error(String namespace, String errorMessage) {
        return new OperatorStatusResult(
            namespace,
            null,
            "ERROR",
            false,
            0,
            0,
            null,
            null,
            null,
            Instant.now(),
            String.format("Error checking operator status in namespace '%s': %s", namespace, errorMessage)
        );
    }

    private static String determineStatus(boolean ready, int replicas, int readyReplicas) {
        if (replicas == 0) return "NOT_DEPLOYED";
        if (readyReplicas == 0) return "DOWN";
        if (readyReplicas < replicas) return "DEGRADED";
        if (ready && readyReplicas == replicas) return "HEALTHY";
        return "UNKNOWN";
    }

    private static String generateMessage(String deploymentName, String status, int replicas, int readyReplicas) {
        return switch (status) {
            case "HEALTHY" -> String.format("Strimzi operator '%s' is running normally (%d/%d replicas ready)",
                deploymentName, readyReplicas, replicas);
            case "DEGRADED" -> String.format("Strimzi operator '%s' is partially available (%d/%d replicas ready)",
                deploymentName, readyReplicas, replicas);
            case "DOWN" -> String.format("Strimzi operator '%s' is not running (0/%d replicas ready)",
                deploymentName, replicas);
            case "NOT_DEPLOYED" -> "Strimzi operator is not deployed";
            default -> String.format("Strimzi operator '%s' status is %s", deploymentName, status);
        };
    }
}
