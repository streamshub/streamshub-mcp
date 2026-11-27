/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.util;

import io.streamshub.mcp.strimzi.dto.kafkanodepool.StorageVolumeInfo;
import io.strimzi.api.kafka.model.kafka.EphemeralStorageBuilder;
import io.strimzi.api.kafka.model.kafka.JbodStorage;
import io.strimzi.api.kafka.model.kafka.JbodStorageBuilder;
import io.strimzi.api.kafka.model.kafka.PersistentClaimStorageBuilder;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Unit tests for {@link KafkaStorageUtils}.
 */
class KafkaStorageUtilsTest {

    KafkaStorageUtilsTest() {
    }

    private static JbodStorage jbod(final String... sizes) {
        JbodStorageBuilder builder = new JbodStorageBuilder();
        int id = 0;
        for (String size : sizes) {
            builder.addToVolumes(new PersistentClaimStorageBuilder().withId(id++).withSize(size).build());
        }
        return builder.build();
    }

    @Test
    void totalSizeSumsJbodVolumes() {
        assertEquals("100Gi", KafkaStorageUtils.totalSize(jbod("50Gi", "50Gi")));
    }

    @Test
    void totalSizeSingleClaim() {
        assertEquals("100Gi", KafkaStorageUtils.totalSize(
            new PersistentClaimStorageBuilder().withSize("100Gi").build()));
    }

    @Test
    void totalSizeMixedUnits() {
        assertEquals("1074Gi", KafkaStorageUtils.totalSize(jbod("50Gi", "1Ti")));
    }

    @Test
    void totalSizeIgnoresEphemeralVolumes() {
        JbodStorage storage = new JbodStorageBuilder()
            .addToVolumes(new PersistentClaimStorageBuilder().withId(0).withSize("50Gi").build())
            .addToVolumes(new EphemeralStorageBuilder().withId(1).build())
            .build();
        assertEquals("50Gi", KafkaStorageUtils.totalSize(storage));
    }

    @Test
    void totalSizeNullOnUnparsableSize() {
        assertNull(KafkaStorageUtils.totalSize(jbod("50Gi", "fifty")));
    }

    @Test
    void totalSizeNullForEphemeralAndNull() {
        assertNull(KafkaStorageUtils.totalSize(new EphemeralStorageBuilder().build()));
        assertNull(KafkaStorageUtils.totalSize(null));
    }

    @Test
    void volumesListsEachJbodVolume() {
        List<StorageVolumeInfo> volumes = KafkaStorageUtils.volumes(jbod("50Gi", "50Gi"));

        assertEquals(2, volumes.size());
        assertEquals(0, volumes.get(0).id());
        assertEquals(1, volumes.get(1).id());
        assertEquals("persistent-claim", volumes.get(0).type());
        assertEquals("50Gi", volumes.get(0).size());
        assertFalse(volumes.get(0).deleteClaim());
    }

    @Test
    void volumesNullForNonJbod() {
        assertNull(KafkaStorageUtils.volumes(new PersistentClaimStorageBuilder().withSize("1Gi").build()));
        assertNull(KafkaStorageUtils.volumes(null));
    }
}
