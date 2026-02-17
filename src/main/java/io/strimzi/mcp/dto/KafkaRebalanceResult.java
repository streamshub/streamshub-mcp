/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.mcp.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;

/**
 * Result object for Kafka Rebalance discovery operations.
 *
 * @param rebalances      the list of discovered Kafka Rebalance operations
 * @param totalRebalances the total number of Rebalance operations found
 * @param status          the status of the operation
 * @param message         a human-readable message describing the result
 * @param timestamp       the time this result was generated
 */
public record KafkaRebalanceResult(
    @JsonProperty("rebalances") List<KafkaRebalanceInfo> rebalances,
    @JsonProperty("total_rebalances") int totalRebalances,
    @JsonProperty("status") String status,
    @JsonProperty("message") String message,
    @JsonProperty("timestamp") Instant timestamp
) {
    /**
     * Creates a successful result with the discovered Rebalance operations.
     *
     * @param rebalances the list of discovered Kafka Rebalance operations
     * @return a successful KafkaRebalanceResult
     */
    public static KafkaRebalanceResult of(List<KafkaRebalanceInfo> rebalances) {
        String message = rebalances.size() == 1 ?
            String.format("Found 1 Kafka Rebalance: %s", rebalances.get(0).getDisplayName()) :
            String.format("Found %d Kafka Rebalance operations", rebalances.size());

        return new KafkaRebalanceResult(
            rebalances,
            rebalances.size(),
            "SUCCESS",
            message,
            Instant.now()
        );
    }

    /**
     * Creates an empty result when no Rebalance operations are found in the namespace.
     *
     * @param namespace the Kubernetes namespace that was searched
     * @return an empty KafkaRebalanceResult
     */
    public static KafkaRebalanceResult empty(String namespace) {
        return new KafkaRebalanceResult(
            List.of(),
            0,
            "SUCCESS",
            String.format("No Kafka Rebalance operations found in namespace '%s'", namespace),
            Instant.now()
        );
    }

    /**
     * Creates an error result when Rebalance discovery fails.
     *
     * @param namespace    the Kubernetes namespace that was searched
     * @param errorMessage the error description
     * @return an error KafkaRebalanceResult
     */
    public static KafkaRebalanceResult error(String namespace, String errorMessage) {
        return new KafkaRebalanceResult(
            List.of(),
            0,
            "ERROR",
            String.format("Error discovering Kafka Rebalance operations in namespace '%s': %s", namespace, errorMessage),
            Instant.now()
        );
    }
}