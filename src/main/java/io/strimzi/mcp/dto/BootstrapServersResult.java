/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.mcp.dto;

import java.time.Instant;
import java.util.List;

/**
 * Result object for Kafka bootstrap servers information.
 *
 * @param status           the status of the result
 * @param namespace        the Kubernetes namespace
 * @param clusterName      the Kafka cluster name
 * @param bootstrapServers the list of bootstrap server endpoints
 * @param message          a human-readable message describing the result
 * @param timestamp        the time this result was generated
 */
public record BootstrapServersResult(
    String status,
    String namespace,
    String clusterName,
    List<BootstrapServerInfo> bootstrapServers,
    String message,
    Instant timestamp
) {

    /**
     * Creates a successful result with bootstrap servers.
     *
     * @param namespace   the Kubernetes namespace
     * @param clusterName the Kafka cluster name
     * @param servers     the list of discovered bootstrap servers
     * @return a successful BootstrapServersResult
     */
    public static BootstrapServersResult of(String namespace, String clusterName, List<BootstrapServerInfo> servers) {
        return new BootstrapServersResult(
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
     * @return an empty BootstrapServersResult
     */
    public static BootstrapServersResult empty(String namespace, String clusterName) {
        return new BootstrapServersResult(
            "empty",
            namespace,
            clusterName,
            List.of(),
            String.format("No bootstrap servers found for cluster '%s' in namespace '%s'", clusterName, namespace),
            Instant.now()
        );
    }

    /**
     * Creates a not-found result when the Kafka cluster does not exist.
     *
     * @param namespace   the Kubernetes namespace
     * @param clusterName the Kafka cluster name
     * @return a not-found BootstrapServersResult
     */
    public static BootstrapServersResult notFound(String namespace, String clusterName) {
        return new BootstrapServersResult(
            "not-found",
            namespace,
            clusterName,
            List.of(),
            String.format("Kafka cluster '%s' not found in namespace '%s'", clusterName, namespace),
            Instant.now()
        );
    }

    /**
     * Creates an error result when bootstrap server retrieval fails.
     *
     * @param namespace    the Kubernetes namespace
     * @param clusterName  the Kafka cluster name
     * @param errorMessage the error description
     * @return an error BootstrapServersResult
     */
    public static BootstrapServersResult error(String namespace, String clusterName, String errorMessage) {
        return new BootstrapServersResult(
            "error",
            namespace,
            clusterName,
            List.of(),
            String.format("Error getting bootstrap servers for cluster '%s': %s", clusterName, errorMessage),
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
        /**
         * Returns the connection string in host:port format.
         *
         * @return the connection string
         */
        public String getConnectionString() {
            return String.format("%s:%d", host, port);
        }
    }
}
