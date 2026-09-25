/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.dto.metrics;

import io.quarkiverse.mcp.server.McpException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link AggregationLevel}.
 */
class AggregationLevelTest {

    AggregationLevelTest() {
    }

    @Test
    void fromStringNullDefaultsToCluster() {
        assertEquals(AggregationLevel.CLUSTER, AggregationLevel.fromString(null));
    }

    @Test
    void fromStringBlankDefaultsToCluster() {
        assertEquals(AggregationLevel.CLUSTER, AggregationLevel.fromString(""));
        assertEquals(AggregationLevel.CLUSTER, AggregationLevel.fromString("   "));
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
        McpException ex = assertThrows(McpException.class, () -> AggregationLevel.fromString("invalid"));
        assertTrue(ex.getMessage().contains("aggregation level must be one of"));
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

    /**
     * The F5 regression: a per-partition category must not fall back to CLUSTER, because
     * averaging a 0/1 gauge across partitions turns "3 partitions under min ISR" into 0.003.
     */
    @Test
    void resolveWithoutRequestKeepsPartitionDetailForPartitionCategories() {
        assertEquals(AggregationLevel.PARTITION, AggregationLevel.resolve(null, AggregationLevel.PARTITION));
        assertEquals(AggregationLevel.PARTITION, AggregationLevel.resolve("", AggregationLevel.PARTITION));
        assertEquals(AggregationLevel.PARTITION, AggregationLevel.resolve("  ", AggregationLevel.PARTITION));
    }

    @Test
    void resolveWithoutRequestDefaultsToClusterForEveryOtherCategory() {
        assertEquals(AggregationLevel.CLUSTER, AggregationLevel.resolve(null, AggregationLevel.TOPIC));
        assertEquals(AggregationLevel.CLUSTER, AggregationLevel.resolve(null, AggregationLevel.BROKER));
        assertEquals(AggregationLevel.CLUSTER, AggregationLevel.resolve(null, AggregationLevel.CLUSTER));
    }

    @Test
    void resolveClampsAnExplicitRequestToTheCategoryCeiling() {
        assertEquals(AggregationLevel.TOPIC, AggregationLevel.resolve("partition", AggregationLevel.TOPIC));
        assertEquals(AggregationLevel.BROKER, AggregationLevel.resolve("BROKER", AggregationLevel.TOPIC));
        assertEquals(AggregationLevel.PARTITION, AggregationLevel.resolve("partition", AggregationLevel.PARTITION));
        assertEquals(AggregationLevel.CLUSTER, AggregationLevel.resolve("cluster", AggregationLevel.PARTITION));
    }

    @Test
    void resolveRejectsAnUnknownLevel() {
        McpException ex = assertThrows(McpException.class,
            () -> AggregationLevel.resolve("rack", AggregationLevel.BROKER));
        assertTrue(ex.getMessage().contains("aggregation level must be one of"));
    }
}
