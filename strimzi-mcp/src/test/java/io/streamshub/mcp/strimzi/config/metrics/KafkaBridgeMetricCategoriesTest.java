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
 * Unit tests for {@link KafkaBridgeMetricCategories}.
 */
class KafkaBridgeMetricCategoriesTest {

    KafkaBridgeMetricCategoriesTest() {
    }

    @Test
    void resolveValidCategoryReturnsMetrics() {
        List<String> metrics = KafkaBridgeMetricCategories.resolve("http");
        assertFalse(metrics.isEmpty());
        assertTrue(metrics.contains("strimzi_bridge_http_server_requestCount_total"));
    }

    @Test
    void resolveUnknownCategoryReturnsEmpty() {
        assertTrue(KafkaBridgeMetricCategories.resolve("nonexistent").isEmpty());
    }

    @Test
    void resolveNullReturnsEmpty() {
        assertTrue(KafkaBridgeMetricCategories.resolve(null).isEmpty());
    }

    @Test
    void allCategoriesReturnsFourCategories() {
        Set<String> categories = KafkaBridgeMetricCategories.allCategories();
        assertEquals(4, categories.size());
        assertTrue(categories.contains("http"));
        assertTrue(categories.contains("producer"));
        assertTrue(categories.contains("consumer"));
        assertTrue(categories.contains("resources"));
    }

    @Test
    void interpretationWithValidCategoryReturnsGuide() {
        String interpretation = KafkaBridgeMetricCategories.interpretation(List.of("http"));
        assertNotNull(interpretation);
        assertTrue(interpretation.contains("strimzi_bridge_http_server_requestCount_total"));
    }

    @Test
    void interpretationWithNullReturnsNull() {
        assertNull(KafkaBridgeMetricCategories.interpretation(null));
    }

    @Test
    void interpretationWithEmptyListReturnsNull() {
        assertNull(KafkaBridgeMetricCategories.interpretation(List.of()));
    }

    @Test
    void maxGranularityAlwaysReturnsCluster() {
        assertEquals(AggregationLevel.CLUSTER, KafkaBridgeMetricCategories.maxGranularity("http"));
        assertEquals(AggregationLevel.CLUSTER, KafkaBridgeMetricCategories.maxGranularity("producer"));
        assertEquals(AggregationLevel.CLUSTER, KafkaBridgeMetricCategories.maxGranularity("consumer"));
        assertEquals(AggregationLevel.CLUSTER, KafkaBridgeMetricCategories.maxGranularity("resources"));
    }

    @Test
    void maxGranularityNullReturnsCluster() {
        assertEquals(AggregationLevel.CLUSTER, KafkaBridgeMetricCategories.maxGranularity(null));
    }

    @Test
    void maxGranularityUnknownReturnsCluster() {
        assertEquals(AggregationLevel.CLUSTER, KafkaBridgeMetricCategories.maxGranularity("nonexistent"));
    }
}
