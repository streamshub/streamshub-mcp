/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.mcp.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;

/**
 * Result object for Kafka node pools discovery operations.
 *
 * @param nodePools      the list of discovered Kafka node pools
 * @param totalNodePools the total number of node pools found
 * @param status         the status of the operation
 * @param message        a human-readable message describing the result
 * @param timestamp      the time this result was generated
 */
public record KafkaNodePoolsResult(
    @JsonProperty("node_pools") List<KafkaNodePoolInfo> nodePools,
    @JsonProperty("total_node_pools") int totalNodePools,
    @JsonProperty("status") String status,
    @JsonProperty("message") String message,
    @JsonProperty("timestamp") Instant timestamp
) {
    /**
     * Creates a successful result with the discovered node pools.
     *
     * @param nodePools the list of discovered Kafka node pools
     * @return a successful KafkaNodePoolsResult
     */
    public static KafkaNodePoolsResult of(List<KafkaNodePoolInfo> nodePools) {
        String message = nodePools.size() == 1 ?
            String.format("Found 1 Kafka node pool: %s", nodePools.get(0).getDisplayName()) :
            String.format("Found %d Kafka node pools", nodePools.size());

        return new KafkaNodePoolsResult(
            nodePools,
            nodePools.size(),
            "SUCCESS",
            message,
            Instant.now()
        );
    }

    /**
     * Creates an empty result when no node pools are found in the namespace.
     *
     * @param namespace the Kubernetes namespace that was searched
     * @return an empty KafkaNodePoolsResult
     */
    public static KafkaNodePoolsResult empty(String namespace) {
        return new KafkaNodePoolsResult(
            List.of(),
            0,
            "SUCCESS",
            String.format("No Kafka node pools found in namespace '%s'", namespace),
            Instant.now()
        );
    }

    /**
     * Creates an error result when node pool discovery fails.
     *
     * @param namespace    the Kubernetes namespace that was searched
     * @param errorMessage the error description
     * @return an error KafkaNodePoolsResult
     */
    public static KafkaNodePoolsResult error(String namespace, String errorMessage) {
        return new KafkaNodePoolsResult(
            List.of(),
            0,
            "ERROR",
            String.format("Error discovering Kafka node pools in namespace '%s': %s", namespace, errorMessage),
            Instant.now()
        );
    }
}