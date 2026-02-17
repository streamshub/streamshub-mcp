/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.mcp.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;

/**
 * Result object for Kafka topics operations.
 *
 * @param namespace   the Kubernetes namespace
 * @param clusterName the Kafka cluster name
 * @param topics      the list of topics found
 * @param totalTopics the total number of topics
 * @param status      the status of the operation
 * @param message     a human-readable message describing the result
 * @param timestamp   the time this result was generated
 */
public record KafkaTopicsResult(
    @JsonProperty("namespace") String namespace,
    @JsonProperty("cluster_name") String clusterName,
    @JsonProperty("topics") List<TopicInfo> topics,
    @JsonProperty("total_topics") int totalTopics,
    @JsonProperty("status") String status,
    @JsonProperty("message") String message,
    @JsonProperty("timestamp") Instant timestamp
) {
    /**
     * Creates a successful result with the discovered topics.
     *
     * @param namespace   the Kubernetes namespace
     * @param clusterName the Kafka cluster name
     * @param topics      the list of discovered topics
     * @return a successful KafkaTopicsResult
     */
    public static KafkaTopicsResult of(String namespace, String clusterName, List<TopicInfo> topics) {
        String message;
        if (clusterName != null) {
            message = String.format("Found %d topics in cluster '%s' (namespace: %s)",
                topics.size(), clusterName, namespace);
        } else {
            message = String.format("Found %d topics in namespace '%s'", topics.size(), namespace);
        }

        return new KafkaTopicsResult(
            namespace,
            clusterName,
            topics,
            topics.size(),
            "SUCCESS",
            message,
            Instant.now()
        );
    }

    /**
     * Creates an empty result when no topics are found.
     *
     * @param namespace   the Kubernetes namespace
     * @param clusterName the Kafka cluster name
     * @return an empty KafkaTopicsResult
     */
    public static KafkaTopicsResult empty(String namespace, String clusterName) {
        String message;
        if (clusterName != null) {
            message = String.format("No topics found in cluster '%s' (namespace: %s)", clusterName, namespace);
        } else {
            message = String.format("No topics found in namespace '%s'", namespace);
        }

        return new KafkaTopicsResult(
            namespace,
            clusterName,
            List.of(),
            0,
            "SUCCESS",
            message,
            Instant.now()
        );
    }

    /**
     * Creates an error result when topic retrieval fails.
     *
     * @param namespace    the Kubernetes namespace
     * @param clusterName  the Kafka cluster name
     * @param errorMessage the error description
     * @return an error KafkaTopicsResult
     */
    public static KafkaTopicsResult error(String namespace, String clusterName, String errorMessage) {
        return new KafkaTopicsResult(
            namespace,
            clusterName,
            List.of(),
            0,
            "ERROR",
            String.format("Error retrieving topics: %s", errorMessage),
            Instant.now()
        );
    }
}
