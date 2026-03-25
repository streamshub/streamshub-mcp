/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.dto;

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
 * @param roles       the roles this node pool serves (e.g., broker, controller)
 * @param replicas    the desired number of replicas
 * @param storageType the type of storage used (optional)
 * @param storageSize the size of storage (optional)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record KafkaNodePoolResponse(
    @JsonProperty("name") String name,
    @JsonProperty("namespace") String namespace,
    @JsonProperty("cluster") String cluster,
    @JsonProperty("roles") List<String> roles,
    @JsonProperty("replicas") Integer replicas,
    @JsonProperty("storage_type") String storageType,
    @JsonProperty("storage_size") String storageSize
) {

    /**
     * Creates a node pool response with the given fields.
     *
     * @param name        the node pool name
     * @param namespace   the Kubernetes namespace
     * @param cluster     the parent Kafka cluster name
     * @param roles       the roles this node pool serves
     * @param replicas    the desired number of replicas
     * @param storageType the type of storage used
     * @param storageSize the size of storage
     * @return a new node pool response
     */
    public static KafkaNodePoolResponse of(String name, String namespace, String cluster,
                                            List<String> roles, Integer replicas,
                                            String storageType, String storageSize) {
        return new KafkaNodePoolResponse(name, namespace, cluster, roles, replicas, storageType, storageSize);
    }
}