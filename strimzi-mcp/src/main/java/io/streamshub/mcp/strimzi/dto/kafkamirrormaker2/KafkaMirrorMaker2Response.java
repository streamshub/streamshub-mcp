/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.dto.kafkamirrormaker2;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.streamshub.mcp.common.dto.ConditionInfo;
import io.streamshub.mcp.common.dto.ReplicasInfo;

import java.time.Instant;
import java.util.List;
import java.util.Map;
/**
 * Response containing KafkaMirrorMaker2 information.
 * Used for both list (summary) and get (detail) operations.
 *
 * @param name                 the MM2 resource name
 * @param namespace            the Kubernetes namespace
 * @param readiness            the readiness status
 * @param replicas             the replica count information
 * @param targetCluster        the target cluster alias
 * @param sourceClusterAliases the list of source cluster aliases
 * @param mirrors              the mirror configurations (null for list)
 * @param clusters             the cluster configurations (null for list)
 * @param connectorStatuses    the connector status maps from CR status (null for list)
 * @param version              the Kafka Connect version (null for list)
 * @param bootstrapServers     the target cluster bootstrap servers (null for list)
 * @param conditions           the status conditions
 * @param creationTime         the creation time (null for list)
 * @param ageMinutes           the age in minutes (null for list)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record KafkaMirrorMaker2Response(
    @JsonProperty("name") String name,
    @JsonProperty("namespace") String namespace,
    @JsonProperty("readiness") String readiness,
    @JsonProperty("replicas") ReplicasInfo replicas,
    @JsonProperty("target_cluster") String targetCluster,
    @JsonProperty("source_cluster_aliases") List<String> sourceClusterAliases,
    @JsonProperty("mirrors") List<MirrorInfo> mirrors,
    @JsonProperty("clusters") List<ClusterInfo> clusters,
    @JsonProperty("connector_statuses") List<Map<String, Object>> connectorStatuses,
    @JsonProperty("version") String version,
    @JsonProperty("bootstrap_servers") String bootstrapServers,
    @JsonProperty("conditions") List<ConditionInfo> conditions,
    @JsonProperty("creation_time") Instant creationTime,
    @JsonProperty("age_minutes") Long ageMinutes
) {

    /**
     * Cluster connection information.
     *
     * @param alias              the cluster alias
     * @param bootstrapServers   the bootstrap servers
     * @param authenticationType the authentication type
     * @param tlsEnabled         whether TLS is enabled
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ClusterInfo(
        @JsonProperty("alias") String alias,
        @JsonProperty("bootstrap_servers") String bootstrapServers,
        @JsonProperty("authentication_type") String authenticationType,
        @JsonProperty("tls_enabled") Boolean tlsEnabled
    ) {
    }

    /**
     * Mirror configuration between source and target clusters.
     *
     * @param sourceCluster        the source cluster alias
     * @param topicsPattern        the regex for topics to mirror
     * @param topicsExcludePattern the regex for topics to exclude
     * @param groupsPattern        the regex for consumer groups to mirror
     * @param groupsExcludePattern the regex for consumer groups to exclude
     * @param sourceConnector      the source connector configuration
     * @param checkpointConnector  the checkpoint connector configuration
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MirrorInfo(
        @JsonProperty("source_cluster") String sourceCluster,
        @JsonProperty("topics_pattern") String topicsPattern,
        @JsonProperty("topics_exclude_pattern") String topicsExcludePattern,
        @JsonProperty("groups_pattern") String groupsPattern,
        @JsonProperty("groups_exclude_pattern") String groupsExcludePattern,
        @JsonProperty("source_connector") ConnectorInfo sourceConnector,
        @JsonProperty("checkpoint_connector") ConnectorInfo checkpointConnector
    ) {
    }

    /**
     * Connector configuration summary.
     *
     * @param tasksMax    the maximum number of tasks
     * @param state       the desired connector state
     * @param autoRestart whether auto-restart is enabled
     * @param pause       whether the connector is paused
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ConnectorInfo(
        @JsonProperty("tasks_max") Integer tasksMax,
        @JsonProperty("state") String state,
        @JsonProperty("auto_restart") Boolean autoRestart,
        @JsonProperty("pause") Boolean pause
    ) {
    }

    /**
     * Creates a summary response for list operations.
     *
     * @param name                 the MM2 name
     * @param namespace            the namespace
     * @param readiness            the readiness status
     * @param replicas             the replica counts
     * @param targetCluster        the target cluster alias
     * @param sourceClusterAliases the source cluster aliases
     * @param conditions           the status conditions
     * @return a summary response
     */
    public static KafkaMirrorMaker2Response summary(final String name, final String namespace,
                                                     final String readiness, final ReplicasInfo replicas,
                                                     final String targetCluster,
                                                     final List<String> sourceClusterAliases,
                                                     final List<ConditionInfo> conditions) {
        return new KafkaMirrorMaker2Response(name, namespace, readiness, replicas,
            targetCluster, sourceClusterAliases, null, null, null, null, null,
            conditions, null, null);
    }

    /**
     * Creates a detailed response for get operations with all fields.
     *
     * @param name                 the MM2 name
     * @param namespace            the namespace
     * @param readiness            the readiness status
     * @param replicas             the replica counts
     * @param targetCluster        the target cluster alias
     * @param sourceClusterAliases the source cluster aliases
     * @param mirrors              the mirror configurations
     * @param clusters             the cluster configurations
     * @param connectorStatuses    the connector status maps
     * @param version              the version
     * @param bootstrapServers     the target bootstrap servers
     * @param conditions           the status conditions
     * @param creationTime         the creation time
     * @param ageMinutes           the age in minutes
     * @return a detailed response
     */
    @SuppressWarnings("checkstyle:ParameterNumber")
    public static KafkaMirrorMaker2Response of(final String name, final String namespace,
                                                final String readiness, final ReplicasInfo replicas,
                                                final String targetCluster,
                                                final List<String> sourceClusterAliases,
                                                final List<MirrorInfo> mirrors,
                                                final List<ClusterInfo> clusters,
                                                final List<Map<String, Object>> connectorStatuses,
                                                final String version, final String bootstrapServers,
                                                final List<ConditionInfo> conditions,
                                                final Instant creationTime, final Long ageMinutes) {
        return new KafkaMirrorMaker2Response(name, namespace, readiness, replicas,
            targetCluster, sourceClusterAliases, mirrors, clusters, connectorStatuses,
            version, bootstrapServers, conditions, creationTime, ageMinutes);
    }
}
