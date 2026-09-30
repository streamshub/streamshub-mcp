/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.dto.kafka;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Detailed information for a single PersistentVolumeClaim backing Kafka storage.
 *
 * @param name                 the PVC name
 * @param namespace            the PVC namespace
 * @param phase                the PVC phase (Bound, Pending, Lost)
 * @param storageClass         the requested storage class name
 * @param requestedCapacity    the requested storage capacity (e.g. "100Gi")
 * @param actualCapacity       the actual bound capacity from status (e.g. "100Gi")
 * @param volumeName           the backing PersistentVolume name
 * @param allowVolumeExpansion whether the StorageClass permits volume expansion
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record KafkaPvcInfo(
    @JsonProperty("name") String name,
    @JsonProperty("namespace") String namespace,
    @JsonProperty("phase") String phase,
    @JsonProperty("storage_class") String storageClass,
    @JsonProperty("requested_capacity") String requestedCapacity,
    @JsonProperty("actual_capacity") String actualCapacity,
    @JsonProperty("volume_name") String volumeName,
    @JsonProperty("allow_volume_expansion") Boolean allowVolumeExpansion
) {

    /**
     * Creates a new Kafka PVC info record.
     *
     * @param name                 the PVC name
     * @param namespace            the PVC namespace
     * @param phase                the PVC phase
     * @param storageClass         the requested storage class name
     * @param requestedCapacity    the requested storage capacity
     * @param actualCapacity       the actual bound capacity
     * @param volumeName           the backing PersistentVolume name
     * @param allowVolumeExpansion whether volume expansion is allowed
     * @return a new info record
     */
    @SuppressWarnings("checkstyle:ParameterNumber")
    public static KafkaPvcInfo of(
            final String name,
            final String namespace,
            final String phase,
            final String storageClass,
            final String requestedCapacity,
            final String actualCapacity,
            final String volumeName,
            final Boolean allowVolumeExpansion) {
        return new KafkaPvcInfo(
            name,
            namespace,
            phase,
            storageClass,
            requestedCapacity,
            actualCapacity,
            volumeName,
            allowVolumeExpansion);
    }
}
