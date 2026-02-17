/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.mcp.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Information about a Kafka Rebalance operation.
 *
 * @param name the rebalance operation name
 * @param namespace the Kubernetes namespace
 * @param kafkaCluster the associated Kafka cluster name
 * @param mode the rebalancing mode
 * @param goals the list of rebalancing goals
 * @param sessionId the Cruise Control session ID
 * @param status the current status of the rebalance operation
 */
public record KafkaRebalanceInfo(
    @JsonProperty("name") String name,
    @JsonProperty("namespace") String namespace,
    @JsonProperty("kafka_cluster") String kafkaCluster,
    @JsonProperty("mode") String mode,
    @JsonProperty("goals") List<String> goals,
    @JsonProperty("session_id") String sessionId,
    @JsonProperty("status") String status
) {
    /**
     * Returns a human-readable display name for this rebalance operation.
     *
     * @return the display name in "name (cluster: cluster, namespace: ns)"
     *         format
     */
    public String getDisplayName() {
        return String.format("%s (cluster: %s, namespace: %s)",
            name, kafkaCluster, namespace);
    }
}
