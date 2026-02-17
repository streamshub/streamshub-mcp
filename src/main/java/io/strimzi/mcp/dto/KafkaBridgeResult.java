/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.mcp.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;

/**
 * Result object for Kafka Bridge discovery operations.
 *
 * @param bridges      the list of discovered Kafka Bridges
 * @param totalBridges the total number of Bridges found
 * @param status       the status of the operation
 * @param message      a human-readable message describing the result
 * @param timestamp    the time this result was generated
 */
public record KafkaBridgeResult(
    @JsonProperty("bridges") List<KafkaBridgeInfo> bridges,
    @JsonProperty("total_bridges") int totalBridges,
    @JsonProperty("status") String status,
    @JsonProperty("message") String message,
    @JsonProperty("timestamp") Instant timestamp
) {
    /**
     * Creates a successful result with the discovered Bridges.
     *
     * @param bridges the list of discovered Kafka Bridges
     * @return a successful KafkaBridgeResult
     */
    public static KafkaBridgeResult of(List<KafkaBridgeInfo> bridges) {
        String message = bridges.size() == 1 ?
            String.format("Found 1 Kafka Bridge: %s", bridges.get(0).getDisplayName()) :
            String.format("Found %d Kafka Bridges", bridges.size());

        return new KafkaBridgeResult(
            bridges,
            bridges.size(),
            "SUCCESS",
            message,
            Instant.now()
        );
    }

    /**
     * Creates an empty result when no Bridges are found in the namespace.
     *
     * @param namespace the Kubernetes namespace that was searched
     * @return an empty KafkaBridgeResult
     */
    public static KafkaBridgeResult empty(String namespace) {
        return new KafkaBridgeResult(
            List.of(),
            0,
            "SUCCESS",
            String.format("No Kafka Bridges found in namespace '%s'", namespace),
            Instant.now()
        );
    }

    /**
     * Creates an error result when Bridge discovery fails.
     *
     * @param namespace    the Kubernetes namespace that was searched
     * @param errorMessage the error description
     * @return an error KafkaBridgeResult
     */
    public static KafkaBridgeResult error(String namespace, String errorMessage) {
        return new KafkaBridgeResult(
            List.of(),
            0,
            "ERROR",
            String.format("Error discovering Kafka Bridges in namespace '%s': %s", namespace, errorMessage),
            Instant.now()
        );
    }
}