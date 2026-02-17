/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.mcp.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;

/**
 * Result object for Kafka Connector discovery operations.
 *
 * @param connectors      the list of discovered Kafka Connectors
 * @param totalConnectors the total number of Connectors found
 * @param status          the status of the operation
 * @param message         a human-readable message describing the result
 * @param timestamp       the time this result was generated
 */
public record KafkaConnectorsResult(
    @JsonProperty("connectors") List<KafkaConnectorInfo> connectors,
    @JsonProperty("total_connectors") int totalConnectors,
    @JsonProperty("status") String status,
    @JsonProperty("message") String message,
    @JsonProperty("timestamp") Instant timestamp
) {
    /**
     * Creates a successful result with the discovered Connectors.
     *
     * @param connectors the list of discovered Kafka Connectors
     * @return a successful KafkaConnectorsResult
     */
    public static KafkaConnectorsResult of(List<KafkaConnectorInfo> connectors) {
        String message = connectors.size() == 1 ?
            String.format("Found 1 Kafka Connector: %s", connectors.get(0).getDisplayName()) :
            String.format("Found %d Kafka Connectors", connectors.size());

        return new KafkaConnectorsResult(
            connectors,
            connectors.size(),
            "SUCCESS",
            message,
            Instant.now()
        );
    }

    /**
     * Creates an empty result when no Connectors are found in the namespace.
     *
     * @param namespace the Kubernetes namespace that was searched
     * @return an empty KafkaConnectorsResult
     */
    public static KafkaConnectorsResult empty(String namespace) {
        return new KafkaConnectorsResult(
            List.of(),
            0,
            "SUCCESS",
            String.format("No Kafka Connectors found in namespace '%s'", namespace),
            Instant.now()
        );
    }

    /**
     * Creates an error result when Connector discovery fails.
     *
     * @param namespace    the Kubernetes namespace that was searched
     * @param errorMessage the error description
     * @return an error KafkaConnectorsResult
     */
    public static KafkaConnectorsResult error(String namespace, String errorMessage) {
        return new KafkaConnectorsResult(
            List.of(),
            0,
            "ERROR",
            String.format("Error discovering Kafka Connectors in namespace '%s': %s", namespace, errorMessage),
            Instant.now()
        );
    }
}