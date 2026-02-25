/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;

/**
 * Response containing Kafka cluster bootstrap server information.
 * Avoids naming conflicts with Kubernetes API classes.
 *
 * @param status           the status of the result
 * @param namespace        the Kubernetes namespace
 * @param clusterName      the Kafka cluster name
 * @param bootstrapServers the list of bootstrap server endpoints
 * @param message          a human-readable message describing the result
 * @param timestamp        the time this result was generated
 */
public record KafkaBootstrapResponse(
    @JsonProperty("status") String status,
    @JsonProperty("namespace") String namespace,
    @JsonProperty("cluster_name") String clusterName,
    @JsonProperty("bootstrap_servers") List<BootstrapServerInfo> bootstrapServers,
    @JsonProperty("message") String message,
    @JsonProperty("timestamp") Instant timestamp
) {

    /**
     * Creates a successful result with bootstrap servers.
     *
     * @param namespace   the Kubernetes namespace
     * @param clusterName the Kafka cluster name
     * @param servers     the list of discovered bootstrap servers
     * @return a successful KafkaBootstrapResponse
     */
    public static KafkaBootstrapResponse of(String namespace, String clusterName, List<BootstrapServerInfo> servers) {
        return new KafkaBootstrapResponse(
            "success",
            namespace,
            clusterName,
            servers,
            String.format("Found %d bootstrap servers for cluster '%s'", servers.size(), clusterName),
            Instant.now()
        );
    }

    /**
     * Creates an empty result when no bootstrap servers are found.
     *
     * @param namespace   the Kubernetes namespace
     * @param clusterName the Kafka cluster name
     * @return an empty KafkaBootstrapResponse
     */
    public static KafkaBootstrapResponse empty(String namespace, String clusterName) {
        return new KafkaBootstrapResponse(
            "empty",
            namespace,
            clusterName,
            List.of(),
            String.format("No bootstrap servers found for cluster '%s' in namespace '%s'", clusterName, namespace),
            Instant.now()
        );
    }

    /**
     * Information about a Kafka bootstrap server.
     *
     * @param host         the server hostname
     * @param port         the server port number
     * @param listenerName the name of the Kafka listener
     * @param listenerType the type of the Kafka listener
     * @param address      the full address string
     */
    public record BootstrapServerInfo(
        String host,
        Integer port,
        String listenerName,
        String listenerType,
        String address
    ) {
    }
}