/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.config.metrics;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link KafkaMetricCategories}.
 */
class KafkaMetricCategoriesTest {

    KafkaMetricCategoriesTest() {
    }

    @Test
    void resolveValidCategoryReturnsMetrics() {
        List<String> metrics = KafkaMetricCategories.resolve("replication");
        assertFalse(metrics.isEmpty());
        assertTrue(metrics.contains("kafka_server_replicamanager_underreplicatedpartitions"));
    }

    @Test
    void resolveUnknownCategoryReturnsEmpty() {
        List<String> metrics = KafkaMetricCategories.resolve("nonexistent");
        assertTrue(metrics.isEmpty());
    }

    @Test
    void resolveNullReturnsEmpty() {
        List<String> metrics = KafkaMetricCategories.resolve(null);
        assertTrue(metrics.isEmpty());
    }

    @Test
    void resolveIsCaseInsensitive() {
        List<String> lower = KafkaMetricCategories.resolve("replication");
        List<String> upper = KafkaMetricCategories.resolve("REPLICATION");
        List<String> mixed = KafkaMetricCategories.resolve("Replication");
        assertEquals(lower, upper);
        assertEquals(lower, mixed);
    }

    @Test
    void allCategoriesReturnsFourCategories() {
        Set<String> categories = KafkaMetricCategories.allCategories();
        assertEquals(4, categories.size());
        assertTrue(categories.contains("replication"));
        assertTrue(categories.contains("throughput"));
        assertTrue(categories.contains("resources"));
        assertTrue(categories.contains("performance"));
    }

    @Test
    void interpretationWithValidCategoryReturnsGuide() {
        String interpretation = KafkaMetricCategories.interpretation(List.of("replication"));
        assertNotNull(interpretation);
        assertTrue(interpretation.contains("underreplicatedpartitions"));
    }

    @Test
    void interpretationWithMultipleCategoriesJoinsThem() {
        String interpretation = KafkaMetricCategories.interpretation(
            List.of("replication", "performance"));
        assertNotNull(interpretation);
        assertTrue(interpretation.contains("underreplicatedpartitions"));
        assertTrue(interpretation.contains("brokerrequesthandleravgidle_percent"));
    }

    @Test
    void interpretationWithNullReturnsNull() {
        assertNull(KafkaMetricCategories.interpretation(null));
    }

    @Test
    void interpretationWithEmptyListReturnsNull() {
        assertNull(KafkaMetricCategories.interpretation(List.of()));
    }

    @Test
    void interpretationWithUnknownCategoryReturnsNull() {
        assertNull(KafkaMetricCategories.interpretation(List.of("nonexistent")));
    }
}
