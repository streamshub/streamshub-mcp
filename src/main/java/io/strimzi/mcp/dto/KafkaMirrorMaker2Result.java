/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.mcp.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;

/**
 * Result object for Kafka MirrorMaker2 discovery operations.
 *
 * @param mirrorMakers      the list of discovered Kafka MirrorMaker2 instances
 * @param totalMirrorMakers the total number of MirrorMaker2 instances found
 * @param status            the status of the operation
 * @param message           a human-readable message describing the result
 * @param timestamp         the time this result was generated
 */
public record KafkaMirrorMaker2Result(
    @JsonProperty("mirror_makers") List<KafkaMirrorMaker2Info> mirrorMakers,
    @JsonProperty("total_mirror_makers") int totalMirrorMakers,
    @JsonProperty("status") String status,
    @JsonProperty("message") String message,
    @JsonProperty("timestamp") Instant timestamp
) {
    /**
     * Creates a successful result with the discovered MirrorMaker2 instances.
     *
     * @param mirrorMakers the list of discovered Kafka MirrorMaker2 instances
     * @return a successful KafkaMirrorMaker2Result
     */
    public static KafkaMirrorMaker2Result of(List<KafkaMirrorMaker2Info> mirrorMakers) {
        String message = mirrorMakers.size() == 1 ?
            String.format("Found 1 Kafka MirrorMaker2: %s", mirrorMakers.get(0).getDisplayName()) :
            String.format("Found %d Kafka MirrorMaker2 instances", mirrorMakers.size());

        return new KafkaMirrorMaker2Result(
            mirrorMakers,
            mirrorMakers.size(),
            "SUCCESS",
            message,
            Instant.now()
        );
    }

    /**
     * Creates an empty result when no MirrorMaker2 instances are found in the namespace.
     *
     * @param namespace the Kubernetes namespace that was searched
     * @return an empty KafkaMirrorMaker2Result
     */
    public static KafkaMirrorMaker2Result empty(String namespace) {
        return new KafkaMirrorMaker2Result(
            List.of(),
            0,
            "SUCCESS",
            String.format("No Kafka MirrorMaker2 instances found in namespace '%s'", namespace),
            Instant.now()
        );
    }

    /**
     * Creates an error result when MirrorMaker2 discovery fails.
     *
     * @param namespace    the Kubernetes namespace that was searched
     * @param errorMessage the error description
     * @return an error KafkaMirrorMaker2Result
     */
    public static KafkaMirrorMaker2Result error(String namespace, String errorMessage) {
        return new KafkaMirrorMaker2Result(
            List.of(),
            0,
            "ERROR",
            String.format("Error discovering Kafka MirrorMaker2 instances in namespace '%s': %s", namespace, errorMessage),
            Instant.now()
        );
    }
}