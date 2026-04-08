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
}
