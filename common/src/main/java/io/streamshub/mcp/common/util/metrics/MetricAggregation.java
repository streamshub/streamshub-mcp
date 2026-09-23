/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.util.metrics;

import java.util.List;
import java.util.Map;
import java.util.stream.DoubleStream;

/**
 * How values from several source series are combined into one when samples are
 * aggregated across a stripped dimension (pod, topic, partition).
 *
 * <p>Averaging is the right default for rates and per-broker levels, but it is wrong
 * for cluster-wide counts and for metrics that are themselves a maximum. Averaging
 * {@code activecontrollercount} across three controllers yields 0.33 for a perfectly
 * healthy cluster, contradicting the "should be exactly 1" guidance shipped in the
 * interpretation text; averaging {@code maxlag} across brokers understates the worst
 * lag, which is the only value the documented thresholds are about.</p>
 *
 * <p>Only metrics where averaging changes the answer a client would act on are listed
 * here. Metrics whose interpretation is explicitly per-broker — {@code leadercount},
 * {@code partitioncount}, both documented as "should be roughly equal across brokers" —
 * stay on {@link #AVG} on purpose.</p>
 */
public enum MetricAggregation {

    /** Mean across source series. The default. */
    AVG,

    /** Total across source series — for counts that are cluster-wide facts. */
    SUM,

    /** Largest across source series — for metrics that are already a maximum. */
    MAX;

    private static final Map<String, MetricAggregation> BY_METRIC = Map.of(
        // "Should be exactly 1" — the average across N controllers can never be 1.
        "kafka_controller_kafkacontroller_activecontrollercount", SUM,
        // Only the active controller reports a real value; standbys report 0.
        "kafka_controller_kafkacontroller_offlinepartitionscount", MAX,
        // Cluster-wide partition counts: "3 under-replicated" must not read as 0.5.
        "kafka_server_replicamanager_underreplicatedpartitions", SUM,
        "kafka_server_replicamanager_offlinereplicacount", SUM,
        // Already a maximum; averaging maxima understates the worst case.
        "kafka_server_replicafetchermanager_maxlag", MAX,
        // Per-partition 0/1 health flags: the count of bad partitions, not their mean.
        "kafka_cluster_partition_underminisr", SUM,
        "kafka_cluster_partition_atminisr", SUM
    );

    /**
     * Returns the aggregation function for a metric, defaulting to {@link #AVG}.
     *
     * @param metricName the Prometheus metric name
     * @return the function to combine source series for this metric
     */
    public static MetricAggregation forMetric(final String metricName) {
        return BY_METRIC.getOrDefault(metricName, AVG);
    }

    /**
     * Combines the values recorded at one timestamp by several source series.
     *
     * @param values the values to combine; must not be empty
     * @return the combined value
     */
    public double reduce(final List<Double> values) {
        DoubleStream stream = values.stream().mapToDouble(Double::doubleValue);
        return switch (this) {
            case AVG -> stream.average().orElse(0.0);
            case SUM -> stream.sum();
            case MAX -> stream.max().orElse(0.0);
        };
    }
}
