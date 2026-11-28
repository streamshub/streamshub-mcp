/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.util;

import io.fabric8.kubernetes.api.model.Quantity;
import io.streamshub.mcp.strimzi.dto.kafkanodepool.StorageVolumeInfo;
import io.strimzi.api.kafka.model.kafka.EphemeralStorage;
import io.strimzi.api.kafka.model.kafka.JbodStorage;
import io.strimzi.api.kafka.model.kafka.PersistentClaimStorage;
import io.strimzi.api.kafka.model.kafka.SingleVolumeStorage;
import io.strimzi.api.kafka.model.kafka.Storage;

import java.util.List;

/**
 * Helpers that describe Strimzi {@link Storage} definitions, including JBOD.
 */
public final class KafkaStorageUtils {

    private KafkaStorageUtils() {
    }

    /**
     * Computes the per-broker total of the persistent-claim storage.
     * For JBOD the persistent-claim volume sizes are summed and the format of the first
     * volume is kept (for example {@code 50Gi + 50Gi} gives {@code 100Gi}). Ephemeral
     * volumes are ignored.
     *
     * @param storage the storage definition, may be null
     * @return the total size, or null when no volume has a size, a size cannot be parsed,
     *         or the storage is of another type
     */
    public static String totalSize(final Storage storage) {
        if (storage instanceof PersistentClaimStorage pcs) {
            return pcs.getSize();
        }
        if (storage instanceof JbodStorage jbod && jbod.getVolumes() != null) {
            try {
                Quantity total = null;
                for (SingleVolumeStorage volume : jbod.getVolumes()) {
                    if (volume instanceof PersistentClaimStorage pcs && pcs.getSize() != null) {
                        Quantity size = new Quantity(pcs.getSize());
                        total = total == null ? size : total.add(size);
                    }
                }
                return total != null ? total.toString() : null;
            } catch (IllegalArgumentException | ArithmeticException e) {
                return null;
            }
        }
        return null;
    }

    /**
     * Lists the volumes of a JBOD storage definition in spec order.
     *
     * @param storage the storage definition, may be null
     * @return one entry per volume, or null when the storage is not JBOD
     */
    public static List<StorageVolumeInfo> volumes(final Storage storage) {
        if (storage instanceof JbodStorage jbod && jbod.getVolumes() != null) {
            return jbod.getVolumes().stream().map(KafkaStorageUtils::toVolumeInfo).toList();
        }
        return null;
    }

    private static StorageVolumeInfo toVolumeInfo(final SingleVolumeStorage volume) {
        return switch (volume) {
            case PersistentClaimStorage pcs ->
                StorageVolumeInfo.of(pcs.getId(), pcs.getType(), pcs.getSize(), pcs.isDeleteClaim());
            case EphemeralStorage eph ->
                StorageVolumeInfo.of(eph.getId(), eph.getType(), eph.getSizeLimit(), null);
            default -> StorageVolumeInfo.of(volume.getId(), volume.getType(), null, null);
        };
    }
}
