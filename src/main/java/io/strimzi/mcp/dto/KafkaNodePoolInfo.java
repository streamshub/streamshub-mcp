/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.mcp.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Information about a Kafka node pool.
 *
 * @param name the node pool name
 * @param namespace the Kubernetes namespace
 * @param kafkaCluster the associated Kafka cluster name
 * @param replicas the number of replicas
 * @param roles the list of node roles (broker/controller)
 * @param nodeIds the list of assigned node IDs
 * @param status the current status of the node pool
 */
public record KafkaNodePoolInfo(
    @JsonProperty("name") String name,
    @JsonProperty("namespace") String namespace,
    @JsonProperty("kafka_cluster") String kafkaCluster,
    @JsonProperty("replicas") Integer replicas,
    @JsonProperty("roles") List<String> roles,
    @JsonProperty("node_ids") List<Integer> nodeIds,
    @JsonProperty("status") String status
) {
    /**
     * Returns a human-readable display name for this node pool.
     *
     * @return the display name in "name (cluster: cluster, namespace: ns)" format
     */
    public String getDisplayName() {
        return String.format("%s (cluster: %s, namespace: %s)",
            name, kafkaCluster, namespace);
    }
}