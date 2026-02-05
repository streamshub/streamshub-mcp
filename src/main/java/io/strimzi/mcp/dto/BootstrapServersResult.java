package io.strimzi.mcp.dto;

import java.time.Instant;
import java.util.List;

/**
 * Result object for Kafka bootstrap servers information.
 */
public record BootstrapServersResult(
    String status,
    String namespace,
    String clusterName,
    List<BootstrapServerInfo> bootstrapServers,
    String message,
    Instant timestamp
) {

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
     */
    public record BootstrapServerInfo(
        String host,
        Integer port,
        String listenerName,
        String listenerType,
        String address
    ) {
        public String getConnectionString() {
            return String.format("%s:%d", host, port);
        }
    }
}