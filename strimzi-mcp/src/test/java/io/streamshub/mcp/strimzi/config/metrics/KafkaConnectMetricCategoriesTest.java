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
 * Unit tests for {@link KafkaConnectMetricCategories}.
 */
class KafkaConnectMetricCategoriesTest {

    KafkaConnectMetricCategoriesTest() {
    }

    @Test
    void resolveValidCategoryReturnsMetrics() {
        List<String> metrics = KafkaConnectMetricCategories.resolve("worker");
        assertFalse(metrics.isEmpty());
        assertTrue(metrics.contains("kafka_connect_worker_connector_count"));
    }

    @Test
    void resolveConnectorCategoryReturnsMetrics() {
        List<String> metrics = KafkaConnectMetricCategories.resolve("connector");
        assertFalse(metrics.isEmpty());
        assertTrue(metrics.contains("kafka_connect_connector_task_batch_size_avg"));
    }

    @Test
    void resolveSourceCategoryReturnsMetrics() {
        List<String> metrics = KafkaConnectMetricCategories.resolve("source");
        assertFalse(metrics.isEmpty());
        assertTrue(metrics.contains("kafka_connect_source_task_source_record_poll_total"));
    }

    @Test
    void resolveSinkCategoryReturnsMetrics() {
        List<String> metrics = KafkaConnectMetricCategories.resolve("sink");
        assertFalse(metrics.isEmpty());
        assertTrue(metrics.contains("kafka_connect_sink_task_sink_record_read_total"));
    }

    @Test
    void resolveUnknownCategoryReturnsEmpty() {
        assertTrue(KafkaConnectMetricCategories.resolve("nonexistent").isEmpty());
    }

    @Test
    void resolveNullReturnsEmpty() {
        assertTrue(KafkaConnectMetricCategories.resolve(null).isEmpty());
    }

    @Test
    void allCategoriesReturnsFiveCategories() {
        Set<String> categories = KafkaConnectMetricCategories.allCategories();
        assertEquals(5, categories.size());
        assertTrue(categories.contains("worker"));
        assertTrue(categories.contains("connector"));
        assertTrue(categories.contains("source"));
        assertTrue(categories.contains("sink"));
        assertTrue(categories.contains("resources"));
    }

    @Test
    void interpretationWithValidCategoryReturnsGuide() {
        String interpretation = KafkaConnectMetricCategories.interpretation(List.of("worker"));
        assertNotNull(interpretation);
        assertTrue(interpretation.contains("kafka_connect_worker_connector_count"));
    }

    @Test
    void interpretationWithNullReturnsNull() {
        assertNull(KafkaConnectMetricCategories.interpretation(null));
    }

    @Test
    void interpretationWithEmptyListReturnsNull() {
        assertNull(KafkaConnectMetricCategories.interpretation(List.of()));
    }

    @Test
    void maxGranularityAlwaysReturnsCluster() {
        assertEquals(AggregationLevel.CLUSTER, KafkaConnectMetricCategories.maxGranularity("worker"));
        assertEquals(AggregationLevel.CLUSTER, KafkaConnectMetricCategories.maxGranularity("connector"));
        assertEquals(AggregationLevel.CLUSTER, KafkaConnectMetricCategories.maxGranularity("source"));
        assertEquals(AggregationLevel.CLUSTER, KafkaConnectMetricCategories.maxGranularity("sink"));
        assertEquals(AggregationLevel.CLUSTER, KafkaConnectMetricCategories.maxGranularity("resources"));
    }

    @Test
    void maxGranularityNullReturnsCluster() {
        assertEquals(AggregationLevel.CLUSTER, KafkaConnectMetricCategories.maxGranularity(null));
    }

    @Test
    void maxGranularityUnknownReturnsCluster() {
        assertEquals(AggregationLevel.CLUSTER, KafkaConnectMetricCategories.maxGranularity("nonexistent"));
    }
}
