/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.service.kafka;

import io.streamshub.mcp.strimzi.config.metrics.KafkaMetricCategories;
import io.streamshub.mcp.strimzi.dto.metrics.KafkaMetricsResponse;
import io.streamshub.mcp.strimzi.service.metrics.KafkaMetricsService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests that {@link KafkaMetricsDiagnosticService#gatherAllMetrics} queries each category on its own,
 * so a truncated or failing category does not affect the others.
 */
class KafkaMetricsDiagnosticServiceGatherTest {

    private static final List<String> CATEGORIES = List.of(
        KafkaMetricCategories.REPLICATION, KafkaMetricCategories.PERFORMANCE,
        KafkaMetricCategories.RESOURCES, KafkaMetricCategories.THROUGHPUT);

    private final KafkaMetricsService metricsService = Mockito.mock(KafkaMetricsService.class);
    private final List<String> completed = new ArrayList<>();
    private final List<String> failed = new ArrayList<>();

    KafkaMetricsDiagnosticServiceGatherTest() {
    }

    private void givenCategory(final String category, final KafkaMetricsResponse response) {
        when(metricsService.getKafkaMetrics(eq("kafka"), eq("my-cluster"), eq(category), isNull(),
            any(), any(), any(), any(), isNull(), isNull())).thenReturn(response);
    }

    private Map<String, KafkaMetricsResponse> gather() {
        KafkaMetricsDiagnosticService service = new KafkaMetricsDiagnosticService();
        service.kafkaMetricsService = metricsService;
        return service.gatherAllMetrics("kafka", "my-cluster", KafkaMetricsDiagnosticService.InvestigationAreas.all(),
            null, null, null, null, completed, failed);
    }

    private static KafkaMetricsResponse response(final String category, final Integer truncatedAtLimit) {
        return KafkaMetricsResponse.ofTimeSeries("my-cluster", "kafka", "pod-scraping", List.of(category), List.of(),
            "guide", truncatedAtLimit);
    }

    /**
     * Verify each category is queried with its own category name and its response is used as is.
     */
    @Test
    void testQueriesEachCategorySeparately() {
        CATEGORIES.forEach(c -> givenCategory(c, response(c, null)));

        Map<String, KafkaMetricsResponse> results = gather();

        assertEquals(CATEGORIES, List.copyOf(results.keySet()));
        CATEGORIES.forEach(c -> {
            verify(metricsService).getKafkaMetrics(eq("kafka"), eq("my-cluster"), eq(c), isNull(),
                any(), any(), any(), any(), isNull(), isNull());
            assertEquals(List.of(c), results.get(c).categories());
            assertNull(results.get(c).truncated(), c);
        });
        assertEquals(4, completed.size());
        assertTrue(failed.isEmpty());
    }

    /**
     * Verify a truncated category is flagged without flagging the others.
     */
    @Test
    void testTruncationStaysWithItsCategory() {
        CATEGORIES.forEach(c -> givenCategory(c, response(c, null)));
        KafkaMetricsResponse truncated = response(KafkaMetricCategories.PERFORMANCE, 10000);
        givenCategory(KafkaMetricCategories.PERFORMANCE, truncated);

        Map<String, KafkaMetricsResponse> results = gather();

        assertSame(truncated, results.get(KafkaMetricCategories.PERFORMANCE));
        assertEquals(Boolean.TRUE, truncated.truncated());
        assertNull(results.get(KafkaMetricCategories.REPLICATION).truncated());
        assertNull(results.get(KafkaMetricCategories.THROUGHPUT).truncated());
    }

    /**
     * Verify a failing category is reported as failed while the others still return data.
     */
    @Test
    void testFailingCategoryDoesNotDropTheOthers() {
        CATEGORIES.forEach(c -> givenCategory(c, response(c, null)));
        when(metricsService.getKafkaMetrics(eq("kafka"), eq("my-cluster"), eq(KafkaMetricCategories.RESOURCES),
            isNull(), any(), any(), any(), any(), isNull(), isNull()))
            .thenThrow(new IllegalStateException("boom"));

        Map<String, KafkaMetricsResponse> results = gather();

        assertEquals(3, results.size());
        assertEquals(1, failed.size());
        assertTrue(failed.getFirst().contains("boom"), failed.getFirst());
    }
}
