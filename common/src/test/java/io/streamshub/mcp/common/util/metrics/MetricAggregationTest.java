/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.util.metrics;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for {@link MetricAggregation}.
 */
class MetricAggregationTest {

    MetricAggregationTest() {
        // default constructor for checkstyle
    }

    @Test
    void unknownMetricDefaultsToAvg() {
        assertEquals(MetricAggregation.AVG, MetricAggregation.forMetric("something_unlisted"));
    }

    @Test
    void gaugeEntriesResolveExactly() {
        assertEquals(MetricAggregation.SUM,
            MetricAggregation.forMetric("kafka_controller_kafkacontroller_activecontrollercount"));
        assertEquals(MetricAggregation.MAX,
            MetricAggregation.forMetric("kafka_server_replicafetchermanager_maxlag"));
    }

    /**
     * The table is written in catalog spelling but consulted with the name in the response, and
     * the Prometheus provider renames rate-converted counters on the way out. Without the
     * fallback every counter entry in the table would be unreachable.
     */
    @Test
    void rateConvertedCounterResolvesThroughPreRenameName() {
        assertEquals(MetricAggregation.SUM,
            MetricAggregation.forMetric("kafka_server_brokertopicmetrics_bytesin_rate_per_second"));
    }

    /**
     * Throughput metrics are aliased on SMR as well as rate-converted, so both renames stack.
     */
    @Test
    void smrSpelledCounterResolvesThroughPreRenameName() {
        assertEquals(MetricAggregation.SUM,
            MetricAggregation.forMetric(
                "kafka_server_brokertopicmetrics_bytesinpersec_rate_per_second"));
    }

    @Test
    void rateSuffixOnAnUnlistedMetricStillDefaultsToAvg() {
        assertEquals(MetricAggregation.AVG,
            MetricAggregation.forMetric("kafka_something_unlisted_rate_per_second"));
    }

    @Test
    void reduceAppliesTheSelectedFunction() {
        List<Double> values = List.of(1.0, 2.0, 6.0);
        assertEquals(3.0, MetricAggregation.AVG.reduce(values));
        assertEquals(9.0, MetricAggregation.SUM.reduce(values));
        assertEquals(6.0, MetricAggregation.MAX.reduce(values));
    }
}
