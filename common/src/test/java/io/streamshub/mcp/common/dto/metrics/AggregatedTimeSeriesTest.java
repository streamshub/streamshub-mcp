/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.dto.metrics;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link AggregatedTimeSeries}.
 */
class AggregatedTimeSeriesTest {

    AggregatedTimeSeriesTest() {
    }

    @Test
    void emptyInputReturnsEmptyList() {
        assertTrue(AggregatedTimeSeries.fromSamples(List.of(), AggregationLevel.BROKER).isEmpty());
    }

    @Test
    void nullInputReturnsEmptyList() {
        assertTrue(AggregatedTimeSeries.fromSamples(null, AggregationLevel.BROKER).isEmpty());
    }

    @Test
    void partitionLevelKeepsAllLabels() {
        List<MetricSample> samples = List.of(
            MetricSample.of("m", Map.of("pod", "pod-0", "topic", "t1", "partition", "0"), 10.0),
            MetricSample.of("m", Map.of("pod", "pod-0", "topic", "t1", "partition", "1"), 20.0)
        );

        List<AggregatedTimeSeries> result = AggregatedTimeSeries.fromSamples(samples, AggregationLevel.PARTITION);

        assertEquals(2, result.size());
        assertEquals(1, result.get(0).sourceCount());
        assertEquals(1, result.get(1).sourceCount());
        assertTrue(result.get(0).labels().containsKey("partition"));
        assertTrue(result.get(0).labels().containsKey("topic"));
        assertTrue(result.get(0).labels().containsKey("pod"));
    }

    @Test
    void topicLevelAveragesAcrossPartitions() {
        List<MetricSample> samples = List.of(
            MetricSample.of("m", Map.of("pod", "pod-0", "topic", "t1", "partition", "0"), 10.0),
            MetricSample.of("m", Map.of("pod", "pod-0", "topic", "t1", "partition", "1"), 20.0)
        );

        List<AggregatedTimeSeries> result = AggregatedTimeSeries.fromSamples(samples, AggregationLevel.TOPIC);

        assertEquals(1, result.size());
        AggregatedTimeSeries series = result.getFirst();
        assertEquals("m", series.name());
        assertEquals(2, series.sourceCount());
        assertEquals(15.0, series.summary().avg(), 0.001);
        assertTrue(series.labels().containsKey("topic"));
        assertTrue(series.labels().containsKey("pod"));
        assertTrue(!series.labels().containsKey("partition"));
    }

    @Test
    void brokerLevelAveragesAcrossTopicsAndPartitions() {
        List<MetricSample> samples = List.of(
            MetricSample.of("m", Map.of("pod", "pod-0", "topic", "t1", "partition", "0"), 10.0),
            MetricSample.of("m", Map.of("pod", "pod-0", "topic", "t1", "partition", "1"), 20.0),
            MetricSample.of("m", Map.of("pod", "pod-0", "topic", "t2", "partition", "0"), 30.0)
        );

        List<AggregatedTimeSeries> result = AggregatedTimeSeries.fromSamples(samples, AggregationLevel.BROKER);

        assertEquals(1, result.size());
        AggregatedTimeSeries series = result.getFirst();
        assertEquals(3, series.sourceCount());
        assertEquals(20.0, series.summary().avg(), 0.001);
        assertTrue(series.labels().containsKey("pod"));
        assertTrue(!series.labels().containsKey("topic"));
        assertTrue(!series.labels().containsKey("partition"));
    }

    @Test
    void brokerLevelKeepsPerBrokerDistinction() {
        List<MetricSample> samples = List.of(
            MetricSample.of("m", Map.of("pod", "pod-0", "topic", "t1"), 10.0),
            MetricSample.of("m", Map.of("pod", "pod-1", "topic", "t1"), 30.0)
        );

        List<AggregatedTimeSeries> result = AggregatedTimeSeries.fromSamples(samples, AggregationLevel.BROKER);

        assertEquals(2, result.size());
        assertEquals("pod-0", result.get(0).labels().get("pod"));
        assertEquals("pod-1", result.get(1).labels().get("pod"));
        assertEquals(10.0, result.get(0).summary().latest(), 0.001);
        assertEquals(30.0, result.get(1).summary().latest(), 0.001);
    }

