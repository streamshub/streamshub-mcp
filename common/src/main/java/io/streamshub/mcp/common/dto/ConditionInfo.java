/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Kubernetes-style status condition information.
 * Reusable across any resource that reports conditions (Kafka, KafkaNodePool, pods, etc.).
 *
 * @param type               the condition type (e.g., Ready, NotReady)
 * @param status             the condition status value (True, False, Unknown)
 * @param reason             the machine-readable reason for the condition
 * @param message            the human-readable message describing the condition
 * @param lastTransitionTime the time at which the condition last transitioned
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ConditionInfo(
    @JsonProperty("type") String type,
    @JsonProperty("status") String status,
    @JsonProperty("reason") String reason,
    @JsonProperty("message") String message,
    @JsonProperty("last_transition_time") String lastTransitionTime
) {
    /**
     * Creates a condition info from the given parameters.
     *
     * @param type               the condition type
     * @param status             the condition status value
     * @param reason             the machine-readable reason
     * @param message            the human-readable message
     * @param lastTransitionTime the last transition time as an ISO 8601 string
     * @return a new ConditionInfo instance
     */
    public static ConditionInfo of(final String type, final String status,
                                   final String reason, final String message,
                                   final String lastTransitionTime) {
        return new ConditionInfo(type, status, reason, message, lastTransitionTime);
    }
}
