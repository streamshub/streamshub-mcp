package io.strimzi.mcp.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;

/**
 * Result object for Kafka topics operations.
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