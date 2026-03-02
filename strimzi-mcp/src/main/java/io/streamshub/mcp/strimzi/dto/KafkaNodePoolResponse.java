/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.dto;

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
public record KafkaNodePoolResponse(
    @JsonProperty("name") String name,
    @JsonProperty("namespace") String namespace,
    @JsonProperty("cluster") String cluster,
    @JsonProperty("roles") List<String> roles,
    @JsonProperty("replicas") Integer replicas,
    @JsonProperty("storage_type") String storageType,
    @JsonProperty("storage_size") String storageSize
) {
}
