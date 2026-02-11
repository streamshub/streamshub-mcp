/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.mcp.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;

/**
 * Result object for Kafka clusters discovery operations.
 *
 * @param clusters the list of discovered Kafka clusters
 * @param totalClusters the total number of clusters found
 * @param status the status of the operation
 * @param message a human-readable message describing the result
 * @param timestamp the time this result was generated
 */
public record KafkaClustersResult(
    @JsonProperty("clusters") List<KafkaClusterInfo> clusters,
    @JsonProperty("total_clusters") int totalClusters,
    @JsonProperty("status") String status,
    @JsonProperty("message") String message,
    @JsonProperty("timestamp") Instant timestamp
) {
    /**
     * Creates a successful result with the discovered clusters.
     *
     * @param clusters the list of discovered Kafka clusters
     * @return a successful KafkaClustersResult
     */
    public static KafkaClustersResult of(List<KafkaClusterInfo> clusters) {
        String message = clusters.size() == 1 ?
            String.format("Found 1 Kafka cluster: %s", clusters.get(0).getDisplayName()) :
            String.format("Found %d Kafka clusters", clusters.size());

        return new KafkaClustersResult(
            clusters,
            clusters.size(),
            "SUCCESS",
            message,
            Instant.now()
        );
    }

    /**
     * Creates an empty result when no clusters are found in the namespace.
     *
     * @param namespace the Kubernetes namespace that was searched
     * @return an empty KafkaClustersResult
     */
    public static KafkaClustersResult empty(String namespace) {
        return new KafkaClustersResult(
            List.of(),
            0,
            "SUCCESS",
            String.format("No Kafka clusters found in namespace '%s'", namespace),
            Instant.now()
        );
    }

    /**
     * Creates an error result when cluster discovery fails.
     *
     * @param namespace the Kubernetes namespace that was searched
     * @param errorMessage the error description
     * @return an error KafkaClustersResult
     */
    public static KafkaClustersResult error(String namespace, String errorMessage) {
        return new KafkaClustersResult(
            List.of(),
            0,
            "ERROR",
            String.format("Error discovering Kafka clusters in namespace '%s': %s", namespace, errorMessage),
            Instant.now()
        );
    }
}
