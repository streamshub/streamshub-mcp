/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.mcp.dto;

import java.util.List;

/**
 * Simple record to hold Kafka cluster information.
 *
 * @param name the cluster name
 * @param namespace the Kubernetes namespace
 * @param conditions the list of cluster conditions
 */
public record KafkaClusterInfo(
    String name,
    String namespace,
    List<?> conditions
) {
    /**
     * Returns a human-readable display name for this cluster.
     *
     * @return the display name in "name (namespace: ns)" format
     */
    public String getDisplayName() {
        return String.format("%s (namespace: %s)", name, namespace);
    }
}