    @Test
    void clusterLevelAveragesAcrossAllDimensions() {
        List<MetricSample> samples = List.of(
            MetricSample.of("m", Map.of("pod", "pod-0", "topic", "t1", "partition", "0"), 10.0),
            MetricSample.of("m", Map.of("pod", "pod-1", "topic", "t1", "partition", "0"), 20.0),
            MetricSample.of("m", Map.of("pod", "pod-0", "topic", "t2", "partition", "0"), 30.0)
        );

        List<AggregatedTimeSeries> result = AggregatedTimeSeries.fromSamples(samples, AggregationLevel.CLUSTER);

        assertEquals(1, result.size());
        AggregatedTimeSeries series = result.getFirst();
        assertEquals(3, series.sourceCount());
        assertEquals(20.0, series.summary().avg(), 0.001);
        assertTrue(!series.labels().containsKey("pod"));
        assertTrue(!series.labels().containsKey("topic"));
        assertTrue(!series.labels().containsKey("partition"));
    }

    @Test
    void intrinsicLabelsPreservedAtAllLevels() {
        Map<String, String> labels = Map.of(
            "pod", "pod-0", "topic", "t1", "partition", "0",
            "type", "producer-metrics", "quantile", "0.99"
        );
        List<MetricSample> samples = List.of(MetricSample.of("m", labels, 42.0));

        for (AggregationLevel level : AggregationLevel.values()) {
            List<AggregatedTimeSeries> result = AggregatedTimeSeries.fromSamples(samples, level);
            assertEquals(1, result.size());
            assertEquals("producer-metrics", result.getFirst().labels().get("type"));
            assertEquals("0.99", result.getFirst().labels().get("quantile"));
        }
    }

    @Test
    void nullTimestampsUseEpochZero() {
        List<MetricSample> samples = List.of(
            MetricSample.of("m", Map.of("pod", "pod-0"), 5.0),
            MetricSample.of("m", Map.of("pod", "pod-1"), 15.0)
        );

        List<AggregatedTimeSeries> result = AggregatedTimeSeries.fromSamples(samples, AggregationLevel.CLUSTER);

        assertEquals(1, result.size());
        assertEquals(0L, result.getFirst().dataPoints().getFirst().getFirst());
        assertEquals(10.0, result.getFirst().summary().avg(), 0.001);
    }

    @Test
    void rangeDataWithMultipleTimestampsAveragedPerTimestamp() {
        Instant t1 = Instant.ofEpochSecond(1000);
        Instant t2 = Instant.ofEpochSecond(1060);
        List<MetricSample> samples = List.of(
            MetricSample.of("m", Map.of("pod", "pod-0"), 10.0, t1),
            MetricSample.of("m", Map.of("pod", "pod-1"), 20.0, t1),
            MetricSample.of("m", Map.of("pod", "pod-0"), 30.0, t2),
            MetricSample.of("m", Map.of("pod", "pod-1"), 40.0, t2)
        );

        List<AggregatedTimeSeries> result = AggregatedTimeSeries.fromSamples(samples, AggregationLevel.CLUSTER);

        assertEquals(1, result.size());
        AggregatedTimeSeries series = result.getFirst();
        assertEquals(2, series.dataPoints().size());
        assertEquals(15.0, ((Number) series.dataPoints().get(0).get(1)).doubleValue(), 0.001);
        assertEquals(35.0, ((Number) series.dataPoints().get(1).get(1)).doubleValue(), 0.001);
        assertEquals(2, series.sourceCount());
    }

    @Test
    void summaryComputedFromAveragedData() {
        List<MetricSample> samples = List.of(
            MetricSample.of("m", Map.of("pod", "pod-0"), 100.0),
            MetricSample.of("m", Map.of("pod", "pod-1"), 200.0)
        );

        List<AggregatedTimeSeries> result = AggregatedTimeSeries.fromSamples(samples, AggregationLevel.CLUSTER);

        assertNotNull(result.getFirst().summary());
        assertEquals(150.0, result.getFirst().summary().latest(), 0.001);
        assertEquals(150.0, result.getFirst().summary().min(), 0.001);
        assertEquals(150.0, result.getFirst().summary().max(), 0.001);
    }

    @Test
    void differentMetricNamesProduceSeparateSeries() {
        List<MetricSample> samples = List.of(
            MetricSample.of("metric_a", Map.of("pod", "pod-0"), 1.0),
            MetricSample.of("metric_b", Map.of("pod", "pod-0"), 2.0)
        );

        List<AggregatedTimeSeries> result = AggregatedTimeSeries.fromSamples(samples, AggregationLevel.CLUSTER);

        assertEquals(2, result.size());
        assertEquals("metric_a", result.get(0).name());
        assertEquals("metric_b", result.get(1).name());
    }
}
