/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.mcp.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;

/**
 * Result object for Kafka Connect discovery operations.
 *
 * @param connects      the list of discovered Kafka Connect clusters
 * @param totalConnects the total number of Connect clusters found
 * @param status        the status of the operation
 * @param message       a human-readable message describing the result
 * @param timestamp     the time this result was generated
 */
public record KafkaConnectResult(
    @JsonProperty("connects") List<KafkaConnectInfo> connects,
    @JsonProperty("total_connects") int totalConnects,
    @JsonProperty("status") String status,
    @JsonProperty("message") String message,
    @JsonProperty("timestamp") Instant timestamp
) {
    /**
     * Creates a successful result with the discovered Connect clusters.
     *
     * @param connects the list of discovered Kafka Connect clusters
     * @return a successful KafkaConnectResult
     */
    public static KafkaConnectResult of(final List<KafkaConnectInfo> connects) {
        String message = connects.size() == 1
            ? String.format("Found 1 Kafka Connect cluster: %s",
                connects.get(0).getDisplayName())
            : String.format("Found %d Kafka Connect clusters", connects.size());

        return new KafkaConnectResult(
            connects,
            connects.size(),
            "SUCCESS",
            message,
            Instant.now()
        );
    }

    /**
     * Creates an empty result when no Connect clusters are found in the namespace.
     *
     * @param namespace the Kubernetes namespace that was searched
     * @return an empty KafkaConnectResult
     */
    public static KafkaConnectResult empty(String namespace) {
        return new KafkaConnectResult(
            List.of(),
            0,
            "SUCCESS",
            String.format("No Kafka Connect clusters found in namespace '%s'", namespace),
            Instant.now()
        );
    }

    /**
     * Creates an error result when Connect cluster discovery fails.
     *
     * @param namespace    the Kubernetes namespace that was searched
     * @param errorMessage the error description
     * @return an error KafkaConnectResult
     */
    public static KafkaConnectResult error(String namespace, String errorMessage) {
        return new KafkaConnectResult(
            List.of(),
            0,
            "ERROR",
            String.format("Error discovering Kafka Connect clusters in namespace '%s': %s", namespace, errorMessage),
            Instant.now()
        );
    }
}
