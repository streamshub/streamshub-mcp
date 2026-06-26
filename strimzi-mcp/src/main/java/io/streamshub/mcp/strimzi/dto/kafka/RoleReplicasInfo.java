/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.dto.kafka;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Replica and storage information for a specific Kafka node role (broker or controller).
 * A dual-role node pool contributes to both broker and controller counts.
 *
 * @param expected    the expected number of replicas for this role
 * @param ready       the number of ready replicas for this role
 * @param storageType the storage configuration type (ephemeral, persistent-claim, jbod)
 * @param storageSize the total storage allocated (e.g., "100Gi")
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RoleReplicasInfo(
    @JsonProperty("expected") Integer expected,
    @JsonProperty("ready") Integer ready,
    @JsonProperty("storage_type") String storageType,
    @JsonProperty("storage_size") String storageSize
) {
    /**
     * Creates a role replicas info from the given parameters.
     *
     * @param expected    the expected number of replicas
     * @param ready       the number of ready replicas
     * @param storageType the storage type
     * @param storageSize the storage size
     * @return a new RoleReplicasInfo instance
     */
    public static RoleReplicasInfo of(final Integer expected, final Integer ready,
                                       final String storageType, final String storageSize) {
        return new RoleReplicasInfo(expected, ready, storageType, storageSize);
    }
}
