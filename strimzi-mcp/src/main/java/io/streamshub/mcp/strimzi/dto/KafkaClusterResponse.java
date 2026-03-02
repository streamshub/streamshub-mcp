/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;

/**
 * Response containing comprehensive Kafka cluster resource information.
 * Avoids naming conflicts with Kubernetes API classes.
 *
 * @param name                  the cluster name
 * @param namespace             the Kubernetes namespace
 * @param status                the cluster status (Ready, NotReady, Error, Unknown)
 * @param kafkaVersion          the Kafka version being used
 * @param replicas              the number of Kafka brokers configured
 * @param readyReplicas         the number of ready Kafka brokers
 * @param storageType           the storage configuration type (ephemeral, persistent-claim, jbod)
 * @param storageSize           the total storage allocated (e.g., "100Gi")
 * @param listeners             the list of configured listener types
 * @param externalAccess        whether external access is configured
 * @param authenticationEnabled whether authentication is configured
 * @param authorizationEnabled  whether authorization is configured
 * @param creationTime          when the cluster was created
 * @param ageMinutes            the age of the cluster in minutes
 * @param managedBy             the Strimzi operator version managing this cluster
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record KafkaClusterResponse(
    @JsonProperty("name") String name,
    @JsonProperty("namespace") String namespace,
    @JsonProperty("status") String status,
    @JsonProperty("kafka_version") String kafkaVersion,
    @JsonProperty("replicas") Integer replicas,
    @JsonProperty("ready_replicas") Integer readyReplicas,
    @JsonProperty("storage_type") String storageType,
    @JsonProperty("storage_size") String storageSize,
    @JsonProperty("listeners") List<String> listeners,
    @JsonProperty("external_access") Boolean externalAccess,
    @JsonProperty("authentication_enabled") Boolean authenticationEnabled,
    @JsonProperty("authorization_enabled") Boolean authorizationEnabled,
    @JsonProperty("creation_time") Instant creationTime,
    @JsonProperty("age_minutes") Long ageMinutes,
    @JsonProperty("managed_by") String managedBy
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
