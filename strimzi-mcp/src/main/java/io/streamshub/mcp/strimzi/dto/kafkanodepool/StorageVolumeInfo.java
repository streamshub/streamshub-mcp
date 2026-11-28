/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.dto.kafkanodepool;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One storage volume of a JBOD node pool.
 *
 * @param id          the volume ID
 * @param type        the volume type (persistent-claim or ephemeral)
 * @param size        the volume size (for ephemeral volumes, the size limit)
 * @param deleteClaim whether the claim is deleted when the cluster is removed (persistent-claim only)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record StorageVolumeInfo(
    @JsonProperty("id") Integer id,
    @JsonProperty("type") String type,
    @JsonProperty("size") String size,
    @JsonProperty("delete_claim") Boolean deleteClaim
) {

    /**
     * Creates a storage volume description.
     *
     * @param id          the volume ID
     * @param type        the volume type
     * @param size        the volume size
     * @param deleteClaim whether the claim is deleted with the cluster
     * @return a new storage volume info
     */
    public static StorageVolumeInfo of(final Integer id, final String type, final String size,
                                       final Boolean deleteClaim) {
        return new StorageVolumeInfo(id, type, size, deleteClaim);
    }
}
