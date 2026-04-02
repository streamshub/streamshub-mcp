/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.dto.metrics;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link MetricTimeSeries}.
 */
class MetricTimeSeriesTest {

    MetricTimeSeriesTest() {
    }

    @Test
    void emptyInputReturnsEmptyList() {
        List<MetricTimeSeries> result = MetricTimeSeries.fromSamples(List.of());
        assertTrue(result.isEmpty());
    }

    @Test
    void singleSampleCreatesOneSeries() {
        MetricSample sample = MetricSample.of("metric_a",
            Map.of("pod", "pod-0"), 42.0, Instant.ofEpochSecond(1000));

        List<MetricTimeSeries> result = MetricTimeSeries.fromSamples(List.of(sample));

        assertEquals(1, result.size());
        assertEquals("metric_a", result.getFirst().name());
        assertEquals(Map.of("pod", "pod-0"), result.getFirst().labels());
        assertEquals(1, result.getFirst().dataPoints().size());
        assertEquals(1000L, result.getFirst().dataPoints().getFirst().get(0));
        assertEquals(42.0, result.getFirst().dataPoints().getFirst().get(1));
        assertNull(result.getFirst().compressed());
    }

    @Test
    void multipleSamplesSameNameAndLabelsGroupedIntoOneSeries() {
        List<MetricSample> samples = List.of(
            MetricSample.of("metric_a", Map.of("pod", "pod-0"), 1.0, Instant.ofEpochSecond(100)),
            MetricSample.of("metric_a", Map.of("pod", "pod-0"), 2.0, Instant.ofEpochSecond(200)),
            MetricSample.of("metric_a", Map.of("pod", "pod-0"), 3.0, Instant.ofEpochSecond(300))
        );

        List<MetricTimeSeries> result = MetricTimeSeries.fromSamples(samples);

        assertEquals(1, result.size());
        // All values are different, so no compression occurs
        assertEquals(3, result.getFirst().dataPoints().size());
        assertEquals(List.of(100L, 1.0), result.getFirst().dataPoints().get(0));
        assertEquals(List.of(200L, 2.0), result.getFirst().dataPoints().get(1));
        assertEquals(List.of(300L, 3.0), result.getFirst().dataPoints().get(2));
        assertNull(result.getFirst().compressed());
    }

    @Test
    void differentNamesCreateDistinctSeries() {
        List<MetricSample> samples = List.of(
            MetricSample.of("metric_a", Map.of("pod", "pod-0"), 1.0, Instant.ofEpochSecond(100)),
            MetricSample.of("metric_b", Map.of("pod", "pod-0"), 2.0, Instant.ofEpochSecond(100))
        );

        List<MetricTimeSeries> result = MetricTimeSeries.fromSamples(samples);

        assertEquals(2, result.size());
        assertEquals("metric_a", result.get(0).name());
        assertEquals("metric_b", result.get(1).name());
    }

    @Test
    void differentLabelsCreateDistinctSeries() {
        List<MetricSample> samples = List.of(
            MetricSample.of("metric_a", Map.of("pod", "pod-0"), 1.0, Instant.ofEpochSecond(100)),
            MetricSample.of("metric_a", Map.of("pod", "pod-1"), 2.0, Instant.ofEpochSecond(100))
        );

        List<MetricTimeSeries> result = MetricTimeSeries.fromSamples(samples);

        assertEquals(2, result.size());
        assertEquals(Map.of("pod", "pod-0"), result.get(0).labels());
        assertEquals(Map.of("pod", "pod-1"), result.get(1).labels());
    }

    @Test
    void samplesWithoutTimestampsUseZeroEpoch() {
        MetricSample sample = MetricSample.of("metric_a", Map.of(), 5.0, null);

        List<MetricTimeSeries> result = MetricTimeSeries.fromSamples(List.of(sample));

        assertEquals(1, result.size());
        assertEquals(0L, result.getFirst().dataPoints().getFirst().get(0));
        assertEquals(5.0, result.getFirst().dataPoints().getFirst().get(1));
    }

    @Test
    void orderingIsPreserved() {
        List<MetricSample> samples = List.of(
            MetricSample.of("z_metric", Map.of(), 1.0, Instant.ofEpochSecond(100)),
            MetricSample.of("a_metric", Map.of(), 2.0, Instant.ofEpochSecond(100)),
            MetricSample.of("m_metric", Map.of(), 3.0, Instant.ofEpochSecond(100))
        );

        List<MetricTimeSeries> result = MetricTimeSeries.fromSamples(samples);

        assertEquals(3, result.size());
        assertEquals("z_metric", result.get(0).name());
        assertEquals("a_metric", result.get(1).name());
        assertEquals("m_metric", result.get(2).name());
    }

    @Test
    void constantValuesAreCompressed() {
        List<MetricSample> samples = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            samples.add(MetricSample.of("metric_a", Map.of("pod", "pod-0"),
                0.0, Instant.ofEpochSecond(100 + i * 60)));
        }

        List<MetricTimeSeries> result = MetricTimeSeries.fromSamples(samples);

        assertEquals(1, result.size());
        MetricTimeSeries series = result.getFirst();
        assertEquals(2, series.dataPoints().size());
        assertEquals(Boolean.TRUE, series.compressed());
        assertEquals(List.of(100L, 0.0), series.dataPoints().get(0));
        assertEquals(List.of(6040L, 0.0), series.dataPoints().get(1));
    }

    @Test
    void summaryIsPopulated() {
        List<MetricSample> samples = List.of(
            MetricSample.of("metric_a", Map.of(), 10.0, Instant.ofEpochSecond(100)),
            MetricSample.of("metric_a", Map.of(), 20.0, Instant.ofEpochSecond(200)),
            MetricSample.of("metric_a", Map.of(), 30.0, Instant.ofEpochSecond(300))
        );

        List<MetricTimeSeries> result = MetricTimeSeries.fromSamples(samples);

        assertEquals(1, result.size());
        TimeSeriesSummary summary = result.getFirst().summary();
        assertNotNull(summary);
        assertEquals(10.0, summary.min());
        assertEquals(30.0, summary.max());
        assertEquals(20.0, summary.avg(), 1e-9);
        assertEquals(30.0, summary.latest());
        assertEquals(20.0, summary.delta());
        assertEquals(3, summary.originalDataPointCount());
    }

    @Test
    void compressedFlagNullWhenNoCompression() {
        List<MetricSample> samples = List.of(
            MetricSample.of("metric_a", Map.of(), 1.0, Instant.ofEpochSecond(100)),
            MetricSample.of("metric_a", Map.of(), 2.0, Instant.ofEpochSecond(200)),
            MetricSample.of("metric_a", Map.of(), 3.0, Instant.ofEpochSecond(300))
        );

        List<MetricTimeSeries> result = MetricTimeSeries.fromSamples(samples);

        assertNull(result.getFirst().compressed());
    }
}
