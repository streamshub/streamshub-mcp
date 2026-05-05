/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.dto.metrics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit tests for {@link AggregationLevel}.
 */
class AggregationLevelTest {

    AggregationLevelTest() {
    }

    @Test
    void fromStringNullDefaultsToBroker() {
        assertEquals(AggregationLevel.BROKER, AggregationLevel.fromString(null));
    }

    @Test
    void fromStringBlankDefaultsToBroker() {
        assertEquals(AggregationLevel.BROKER, AggregationLevel.fromString(""));
        assertEquals(AggregationLevel.BROKER, AggregationLevel.fromString("   "));
    }

    @Test
    void fromStringParsesValidValues() {
        assertEquals(AggregationLevel.PARTITION, AggregationLevel.fromString("partition"));
        assertEquals(AggregationLevel.TOPIC, AggregationLevel.fromString("topic"));
        assertEquals(AggregationLevel.BROKER, AggregationLevel.fromString("broker"));
        assertEquals(AggregationLevel.CLUSTER, AggregationLevel.fromString("cluster"));
    }

    @Test
    void fromStringIsCaseInsensitive() {
        assertEquals(AggregationLevel.PARTITION, AggregationLevel.fromString("PARTITION"));
        assertEquals(AggregationLevel.TOPIC, AggregationLevel.fromString("Topic"));
        assertEquals(AggregationLevel.BROKER, AggregationLevel.fromString("bRoKeR"));
    }

    @Test
    void fromStringInvalidThrows() {
        assertThrows(IllegalArgumentException.class, () -> AggregationLevel.fromString("invalid"));
    }

    @Test
    void clampToFinerThanCeilingReturnsCeiling() {
        assertEquals(AggregationLevel.BROKER, AggregationLevel.PARTITION.clampTo(AggregationLevel.BROKER));
        assertEquals(AggregationLevel.BROKER, AggregationLevel.TOPIC.clampTo(AggregationLevel.BROKER));
        assertEquals(AggregationLevel.TOPIC, AggregationLevel.PARTITION.clampTo(AggregationLevel.TOPIC));
        assertEquals(AggregationLevel.CLUSTER, AggregationLevel.PARTITION.clampTo(AggregationLevel.CLUSTER));
    }

    @Test
    void clampToCoarserThanCeilingKeepsOriginal() {
        assertEquals(AggregationLevel.CLUSTER, AggregationLevel.CLUSTER.clampTo(AggregationLevel.BROKER));
        assertEquals(AggregationLevel.CLUSTER, AggregationLevel.CLUSTER.clampTo(AggregationLevel.PARTITION));
        assertEquals(AggregationLevel.BROKER, AggregationLevel.BROKER.clampTo(AggregationLevel.TOPIC));
    }

    @Test
    void clampToSameLevelReturnsSame() {
        assertEquals(AggregationLevel.BROKER, AggregationLevel.BROKER.clampTo(AggregationLevel.BROKER));
        assertEquals(AggregationLevel.PARTITION, AggregationLevel.PARTITION.clampTo(AggregationLevel.PARTITION));
        assertEquals(AggregationLevel.CLUSTER, AggregationLevel.CLUSTER.clampTo(AggregationLevel.CLUSTER));
    }
}
