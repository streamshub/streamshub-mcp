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
 * @param kind                  the Kubernetes resource kind
 * @param kafkaVersion          the Kafka version being used
 * @param readiness             the cluster readiness status (Ready, NotReady, Error, Unknown)
 * @param conditions            the list of status conditions from the Kafka resource
 * @param listeners             the list of configured listeners with type and bootstrap address
 * @param replicas              the replica count information with expected and ready counts
 * @param storageType           the storage configuration type (ephemeral, persistent-claim, jbod)
 * @param storageSize           the total storage allocated (e.g., "100Gi")
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
    @JsonProperty("kind") String kind,
    @JsonProperty("kafka_version") String kafkaVersion,
    @JsonProperty("readiness") String readiness,
    @JsonProperty("conditions") List<ConditionInfo> conditions,
    @JsonProperty("listeners") List<ListenerInfo> listeners,
    @JsonProperty("replicas") ReplicasInfo replicas,
    @JsonProperty("storage_type") String storageType,
    @JsonProperty("storage_size") String storageSize,
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

    /**
     * Status condition information from a Kafka resource.
     *
     * @param type               the condition type (e.g., Ready, NotReady)
     * @param status             the condition status value (True, False, Unknown)
     * @param reason             the machine-readable reason for the condition
     * @param message            the human-readable message describing the condition
     * @param lastTransitionTime the time at which the condition last transitioned
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ConditionInfo(
        @JsonProperty("type") String type,
        @JsonProperty("status") String status,
        @JsonProperty("reason") String reason,
        @JsonProperty("message") String message,
        @JsonProperty("last_transition_time") String lastTransitionTime
    ) {
        /**
         * Creates a condition info from the given parameters.
         *
         * @param type               the condition type
         * @param status             the condition status value
         * @param reason             the machine-readable reason
         * @param message            the human-readable message
         * @param lastTransitionTime the last transition time as an ISO 8601 string
         * @return a new ConditionInfo instance
         */
        public static ConditionInfo of(final String type, final String status,
                                       final String reason, final String message,
                                       final String lastTransitionTime) {
            return new ConditionInfo(type, status, reason, message, lastTransitionTime);
        }
    }

    /**
     * Listener information including name, type, and bootstrap address.
     *
     * @param name             the listener name
     * @param type             the listener type (e.g., internal, route, loadbalancer)
     * @param bootstrapAddress the bootstrap address for this listener
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ListenerInfo(
        @JsonProperty("name") String name,
        @JsonProperty("type") String type,
        @JsonProperty("bootstrap_address") String bootstrapAddress
    ) {
        /**
         * Creates a listener info from the given parameters.
         *
         * @param name             the listener name
         * @param type             the listener type
         * @param bootstrapAddress the bootstrap address
         * @return a new ListenerInfo instance
         */
        public static ListenerInfo of(final String name, final String type,
                                      final String bootstrapAddress) {
            return new ListenerInfo(name, type, bootstrapAddress);
        }
    }

    /**
     * Replica count information with expected and ready counts.
     *
     * @param expected the expected number of replicas
     * @param ready    the number of ready replicas
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ReplicasInfo(
        @JsonProperty("expected") Integer expected,
        @JsonProperty("ready") Integer ready
    ) {
        /**
         * Creates a replicas info from the given parameters.
         *
         * @param expected the expected number of replicas
         * @param ready    the number of ready replicas
         * @return a new ReplicasInfo instance
         */
        public static ReplicasInfo of(final Integer expected, final Integer ready) {
            return new ReplicasInfo(expected, ready);
        }
    }
}
