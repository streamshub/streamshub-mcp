/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.dto.metrics;

import io.streamshub.mcp.common.dto.metrics.MetricSample;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link StrimziOperatorMetricsResponse}.
 */
class StrimziOperatorMetricsResponseTest {

    StrimziOperatorMetricsResponseTest() {
    }

    @Test
    void ofWithInstantDataPopulatesMetricsNotTimeSeries() {
        List<MetricSample> samples = List.of(
            MetricSample.of("strimzi_reconciliations_total", Map.of(), 100.0)
        );

        StrimziOperatorMetricsResponse response = StrimziOperatorMetricsResponse.of(
            "cluster-operator", "strimzi", "pod-scraping",
            List.of("reconciliation"), samples, null);

        assertNotNull(response.metrics());
        assertEquals(1, response.metrics().size());
        assertNull(response.timeSeries());
        assertEquals(1, response.sampleCount());
        assertEquals(1, response.metricCount());
    }

    @Test
    void ofWithRangeDataPopulatesTimeSeriesNotMetrics() {
        Instant now = Instant.now();
        List<MetricSample> samples = List.of(
            MetricSample.of("strimzi_reconciliations_total", Map.of(), 100.0, now),
            MetricSample.of("strimzi_reconciliations_total", Map.of(), 105.0, now.plusSeconds(60))
        );

        StrimziOperatorMetricsResponse response = StrimziOperatorMetricsResponse.of(
            "cluster-operator", "strimzi", "prometheus",
            List.of("reconciliation"), samples, null);

        assertNull(response.metrics());
        assertNotNull(response.timeSeries());
        assertEquals(1, response.timeSeries().size());
        assertEquals(2, response.sampleCount());
    }

    @Test
    void ofWithEmptySamplesReturnsEmptyMetrics() {
        StrimziOperatorMetricsResponse response = StrimziOperatorMetricsResponse.of(
            "cluster-operator", "strimzi", "pod-scraping",
            List.of(), List.of(), null);

        assertNotNull(response.metrics());
        assertTrue(response.metrics().isEmpty());
        assertNull(response.timeSeries());
        assertEquals(0, response.sampleCount());
    }

    @Test
    void ofPassesThroughInterpretation() {
        StrimziOperatorMetricsResponse response = StrimziOperatorMetricsResponse.of(
            "cluster-operator", "strimzi", "pod-scraping",
            List.of(), List.of(), "test interpretation");

        assertEquals("test interpretation", response.interpretation());
    }

    @Test
    void emptyCreatesResponseWithNoMetrics() {
        StrimziOperatorMetricsResponse response = StrimziOperatorMetricsResponse.empty(
            "cluster-operator", "strimzi", "No pods found");

        assertEquals("cluster-operator", response.operatorName());
        assertEquals("strimzi", response.namespace());
        assertEquals("No pods found", response.message());
        assertEquals(0, response.sampleCount());
        assertNull(response.interpretation());
        assertNotNull(response.timestamp());
    }
}
