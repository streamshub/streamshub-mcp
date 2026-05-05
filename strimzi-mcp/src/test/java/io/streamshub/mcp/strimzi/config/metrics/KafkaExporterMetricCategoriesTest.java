/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.config.metrics;

import io.streamshub.mcp.common.dto.metrics.AggregationLevel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link KafkaExporterMetricCategories}.
 */
class KafkaExporterMetricCategoriesTest {

    KafkaExporterMetricCategoriesTest() {
    }

    @Test
    void resolveValidCategoryReturnsMetrics() {
        List<String> metrics = KafkaExporterMetricCategories.resolve("consumer_lag");
        assertFalse(metrics.isEmpty());
        assertTrue(metrics.contains("kafka_consumergroup_lag"));
    }

    @Test
    void resolveUnknownCategoryReturnsEmpty() {
        assertTrue(KafkaExporterMetricCategories.resolve("nonexistent").isEmpty());
    }

    @Test
    void resolveNullReturnsEmpty() {
        assertTrue(KafkaExporterMetricCategories.resolve(null).isEmpty());
    }

    @Test
    void allCategoriesReturnsThreeCategories() {
        Set<String> categories = KafkaExporterMetricCategories.allCategories();
        assertEquals(3, categories.size());
        assertTrue(categories.contains("consumer_lag"));
        assertTrue(categories.contains("partitions"));
        assertTrue(categories.contains("resources"));
    }

    @Test
    void interpretationWithValidCategoryReturnsGuide() {
        String interpretation = KafkaExporterMetricCategories.interpretation(List.of("consumer_lag"));
        assertNotNull(interpretation);
        assertTrue(interpretation.contains("kafka_consumergroup_lag"));
    }

    @Test
    void interpretationWithNullReturnsNull() {
        assertNull(KafkaExporterMetricCategories.interpretation(null));
    }

    @Test
    void interpretationWithEmptyListReturnsNull() {
        assertNull(KafkaExporterMetricCategories.interpretation(List.of()));
    }

    @Test
    void maxGranularityConsumerLagReturnsPartition() {
        assertEquals(AggregationLevel.PARTITION, KafkaExporterMetricCategories.maxGranularity("consumer_lag"));
        assertEquals(AggregationLevel.PARTITION, KafkaExporterMetricCategories.maxGranularity("CONSUMER_LAG"));
    }

    @Test
    void maxGranularityPartitionsReturnsPartition() {
        assertEquals(AggregationLevel.PARTITION, KafkaExporterMetricCategories.maxGranularity("partitions"));
    }

    @Test
    void maxGranularityResourcesReturnsBroker() {
        assertEquals(AggregationLevel.BROKER, KafkaExporterMetricCategories.maxGranularity("resources"));
    }

    @Test
    void maxGranularityNullReturnsBroker() {
        assertEquals(AggregationLevel.BROKER, KafkaExporterMetricCategories.maxGranularity(null));
    }

    @Test
    void maxGranularityUnknownReturnsBroker() {
        assertEquals(AggregationLevel.BROKER, KafkaExporterMetricCategories.maxGranularity("nonexistent"));
    }
}
