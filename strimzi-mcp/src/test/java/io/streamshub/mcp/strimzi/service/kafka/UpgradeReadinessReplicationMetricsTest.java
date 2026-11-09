/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.service.kafka;

import io.streamshub.mcp.strimzi.dto.metrics.KafkaMetricsResponse;
import io.streamshub.mcp.strimzi.service.metrics.KafkaMetricsService;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests that the upgrade readiness workflow gathers replication metrics over a time window,
 * so the analysis can judge lag as a trend.
 */
class UpgradeReadinessReplicationMetricsTest {

    UpgradeReadinessReplicationMetricsTest() {
    }

    private static UpgradeReadinessDiagnosticService serviceWith(final KafkaMetricsService metricsService) {
        UpgradeReadinessDiagnosticService service = new UpgradeReadinessDiagnosticService();
        service.metricsService = metricsService;
        return service;
    }

    /**
     * Verify replication metrics are requested with the 15-minute window the prompts use.
     */
    @Test
    void testReplicationMetricsUseRangeWindow() {
        KafkaMetricsService metricsService = mock(KafkaMetricsService.class);
        KafkaMetricsResponse response = KafkaMetricsResponse.empty("c", "kafka", "none");
        when(metricsService.getKafkaMetrics(anyString(), anyString(), anyString(), any(), any(), any(), any(),
            any(), any(), any())).thenReturn(response);
        List<String> completed = new ArrayList<>();

        KafkaMetricsResponse result = serviceWith(metricsService)
            .gatherReplicationMetrics("kafka", "c", completed, new ArrayList<>());

        assertNotNull(result);
        verify(metricsService).getKafkaMetrics(eq("kafka"), eq("c"), eq("replication"), isNull(), eq(15),
            isNull(), isNull(), isNull(), isNull(), isNull());
        assertTrue(completed.contains("replication_metrics"));
    }

    /**
     * Verify a metrics failure is recorded and does not abort the workflow.
     */
    @Test
    void testReplicationMetricsFailureIsRecorded() {
        KafkaMetricsService metricsService = mock(KafkaMetricsService.class);
        when(metricsService.getKafkaMetrics(anyString(), anyString(), anyString(), any(), any(), any(), any(),
            any(), any(), any())).thenThrow(new IllegalStateException("no provider"));
        List<String> failed = new ArrayList<>();

        KafkaMetricsResponse result = serviceWith(metricsService)
            .gatherReplicationMetrics("kafka", "c", new ArrayList<>(), failed);

        assertNull(result);
        assertEquals(1, failed.size());
        assertTrue(failed.getFirst().startsWith("replication_metrics: "), failed.getFirst());
    }
}
