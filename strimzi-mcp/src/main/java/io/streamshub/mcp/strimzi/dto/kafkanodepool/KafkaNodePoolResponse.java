/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.dto.kafkanodepool;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
/**
 * Response containing KafkaNodePool information.
 * Represents a node pool in KRaft mode Kafka deployments.
 *
 * @param name        the node pool name
 * @param namespace   the Kubernetes namespace
 * @param cluster     the parent Kafka cluster name
 * @param roles          the roles this node pool serves in spec (e.g., broker, controller)
 * @param replicas       the desired number of replicas in spec
 * @param statusReplicas the actual number of replicas from status
 * @param statusRoles    the actual roles assigned from status
 * @param nodeIds        the node IDs used in this pool from status
 * @param storageType    the type of storage used (optional)
 * @param storageSize    the size of storage (optional)
 * @param ready          whether the node pool condition is Ready
 * @param conditions     the status conditions
 * @param reconciliation reconciliation status tracking (generation vs observedGeneration)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record KafkaNodePoolResponse(
    @JsonProperty("name") String name,
    @JsonProperty("namespace") String namespace,
    @JsonProperty("cluster") String cluster,
    @JsonProperty("roles") List<String> roles,
    @JsonProperty("replicas") Integer replicas,
    @JsonProperty("status_replicas") Integer statusReplicas,
    @JsonProperty("status_roles") List<String> statusRoles,
    @JsonProperty("node_ids") List<Integer> nodeIds,
    @JsonProperty("storage_type") String storageType,
    @JsonProperty("storage_size") String storageSize,
    @JsonProperty("ready") Boolean ready,
    @JsonProperty("conditions") List<io.streamshub.mcp.common.dto.ConditionInfo> conditions,
    @JsonProperty("reconciliation") io.streamshub.mcp.common.dto.ReconciliationInfo reconciliation
) {

    /**
     * Creates a node pool response with the given fields.
     *
     * @param name           the node pool name
     * @param namespace      the Kubernetes namespace
     * @param cluster        the parent Kafka cluster name
     * @param roles          the roles this node pool serves in spec
     * @param replicas       the desired number of replicas in spec
     * @param statusReplicas the actual number of replicas from status
     * @param statusRoles    the actual roles assigned from status
     * @param nodeIds        the node IDs used in this pool from status
     * @param storageType    the type of storage used
     * @param storageSize    the size of storage
     * @param ready          whether the node pool condition is Ready
     * @param conditions     the status conditions
     * @param reconciliation reconciliation status tracking
     * @return a new node pool response
     */
    @SuppressWarnings("checkstyle:ParameterNumber")
    public static KafkaNodePoolResponse of(String name, String namespace, String cluster,
                                            List<String> roles, Integer replicas,
                                            Integer statusReplicas, List<String> statusRoles,
                                            List<Integer> nodeIds,
                                            String storageType, String storageSize,
                                            Boolean ready,
                                            List<io.streamshub.mcp.common.dto.ConditionInfo> conditions,
                                            io.streamshub.mcp.common.dto.ReconciliationInfo reconciliation) {
        return new KafkaNodePoolResponse(name, namespace, cluster, roles, replicas, statusReplicas, statusRoles,
            nodeIds, storageType, storageSize, ready, conditions, reconciliation);
    }
}