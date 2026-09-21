/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.dto.kafka;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.streamshub.mcp.common.dto.ConditionInfo;
import io.streamshub.mcp.common.dto.ReconciliationInfo;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
/**
 * Response containing comprehensive Kafka cluster resource information.
 * Avoids naming conflicts with Kubernetes API classes.
 *
 * @param name                          the cluster name
 * @param namespace                     the Kubernetes namespace
 * @param kind                          the Kubernetes resource kind
 * @param kafkaVersion                  the spec Kafka version (or status version if spec is unset)
 * @param runningKafkaVersion           the actual running Kafka version from status
 * @param kafkaMetadataVersion          the KRaft metadata version from status
 * @param operatorLastSuccessfulVersion the operator version that last successfully reconciled this cluster
 * @param clusterId                     the Kafka cluster ID from status
 * @param readiness                     the cluster readiness status (Ready, NotReady, Error, Unknown)
 * @param conditions                    the list of status conditions from the Kafka resource
 * @param listeners                     the list of configured listeners with type and bootstrap address
 * @param brokerReplicas                the broker replica and storage information
 * @param controllerReplicas            the controller replica and storage information
 * @param externalAccess                whether external access is configured
 * @param authenticationEnabled         whether authentication is configured
 * @param authorizationEnabled          whether authorization is configured
 * @param creationTime                  when the cluster was created
 * @param ageMinutes                    the age of the cluster in minutes
 * @param managedBy                     the Strimzi operator version managing this cluster
 * @param autoRebalance                 auto-rebalance status information from status
 * @param clusterSecurity               cluster security information from status
 * @param reconciliation                reconciliation status tracking (generation vs observedGeneration)
 * @param warnings                      data gathering issues (omitted when empty)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record KafkaClusterResponse(
    @JsonProperty("name") String name,
    @JsonProperty("namespace") String namespace,
    @JsonProperty("kind") String kind,
    @JsonProperty("kafka_version") String kafkaVersion,
    @JsonProperty("running_kafka_version") String runningKafkaVersion,
    @JsonProperty("kafka_metadata_version") String kafkaMetadataVersion,
    @JsonProperty("operator_last_successful_version") String operatorLastSuccessfulVersion,
    @JsonProperty("cluster_id") String clusterId,
    @JsonProperty("readiness") String readiness,
    @JsonProperty("conditions") List<ConditionInfo> conditions,
    @JsonProperty("listeners") List<ListenerInfo> listeners,
    @JsonProperty("broker_replicas") RoleReplicasInfo brokerReplicas,
    @JsonProperty("controller_replicas") RoleReplicasInfo controllerReplicas,
    @JsonProperty("external_access") Boolean externalAccess,
    @JsonProperty("authentication_enabled") Boolean authenticationEnabled,
    @JsonProperty("authorization_enabled") Boolean authorizationEnabled,
    @JsonProperty("creation_time") Instant creationTime,
    @JsonProperty("age_minutes") Long ageMinutes,
    @JsonProperty("managed_by") String managedBy,
    @JsonProperty("auto_rebalance") AutoRebalanceInfo autoRebalance,
    @JsonProperty("cluster_security") ClusterSecurityInfo clusterSecurity,
    @JsonProperty("reconciliation") ReconciliationInfo reconciliation,
    @JsonInclude(JsonInclude.Include.NON_EMPTY) @JsonProperty("warnings") List<String> warnings
) {
    /**
     * Auto-rebalance status from Kafka status.
     *
     * @param state              auto-rebalance state (e.g. Idle, Rebalancing)
     * @param modes              configured auto-rebalance modes
     * @param lastTransitionTime last transition timestamp
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AutoRebalanceInfo(
        @JsonProperty("state") String state,
        @JsonProperty("modes") List<String> modes,
        @JsonProperty("last_transition_time") String lastTransitionTime
    ) {
    }

    /**
     * Cluster security status from Kafka status.
     *
     * @param encryption     encryption status
     * @param authentication authentication status
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ClusterSecurityInfo(
        @JsonProperty("encryption") String encryption,
        @JsonProperty("authentication") String authentication
    ) {
    }

    /**
     * Creates a cluster response with the given fields.
     *
     * @param name                          the cluster name
     * @param namespace                     the Kubernetes namespace
     * @param kind                          the resource kind
     * @param kafkaVersion                  the spec Kafka version
     * @param runningKafkaVersion           the running Kafka version from status
     * @param kafkaMetadataVersion          the KRaft metadata version from status
     * @param operatorLastSuccessfulVersion the operator version that last reconciled
     * @param clusterId                     the Kafka cluster ID
     * @param readiness                     the readiness status
     * @param conditions                    the status conditions
     * @param listeners                     the listener configurations
     * @param brokerReplicas                the broker replica and storage info
     * @param controllerReplicas            the controller replica and storage info
     * @param externalAccess                whether external access is configured
     * @param authenticationEnabled         whether authentication is enabled
     * @param authorizationEnabled          whether authorization is enabled
     * @param creationTime                  the creation time
     * @param ageMinutes                    the age in minutes
     * @param managedBy                     the managing operator
     * @param autoRebalance                 auto-rebalance status
     * @param clusterSecurity               cluster security status
     * @param reconciliation                reconciliation status tracking
     * @param warnings                      data gathering issues (may be null or empty)
     * @return a new cluster response
     */
    @SuppressWarnings("checkstyle:ParameterNumber")
    public static KafkaClusterResponse of(String name, String namespace, String kind,
                                           String kafkaVersion, String runningKafkaVersion,
                                           String kafkaMetadataVersion, String operatorLastSuccessfulVersion,
                                           String clusterId, String readiness,
                                           List<ConditionInfo> conditions, List<ListenerInfo> listeners,
                                           RoleReplicasInfo brokerReplicas,
                                           RoleReplicasInfo controllerReplicas,
                                           Boolean externalAccess, Boolean authenticationEnabled,
                                           Boolean authorizationEnabled, Instant creationTime,
                                           Long ageMinutes, String managedBy,
                                           AutoRebalanceInfo autoRebalance,
                                           ClusterSecurityInfo clusterSecurity,
                                           io.streamshub.mcp.common.dto.ReconciliationInfo reconciliation,
                                           List<String> warnings) {
        return new KafkaClusterResponse(name, namespace, kind, kafkaVersion, runningKafkaVersion,
            kafkaMetadataVersion, operatorLastSuccessfulVersion, clusterId, readiness,
            conditions, listeners, brokerReplicas, controllerReplicas,
            externalAccess, authenticationEnabled, authorizationEnabled,
            creationTime, ageMinutes, managedBy, autoRebalance, clusterSecurity, reconciliation,
            warnings != null ? warnings.stream().filter(Objects::nonNull).toList() : List.of());
    }

    /**
     * Returns a human-readable display name for this cluster.
     *
     * @return the display name in "name (namespace: ns)" format
     */
    public String getDisplayName() {
        return String.format("%s (namespace: %s)", name, namespace);
    }
}
