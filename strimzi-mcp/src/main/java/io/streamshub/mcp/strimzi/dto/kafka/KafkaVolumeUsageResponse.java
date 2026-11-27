/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.dto.kafka;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Disk usage of a Kafka cluster's PersistentVolumeClaims against their capacity, from the
 * kubelet's {@code kubelet_volume_stats_*} metrics.
 *
 * @param volumes the PVCs that have volume stats, ordered by name
 * @param message what was found, or why there is no data
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record KafkaVolumeUsageResponse(
    @JsonProperty("volumes") List<VolumeUsage> volumes,
    @JsonProperty("message") String message
) {

    /**
     * Creates a response.
     *
     * @param volumes the PVC usage entries, or null
     * @param message what was found, or why there is no data
     * @return the response
     */
    public static KafkaVolumeUsageResponse of(final List<VolumeUsage> volumes, final String message) {
        return new KafkaVolumeUsageResponse(volumes != null ? List.copyOf(volumes) : List.of(), message);
    }

    /**
     * Usage of one PVC.
     *
     * @param pvc           the PersistentVolumeClaim name
     * @param usedBytes     bytes in use on the volume, or null when not reported
     * @param capacityBytes the volume's capacity in bytes, or null when not reported
     * @param usagePercent  used / capacity as a percentage with one decimal, or null when either
     *                      value is missing
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record VolumeUsage(
        @JsonProperty("pvc") String pvc,
        @JsonProperty("used_bytes") Long usedBytes,
        @JsonProperty("capacity_bytes") Long capacityBytes,
        @JsonProperty("usage_percent") Double usagePercent
    ) {

        /**
         * Creates a usage entry and computes the percentage.
         *
         * @param pvc           the PVC name
         * @param usedBytes     bytes in use, or null
         * @param capacityBytes capacity in bytes, or null
         * @return the usage entry
         */
        public static VolumeUsage of(final String pvc, final Long usedBytes, final Long capacityBytes) {
            Double percent = null;
            if (usedBytes != null && capacityBytes != null && capacityBytes > 0) {
                percent = Math.round(usedBytes * 1000.0 / capacityBytes) / 10.0;
            }
            return new VolumeUsage(pvc, usedBytes, capacityBytes, percent);
        }
    }
}
