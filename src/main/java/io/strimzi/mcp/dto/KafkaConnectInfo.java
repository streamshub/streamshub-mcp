/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.mcp.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Information about a Kafka Connect cluster.
 *
 * @param name the Connect cluster name
 * @param namespace the Kubernetes namespace
 * @param kafkaCluster the associated Kafka cluster name
 * @param replicas the number of replicas
 * @param version the Kafka Connect version
 * @param build the build configuration status
 * @param restApiUrl the REST API URL for the Connect cluster
 * @param status the current status of the Connect cluster
 */
public record KafkaConnectInfo(
    @JsonProperty("name") String name,
    @JsonProperty("namespace") String namespace,
    @JsonProperty("kafka_cluster") String kafkaCluster,
    @JsonProperty("replicas") Integer replicas,
    @JsonProperty("version") String version,
    @JsonProperty("build") String build,
    @JsonProperty("rest_api_url") String restApiUrl,
    @JsonProperty("status") String status
) {
    /**
     * Returns a human-readable display name for this Connect cluster.
     *
     * @return the display name in "name (cluster: cluster, namespace: ns)" format
     */
    public String getDisplayName() {
        return String.format("%s (cluster: %s, namespace: %s)",
            name, kafkaCluster, namespace);
    }
}