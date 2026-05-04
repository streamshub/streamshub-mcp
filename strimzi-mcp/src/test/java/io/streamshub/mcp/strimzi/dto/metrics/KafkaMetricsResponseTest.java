/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.dto.metrics;

import io.streamshub.mcp.common.dto.metrics.AggregationLevel;
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
 * Unit tests for {@link KafkaMetricsResponse}.
 */
class KafkaMetricsResponseTest {

    KafkaMetricsResponseTest() {
    }

    @Test
    void ofAlwaysPopulatesTimeSeries() {
        List<MetricSample> samples = List.of(
            MetricSample.of("metric_a", Map.of("pod", "pod-0"), 1.0),
            MetricSample.of("metric_a", Map.of("pod", "pod-1"), 2.0)
        );

        KafkaMetricsResponse response = KafkaMetricsResponse.of(
            "my-cluster", "kafka", "pod-scraping", List.of("replication"),
            samples, null, AggregationLevel.BROKER);

        assertNotNull(response.timeSeries());
        assertEquals(2, response.sampleCount());
        assertEquals(1, response.metricCount());
        assertEquals("broker", response.aggregation());
    }

    @Test
    void ofWithRangeDataPopulatesTimeSeries() {
        Instant now = Instant.now();
        List<MetricSample> samples = List.of(
            MetricSample.of("metric_a", Map.of("pod", "pod-0"), 1.0, now),
            MetricSample.of("metric_a", Map.of("pod", "pod-0"), 2.0, now.plusSeconds(60))
        );

        KafkaMetricsResponse response = KafkaMetricsResponse.of(
            "my-cluster", "kafka", "prometheus", List.of("throughput"),
            samples, null, AggregationLevel.BROKER);

        assertNotNull(response.timeSeries());
        assertEquals(1, response.timeSeries().size());
        assertEquals(2, response.sampleCount());
    }

    @Test
    void ofWithEmptySamplesReturnsEmptyTimeSeries() {
        KafkaMetricsResponse response = KafkaMetricsResponse.of(
            "my-cluster", "kafka", "pod-scraping", List.of(),
            List.of(), null, AggregationLevel.BROKER);

        assertNotNull(response.timeSeries());
        assertTrue(response.timeSeries().isEmpty());
        assertEquals(0, response.sampleCount());
        assertEquals(0, response.metricCount());
    }

    @Test
    void ofCountsDistinctMetricNames() {
        List<MetricSample> samples = List.of(
            MetricSample.of("metric_a", Map.of(), 1.0),
            MetricSample.of("metric_a", Map.of(), 2.0),
            MetricSample.of("metric_b", Map.of(), 3.0)
        );

        KafkaMetricsResponse response = KafkaMetricsResponse.of(
            "my-cluster", "kafka", "pod-scraping", List.of(),
            samples, null, AggregationLevel.BROKER);

        assertEquals(2, response.metricCount());
        assertEquals(3, response.sampleCount());
    }

    @Test
    void ofPassesThroughInterpretation() {
        KafkaMetricsResponse response = KafkaMetricsResponse.of(
            "my-cluster", "kafka", "pod-scraping", List.of(),
            List.of(), "test interpretation", AggregationLevel.BROKER);

        assertEquals("test interpretation", response.interpretation());
    }

    @Test
    void emptyCreatesResponseWithNoMetrics() {
        KafkaMetricsResponse response = KafkaMetricsResponse.empty(
            "my-cluster", "kafka", "No pods found");

        assertEquals("my-cluster", response.clusterName());
        assertEquals("kafka", response.namespace());
        assertEquals("No pods found", response.message());
        assertEquals(0, response.sampleCount());
        assertEquals(0, response.metricCount());
        assertNull(response.interpretation());
        assertNotNull(response.timestamp());
    }

    @Test
    void ofTimeSeriesCreatesResponseFromPreGroupedSeries() {
        List<MetricSample> samples = List.of(
            MetricSample.of("metric_a", Map.of("pod", "pod-0"), 10.0),
            MetricSample.of("metric_a", Map.of("pod", "pod-1"), 20.0)
        );
        var series = io.streamshub.mcp.common.dto.metrics.AggregatedTimeSeries
            .fromSamples(samples, AggregationLevel.CLUSTER);

        KafkaMetricsResponse response = KafkaMetricsResponse.ofTimeSeries(
            "my-cluster", "kafka", "prometheus", List.of("replication"),
            series, "guide");

        assertNotNull(response.timeSeries());
        assertEquals(1, response.timeSeries().size());
        assertEquals(1, response.metricCount());
        assertEquals("guide", response.interpretation());
    }
}
