package io.strimzi.mcp.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.strimzi.mcp.service.StrimziDiscoveryService.KafkaClusterInfo;

import java.time.Instant;
import java.util.List;

/**
 * Result object for Kafka clusters discovery operations.
 */
public record KafkaClustersResult(
    @JsonProperty("clusters") List<KafkaClusterInfo> clusters,
    @JsonProperty("total_clusters") int totalClusters,
    @JsonProperty("status") String status,
    @JsonProperty("message") String message,
    @JsonProperty("timestamp") Instant timestamp
) {
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

    public static KafkaClustersResult empty(String namespace) {
        return new KafkaClustersResult(
            List.of(),
            0,
            "SUCCESS",
            String.format("No Kafka clusters found in namespace '%s'", namespace),
            Instant.now()
        );
    }

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