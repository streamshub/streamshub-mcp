/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.util.metrics;

import io.streamshub.mcp.common.dto.metrics.AggregatedTimeSeries;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link CommonLabelExtractor}.
 */
class CommonLabelExtractorTest {

    CommonLabelExtractorTest() {
    }

    @Test
    void extractFromNullReturnsEmpty() {
        CommonLabelExtractor.Result result = CommonLabelExtractor.extract(null);
        assertTrue(result.commonLabels().isEmpty());
        assertNotNull(result.strippedSeries());
        assertTrue(result.strippedSeries().isEmpty());
    }

    @Test
    void extractFromEmptyListReturnsEmpty() {
        CommonLabelExtractor.Result result = CommonLabelExtractor.extract(List.of());
        assertTrue(result.commonLabels().isEmpty());
        assertTrue(result.strippedSeries().isEmpty());
    }

    @Test
    void extractFromSingleSeriesMovesAllLabelsToCommon() {
        AggregatedTimeSeries ts = new AggregatedTimeSeries(
            "metric_a", Map.of("namespace", "kafka", "pod", "pod-0"),
            List.of(), null, 1, null);

        CommonLabelExtractor.Result result = CommonLabelExtractor.extract(List.of(ts));

        assertEquals(Map.of("namespace", "kafka", "pod", "pod-0"), result.commonLabels());
        assertEquals(1, result.strippedSeries().size());
        assertTrue(result.strippedSeries().getFirst().labels().isEmpty());
    }

    @Test
    void extractFactorsOutSharedLabels() {
        AggregatedTimeSeries ts1 = new AggregatedTimeSeries(
            "metric_a", Map.of("namespace", "kafka", "pod", "pod-0"),
            List.of(), null, 1, null);
        AggregatedTimeSeries ts2 = new AggregatedTimeSeries(
            "metric_a", Map.of("namespace", "kafka", "pod", "pod-1"),
            List.of(), null, 1, null);

        CommonLabelExtractor.Result result = CommonLabelExtractor.extract(List.of(ts1, ts2));

        assertEquals(Map.of("namespace", "kafka"), result.commonLabels());
        assertEquals(2, result.strippedSeries().size());
        assertEquals(Map.of("pod", "pod-0"), result.strippedSeries().get(0).labels());
        assertEquals(Map.of("pod", "pod-1"), result.strippedSeries().get(1).labels());
    }

    @Test
    void extractNoCommonLabelsReturnsOriginal() {
        AggregatedTimeSeries ts1 = new AggregatedTimeSeries(
            "metric_a", Map.of("pod", "pod-0"),
            List.of(), null, 1, null);
        AggregatedTimeSeries ts2 = new AggregatedTimeSeries(
            "metric_a", Map.of("pod", "pod-1"),
            List.of(), null, 1, null);

        CommonLabelExtractor.Result result = CommonLabelExtractor.extract(List.of(ts1, ts2));

        assertTrue(result.commonLabels().isEmpty());
        assertEquals(2, result.strippedSeries().size());
        assertEquals(Map.of("pod", "pod-0"), result.strippedSeries().get(0).labels());
        assertEquals(Map.of("pod", "pod-1"), result.strippedSeries().get(1).labels());
    }

    @Test
    void extractAllLabelsCommonAcrossMultipleSeries() {
        AggregatedTimeSeries ts1 = new AggregatedTimeSeries(
            "metric_a", Map.of("namespace", "kafka", "cluster", "my-cluster"),
            List.of(), null, 1, null);
        AggregatedTimeSeries ts2 = new AggregatedTimeSeries(
            "metric_b", Map.of("namespace", "kafka", "cluster", "my-cluster"),
            List.of(), null, 1, null);

        CommonLabelExtractor.Result result = CommonLabelExtractor.extract(List.of(ts1, ts2));

        assertEquals(Map.of("namespace", "kafka", "cluster", "my-cluster"), result.commonLabels());
        assertEquals(2, result.strippedSeries().size());
        assertTrue(result.strippedSeries().get(0).labels().isEmpty());
        assertTrue(result.strippedSeries().get(1).labels().isEmpty());
    }

    @Test
    void extractHandlesSeriesWithNullLabels() {
        AggregatedTimeSeries ts1 = new AggregatedTimeSeries(
            "metric_a", Map.of("namespace", "kafka"),
            List.of(), null, 1, null);
        AggregatedTimeSeries ts2 = new AggregatedTimeSeries(
            "metric_b", null,
            List.of(), null, 1, null);

        CommonLabelExtractor.Result result = CommonLabelExtractor.extract(List.of(ts1, ts2));

        assertTrue(result.commonLabels().isEmpty());
    }

    @Test
    void extractPreservesNonLabelFields() {
        List<List<Object>> dataPoints = List.of(List.of(1000L, 42.0));
        AggregatedTimeSeries ts = new AggregatedTimeSeries(
            "metric_a", Map.of("namespace", "kafka"),
            dataPoints, null, 3, Boolean.TRUE);

        CommonLabelExtractor.Result result = CommonLabelExtractor.extract(List.of(ts));

        AggregatedTimeSeries stripped = result.strippedSeries().getFirst();
        assertEquals("metric_a", stripped.name());
        assertEquals(dataPoints, stripped.dataPoints());
        assertEquals(3, stripped.sourceCount());
        assertEquals(Boolean.TRUE, stripped.compressed());
    }
}
