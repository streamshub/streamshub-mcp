/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.mcp.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * Information about a Kafka Connector.
 *
 * @param name the connector name
 * @param namespace the Kubernetes namespace
 * @param connectCluster the associated Connect cluster name
 * @param connectorClass the connector class implementation
 * @param tasksMax the maximum number of tasks
 * @param configKeys key configuration properties
 * @param state the current state of the connector
 * @param status the current status of the connector
 */
public record KafkaConnectorInfo(
    @JsonProperty("name") String name,
    @JsonProperty("namespace") String namespace,
    @JsonProperty("connect_cluster") String connectCluster,
    @JsonProperty("connector_class") String connectorClass,
    @JsonProperty("tasks_max") Integer tasksMax,
    @JsonProperty("config_keys") Map<String, String> configKeys,
    @JsonProperty("state") String state,
    @JsonProperty("status") String status
) {
    /**
     * Returns a human-readable display name for this connector.
     *
     * @return the display name in "name (connect: connect, namespace: ns)" format
     */
    public String getDisplayName() {
        return String.format("%s (connect: %s, namespace: %s)",
            name, connectCluster, namespace);
    }
}