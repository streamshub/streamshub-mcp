/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.service.kafka;

import io.streamshub.mcp.common.service.metrics.MetricsQueryService;
import io.streamshub.mcp.strimzi.dto.metrics.KafkaMetricsResponse;
import io.streamshub.mcp.strimzi.service.metrics.KafkaMetricsService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

/**
 * Tests that {@link KafkaMetricsDiagnosticService} carries the sample-limit truncation flag from the
 * single combined scrape into every per-category response.
 */
class KafkaMetricsDiagnosticServiceTruncationTest {

    KafkaMetricsDiagnosticServiceTruncationTest() {
    }

    private static Map<String, KafkaMetricsResponse> gather(final KafkaMetricsResponse scraped, final int limit) {
        KafkaMetricsService metricsService = Mockito.mock(KafkaMetricsService.class);
        MetricsQueryService queryService = Mockito.mock(MetricsQueryService.class);
        when(metricsService.getKafkaMetrics(anyString(), anyString(), isNull(), anyString(),
            any(), any(), any(), any(), isNull(), isNull())).thenReturn(scraped);
        when(queryService.maxSamples()).thenReturn(limit);

        KafkaMetricsDiagnosticService service = new KafkaMetricsDiagnosticService();
        service.kafkaMetricsService = metricsService;
        service.metricsQueryService = queryService;

        return service.gatherAllMetrics("kafka", "my-cluster", KafkaMetricsDiagnosticService.InvestigationAreas.all(),
            null, null, null, null, new ArrayList<>(), new ArrayList<>());
    }

    private static KafkaMetricsResponse scrape() {
        return KafkaMetricsResponse.ofTimeSeries("my-cluster", "kafka", "pod-scraping", List.of(), List.of(), "guide");
    }

    /**
     * Verify every category response is flagged truncated and names the real limit.
     */
    @Test
    void testTruncatedScrapeFlagsEveryCategory() {
        Map<String, KafkaMetricsResponse> results = gather(scrape().withTruncation(10000), 10000);

        assertEquals(4, results.size());
        results.forEach((category, response) -> {
            assertEquals(Boolean.TRUE, response.truncated(), category);
            assertTrue(response.message().contains("at 10000 samples"), response.message());
        });
    }

    /**
     * Verify a complete scrape leaves the categories unflagged.
     */
    @Test
    void testCompleteScrapeLeavesCategoriesUnflagged() {
        Map<String, KafkaMetricsResponse> results = gather(scrape(), 10000);

        assertEquals(4, results.size());
        results.values().forEach(response -> assertNull(response.truncated()));
    }
}
