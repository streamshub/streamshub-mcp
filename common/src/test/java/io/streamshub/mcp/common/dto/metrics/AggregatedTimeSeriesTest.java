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
import static org.junit.jupiter.api.Assertions.assertNull;
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
        assertEquals(15.0, series.summary().latest(), 0.001);
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
        assertEquals(20.0, series.summary().latest(), 0.001);
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
        assertEquals(20.0, series.summary().latest(), 0.001);
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
        assertEquals(10.0, result.getFirst().summary().latest(), 0.001);
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
        // single aggregated data point: min/max/avg are null
        assertEquals(1, result.getFirst().summary().originalDataPointCount());
    }

    /**
     * A healthy cluster has exactly one active controller. Averaging that 0/1 gauge
     * across three controllers yields 0.33, contradicting the "should be exactly 1"
     * guidance the interpretation text ships.
     */
    @Test
    void activeControllerCountIsSummedNotAveraged() {
        List<MetricSample> samples = List.of(
            MetricSample.of("kafka_controller_kafkacontroller_activecontrollercount", Map.of("pod", "c-0"), 1.0),
            MetricSample.of("kafka_controller_kafkacontroller_activecontrollercount", Map.of("pod", "c-1"), 0.0),
            MetricSample.of("kafka_controller_kafkacontroller_activecontrollercount", Map.of("pod", "c-2"), 0.0)
        );

        List<AggregatedTimeSeries> result =
            AggregatedTimeSeries.fromSamples(samples, AggregationLevel.CLUSTER);

        assertEquals(1, result.size());
        assertEquals(1.0, result.getFirst().summary().latest(), 0.001);
        assertEquals("sum", result.getFirst().aggregationFn());
    }

    /**
     * maxlag is already a maximum; averaging maxima hides the worst broker, which is
     * the only value the documented thresholds are about.
     */
    @Test
    void maxLagTakesTheWorstBrokerNotTheMean() {
        List<MetricSample> samples = List.of(
            MetricSample.of("kafka_server_replicafetchermanager_maxlag", Map.of("pod", "b-0"), 0.0),
            MetricSample.of("kafka_server_replicafetchermanager_maxlag", Map.of("pod", "b-1"), 12000.0)
        );

        List<AggregatedTimeSeries> result =
            AggregatedTimeSeries.fromSamples(samples, AggregationLevel.CLUSTER);

        assertEquals(12000.0, result.getFirst().summary().latest(), 0.001);
        assertEquals("max", result.getFirst().aggregationFn());
    }

    /**
     * leadercount is documented per-broker ("should be roughly equal across brokers"),
     * so it must keep averaging — the fix must not turn every metric into a sum.
     */
    @Test
    void perBrokerMetricsStillAverageAndOmitTheFunction() {
        List<MetricSample> samples = List.of(
            MetricSample.of("kafka_server_replicamanager_leadercount", Map.of("pod", "b-0"), 10.0),
            MetricSample.of("kafka_server_replicamanager_leadercount", Map.of("pod", "b-1"), 20.0)
        );

        List<AggregatedTimeSeries> result =
            AggregatedTimeSeries.fromSamples(samples, AggregationLevel.CLUSTER);

        assertEquals(15.0, result.getFirst().summary().latest(), 0.001);
        assertNull(result.getFirst().aggregationFn());
    }

    // ---------------------------------------------------------------
    // Roll-up preference — a total must not be averaged with its own parts
    // ---------------------------------------------------------------

    /**
     * Kafka publishes BrokerTopicMetrics per topic <em>and</em> as a broker-wide roll-up with no
     * {@code topic} label. Stripping {@code topic} gives them one group key, so without the
     * roll-up preference the result is the mean of a total and its own constituents.
     */
    @Test
    void rollUpWinsOverPerTopicPartsWhenTopicIsStripped() {
        List<MetricSample> samples = List.of(
            MetricSample.of("kafka_server_brokertopicmetrics_bytesin_rate_per_second",
                Map.of("pod", "b-0"), 700.0),
            MetricSample.of("kafka_server_brokertopicmetrics_bytesin_rate_per_second",
                Map.of("pod", "b-0", "topic", "t1"), 500.0),
            MetricSample.of("kafka_server_brokertopicmetrics_bytesin_rate_per_second",
                Map.of("pod", "b-0", "topic", "t2"), 200.0)
        );

        List<AggregatedTimeSeries> result =
            AggregatedTimeSeries.fromSamples(samples, AggregationLevel.BROKER);

        assertEquals(1, result.size());
        assertEquals(700.0, result.getFirst().summary().latest(), 0.001,
            "only the broker roll-up may feed the value, not the mean of 700/500/200");
        assertEquals(1, result.getFirst().sourceCount(),
            "source_count must report the one real source series, not all three");
    }

    /**
     * The throughput payoff: roll-ups are picked per broker, then summed into a cluster total.
     */
    @Test
    void rollUpsAreSummedAcrossBrokersAtClusterLevel() {
        List<MetricSample> samples = List.of(
            MetricSample.of("kafka_server_brokertopicmetrics_bytesin_rate_per_second",
                Map.of("pod", "b-0"), 700.0),
            MetricSample.of("kafka_server_brokertopicmetrics_bytesin_rate_per_second",
                Map.of("pod", "b-0", "topic", "t1"), 700.0),
            MetricSample.of("kafka_server_brokertopicmetrics_bytesin_rate_per_second",
                Map.of("pod", "b-1"), 300.0),
            MetricSample.of("kafka_server_brokertopicmetrics_bytesin_rate_per_second",
                Map.of("pod", "b-1", "topic", "t1"), 300.0)
        );

        List<AggregatedTimeSeries> result =
            AggregatedTimeSeries.fromSamples(samples, AggregationLevel.CLUSTER);

        assertEquals(1, result.size());
        assertEquals(1000.0, result.getFirst().summary().latest(), 0.001);
        assertEquals("sum", result.getFirst().aggregationFn());
    }

    /**
     * Every scraped sample carries a pod label, so a group never splits on it and the roll-up
     * rule must leave ordinary cross-pod aggregation completely alone.
     */
    @Test
    void groupWhereNoSampleCarriesTheStrippedLabelIsUntouched() {
        List<MetricSample> samples = List.of(
            MetricSample.of("kafka_server_replicamanager_leadercount", Map.of("pod", "b-0"), 10.0),
            MetricSample.of("kafka_server_replicamanager_leadercount", Map.of("pod", "b-1"), 20.0),
            MetricSample.of("kafka_server_replicamanager_leadercount", Map.of("pod", "b-2"), 30.0)
        );

        List<AggregatedTimeSeries> result =
            AggregatedTimeSeries.fromSamples(samples, AggregationLevel.CLUSTER);

        assertEquals(20.0, result.getFirst().summary().latest(), 0.001);
        assertEquals(3, result.getFirst().sourceCount(), "all three pods must still feed the mean");
    }

    /**
     * A metric with no roll-up sibling — the ordinary case — keeps averaging all its parts.
     */
    @Test
    void groupWhereEverySampleCarriesTheStrippedLabelIsUntouched() {
        List<MetricSample> samples = List.of(
            MetricSample.of("m", Map.of("pod", "b-0", "topic", "t1"), 10.0),
            MetricSample.of("m", Map.of("pod", "b-0", "topic", "t2"), 20.0)
        );

        List<AggregatedTimeSeries> result =
            AggregatedTimeSeries.fromSamples(samples, AggregationLevel.BROKER);

        assertEquals(15.0, result.getFirst().summary().latest(), 0.001);
        assertEquals(2, result.getFirst().sourceCount());
    }

    /**
     * At TOPIC level the {@code topic} label survives, so the roll-up lands in its own group and
     * is returned as an extra row rather than replacing the per-topic breakdown.
     */
    @Test
    void topicLevelKeepsPartsAndReturnsTheRollUpAsItsOwnSeries() {
        List<MetricSample> samples = List.of(
            MetricSample.of("kafka_server_brokertopicmetrics_bytesin_rate_per_second",
                Map.of("pod", "b-0"), 700.0),
            MetricSample.of("kafka_server_brokertopicmetrics_bytesin_rate_per_second",
                Map.of("pod", "b-0", "topic", "t1"), 500.0),
            MetricSample.of("kafka_server_brokertopicmetrics_bytesin_rate_per_second",
                Map.of("pod", "b-0", "topic", "t2"), 200.0)
        );

        List<AggregatedTimeSeries> result =
            AggregatedTimeSeries.fromSamples(samples, AggregationLevel.TOPIC);

        assertEquals(3, result.size());
        assertEquals(1, result.stream().filter(s -> !s.labels().containsKey("topic")).count(),
            "the roll-up must survive as exactly one un-topiced row");
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
