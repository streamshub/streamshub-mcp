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
 * Unit tests for {@link StrimziOperatorMetricCategories}.
 */
class StrimziOperatorMetricCategoriesTest {

    StrimziOperatorMetricCategoriesTest() {
    }

    @Test
    void resolveValidCategoryReturnsMetrics() {
        List<String> metrics = StrimziOperatorMetricCategories.resolve("reconciliation");
        assertFalse(metrics.isEmpty());
        assertTrue(metrics.contains("strimzi_reconciliations_successful_total"));
    }

    @Test
    void resolveUnknownCategoryReturnsEmpty() {
        List<String> metrics = StrimziOperatorMetricCategories.resolve("nonexistent");
        assertTrue(metrics.isEmpty());
    }

    @Test
    void resolveNullReturnsEmpty() {
        List<String> metrics = StrimziOperatorMetricCategories.resolve(null);
        assertTrue(metrics.isEmpty());
    }

    @Test
    void resolveIsCaseInsensitive() {
        List<String> lower = StrimziOperatorMetricCategories.resolve("reconciliation");
        List<String> upper = StrimziOperatorMetricCategories.resolve("RECONCILIATION");
        assertEquals(lower, upper);
    }

    @Test
    void allCategoriesReturnsThreeCategories() {
        Set<String> categories = StrimziOperatorMetricCategories.allCategories();
        assertEquals(3, categories.size());
        assertTrue(categories.contains("reconciliation"));
        assertTrue(categories.contains("resources"));
        assertTrue(categories.contains("jvm"));
    }

    @Test
    void interpretationWithValidCategoryReturnsGuide() {
        String interpretation = StrimziOperatorMetricCategories.interpretation(
            List.of("reconciliation"));
        assertNotNull(interpretation);
        assertTrue(interpretation.contains("strimzi_reconciliations_successful_total"));
    }

    @Test
    void interpretationWithNullReturnsNull() {
        assertNull(StrimziOperatorMetricCategories.interpretation(null));
    }

    @Test
    void interpretationWithEmptyListReturnsNull() {
        assertNull(StrimziOperatorMetricCategories.interpretation(List.of()));
    }

    @Test
    void interpretationWithUnknownCategoryReturnsNull() {
        assertNull(StrimziOperatorMetricCategories.interpretation(List.of("nonexistent")));
    }

    @Test
    void resolveJvmCategoryReturnsMicrometerNames() {
        List<String> metrics = StrimziOperatorMetricCategories.resolve("jvm");
        assertEquals(6, metrics.size());
        assertTrue(metrics.contains("jvm_memory_used_bytes"));
        assertTrue(metrics.contains("jvm_memory_max_bytes"));
        assertTrue(metrics.contains("jvm_gc_pause_seconds_count"));
        assertTrue(metrics.contains("jvm_gc_pause_seconds_sum"));
        assertTrue(metrics.contains("process_cpu_usage"));
        assertTrue(metrics.contains("jvm_threads_live_threads"));
        // Micrometer forms only — JMX exporter forms must be absent
        assertFalse(metrics.contains("jvm_gc_collection_seconds_count"));
        assertFalse(metrics.contains("jvm_gc_collection_seconds_sum"));
        assertFalse(metrics.contains("process_cpu_seconds_total"));
        assertFalse(metrics.contains("jvm_threads_current"));
    }

    @Test
    void resolveReconciliationCategoryIncludesNewMetrics() {
        List<String> metrics = StrimziOperatorMetricCategories.resolve("reconciliation");
        assertTrue(metrics.contains("strimzi_reconciliations_locked_total"));
        assertTrue(metrics.contains("strimzi_reconciliations_periodical_total"));
    }

    @Test
    void resolveResourcesCategoryIncludesCertificateMetric() {
        List<String> metrics = StrimziOperatorMetricCategories.resolve("resources");
        assertTrue(metrics.contains("strimzi_certificate_expiration_timestamp_ms"));
    }

    @Test
    void maxGranularityAlwaysReturnsCluster() {
        assertEquals(AggregationLevel.CLUSTER, StrimziOperatorMetricCategories.maxGranularity("reconciliation"));
        assertEquals(AggregationLevel.CLUSTER, StrimziOperatorMetricCategories.maxGranularity("resources"));
        assertEquals(AggregationLevel.CLUSTER, StrimziOperatorMetricCategories.maxGranularity("jvm"));
    }

    @Test
    void maxGranularityNullReturnsCluster() {
        assertEquals(AggregationLevel.CLUSTER, StrimziOperatorMetricCategories.maxGranularity(null));
    }

    @Test
    void maxGranularityUnknownReturnsCluster() {
        assertEquals(AggregationLevel.CLUSTER, StrimziOperatorMetricCategories.maxGranularity("nonexistent"));
    }
}
