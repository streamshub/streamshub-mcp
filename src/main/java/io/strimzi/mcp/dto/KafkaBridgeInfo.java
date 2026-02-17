/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.mcp.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Information about a Kafka Bridge instance.
 *
 * @param name the bridge name
 * @param namespace the Kubernetes namespace
 * @param kafkaCluster the associated Kafka cluster name
 * @param replicas the number of replicas
 * @param httpPort the HTTP port for the bridge
 * @param apiUrl the API URL for the bridge
 * @param status the current status of the bridge
 */
public record KafkaBridgeInfo(
    @JsonProperty("name") String name,
    @JsonProperty("namespace") String namespace,
    @JsonProperty("kafka_cluster") String kafkaCluster,
    @JsonProperty("replicas") Integer replicas,
    @JsonProperty("http_port") Integer httpPort,
    @JsonProperty("api_url") String apiUrl,
    @JsonProperty("status") String status
) {
    /**
     * Returns a human-readable display name for this bridge.
     *
     * @return the display name in "name (cluster: cluster, namespace: ns)" format
     */
    public String getDisplayName() {
        return String.format("%s (cluster: %s, namespace: %s)",
            name, kafkaCluster, namespace);
    }
}