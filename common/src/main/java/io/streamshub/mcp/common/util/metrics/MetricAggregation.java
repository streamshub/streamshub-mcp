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
 * stay on {@link #AVG} on purpose, as do {@code *_avg} metrics.</p>
 *
 * <p>Broker-health gauges whose documented thresholds are per broker use the <em>worst</em>
 * broker instead of the mean: the lowest value for idle ratios ({@link #MIN}), the highest
 * for request times and for JVM/process resource usage ({@link #MAX}). With idle ratios of
 * 0.90, 0.88 and 0.04 the mean is 0.61 and looks healthy while one broker is saturated.</p>
 *
 * <p>The table covers every component whose samples can actually collapse. Strimzi operator
 * metrics carry {@code kind} and {@code namespace}, and most Bridge metrics carry
 * {@code clientId}, and those labels survive every aggregation level — so their series never
 * share a group and the function is moot. Bridge entries exist for the {@code replicas > 1}
 * case, where the same {@code clientId} appears on every pod.</p>
 */
public enum MetricAggregation {

    /** Mean across source series. The default. */
    AVG,

    /** Total across source series — for counts that are cluster-wide facts. */
    SUM,

    /** Largest across source series — for metrics that are already a maximum, or where the highest value is the worst. */
    MAX,

    /** Smallest across source series — for metrics where the lowest value is the worst, such as idle ratios. */
    MIN;

    private static final Map<String, MetricAggregation> BY_METRIC = Map.ofEntries(
        // --- Kafka broker: replication ---
        // "Should be exactly 1" — the average across N controllers can never be 1.
        Map.entry("kafka_controller_kafkacontroller_activecontrollercount", SUM),
        // Only the active controller reports a real value; standbys report 0.
        Map.entry("kafka_controller_kafkacontroller_offlinepartitionscount", MAX),
        // Cluster-wide partition counts: "3 under-replicated" must not read as 0.5.
        Map.entry("kafka_server_replicamanager_underreplicatedpartitions", SUM),
        Map.entry("kafka_server_replicamanager_offlinereplicacount", SUM),
        // Already a maximum; averaging maxima understates the worst case.
        Map.entry("kafka_server_replicafetchermanager_maxlag", MAX),
        // Per-partition 0/1 health flags: the count of bad partitions, not their mean.
        Map.entry("kafka_cluster_partition_underminisr", SUM),
        Map.entry("kafka_cluster_partition_atminisr", SUM),

        // --- Kafka broker: performance ---
        // Idle ratios: the thresholds (<0.3 overloaded, <0.1 critical) are per broker, so the
        // saturated broker is the lowest one. A mean across brokers hides it.
        Map.entry("kafka_server_kafkarequesthandlerpool_requesthandleravgidle_percent", MIN),
        Map.entry("kafka_network_socketserver_networkprocessoravgidle_percent", MIN),
        Map.entry("kafka_network_socketserver_networkprocessoravgidlepercent", MIN),
        // Request times: the thresholds are per broker and higher is worse.
        Map.entry("kafka_network_requestmetrics_totaltimems", MAX),
        Map.entry("kafka_network_requestmetrics_requestqueuetimems", MAX),
        Map.entry("kafka_network_requestmetrics_responsequeuetimems", MAX),

        // --- JVM / process resources (Kafka, Connect, Bridge, operator) ---
        // Heap, descriptors, threads, GC and CPU are judged per pod; a leaking or GC-bound pod
        // must not be diluted by its healthy peers. Counters are matched through the
        // rate-rename fallback in forMetric, so they are listed here in catalog spelling.
        Map.entry("jvm_memory_used_bytes", MAX),
        Map.entry("jvm_memory_max_bytes", MAX),
        Map.entry("jvm_gc_collection_seconds_count", MAX),
        Map.entry("jvm_gc_collection_seconds_sum", MAX),
        Map.entry("process_cpu_seconds_total", MAX),
        Map.entry("process_open_fds", MAX),
        Map.entry("jvm_threads_current", MAX),
        // Micrometer spelling, used by the Bridge and the Strimzi operators. The Bridge runs
        // several replicas whose series collapse, so the busiest pod must not be averaged away.
        Map.entry("jvm_gc_pause_seconds_count", MAX),
        Map.entry("jvm_gc_pause_seconds_sum", MAX),
        Map.entry("process_cpu_usage", MAX),
        Map.entry("jvm_threads_live_threads", MAX),

        // --- Kafka broker: throughput ---
        // Cluster ingress/egress is the total across brokers, not one broker's share. Listed
        // under both spellings because every one of these is aliased on the Strimzi Metrics
        // Reporter; the shared rate-suffix fallback in forMetric covers the other rename.
        Map.entry("kafka_server_brokertopicmetrics_messagesin_total", SUM),
        Map.entry("kafka_server_brokertopicmetrics_messagesinpersec_total", SUM),
        Map.entry("kafka_server_brokertopicmetrics_bytesin_total", SUM),
        Map.entry("kafka_server_brokertopicmetrics_bytesinpersec_total", SUM),
        Map.entry("kafka_server_brokertopicmetrics_bytesout_total", SUM),
        Map.entry("kafka_server_brokertopicmetrics_bytesoutpersec_total", SUM),
        Map.entry("kafka_server_brokertopicmetrics_totalproducerequests_total", SUM),
        Map.entry("kafka_server_brokertopicmetrics_totalproducerequestspersec_total", SUM),
        Map.entry("kafka_server_brokertopicmetrics_totalfetchrequests_total", SUM),
        Map.entry("kafka_server_brokertopicmetrics_totalfetchrequestspersec_total", SUM),
        Map.entry("kafka_server_brokertopicmetrics_failedproducerequests_total", SUM),
        Map.entry("kafka_server_brokertopicmetrics_failedproducerequestspersec_total", SUM),
        Map.entry("kafka_server_brokertopicmetrics_failedfetchrequests_total", SUM),
        Map.entry("kafka_server_brokertopicmetrics_failedfetchrequestspersec_total", SUM),
        // Spelled the same on both backends.
        Map.entry("kafka_server_socket_server_metrics_connection_count", SUM),

        // --- Kafka Connect: worker ---
        // Worker-level MBeans counting what runs on *this* worker. The category is pinned to
        // CLUSTER, so the pod label is always stripped and a multi-worker cluster would report
        // the mean per worker where the caller reads a cluster count.
        Map.entry("kafka_connect_worker_connector_count", SUM),
        Map.entry("kafka_connect_worker_task_count", SUM),
        Map.entry("kafka_connect_worker_connector_startup_failure_total", SUM),
        Map.entry("kafka_connect_worker_connector_startup_success_total", SUM),
        Map.entry("kafka_connect_worker_task_startup_failure_total", SUM),
        Map.entry("kafka_connect_worker_task_startup_success_total", SUM),

        // --- Kafka Exporter: consumer lag ---
        // Total messages a group is behind. The category defaults to PARTITION, so this only
        // applies when a caller explicitly asks for a coarser level — where the mean lag per
        // partition is not the number anyone means by "consumer lag".
        Map.entry("kafka_consumergroup_lag", SUM),

        // --- Kafka Bridge ---
        // Bites at replicas > 1: clientId is identical on every replica, so the series do
        // collapse and additive counters would be averaged across pods.
        Map.entry("strimzi_bridge_http_server_active_connections", SUM),
        Map.entry("strimzi_bridge_http_server_active_requests", SUM),
        Map.entry("strimzi_bridge_http_server_requests_total", SUM),
        // Micrometer summary triple: count and sum are additive, max is already a maximum.
        Map.entry("strimzi_bridge_http_server_request_bytes_count", SUM),
        Map.entry("strimzi_bridge_http_server_request_bytes_sum", SUM),
        Map.entry("strimzi_bridge_http_server_request_bytes_max", MAX),
        Map.entry("strimzi_bridge_http_server_response_bytes_count", SUM),
        Map.entry("strimzi_bridge_http_server_response_bytes_sum", SUM),
        Map.entry("strimzi_bridge_http_server_response_bytes_max", MAX),
        Map.entry("strimzi_bridge_kafka_producer_record_send_total", SUM),
        Map.entry("strimzi_bridge_kafka_producer_record_send_rate", SUM),
        Map.entry("strimzi_bridge_kafka_producer_record_error_total", SUM),
        Map.entry("strimzi_bridge_kafka_producer_record_error_rate", SUM),
        Map.entry("strimzi_bridge_kafka_producer_byte_total", SUM),
        Map.entry("strimzi_bridge_kafka_consumer_records_consumed_total", SUM),
        Map.entry("strimzi_bridge_kafka_consumer_records_consumed_rate", SUM),
        Map.entry("strimzi_bridge_kafka_consumer_bytes_consumed_total", SUM),
        Map.entry("strimzi_bridge_kafka_consumer_bytes_consumed_rate", SUM),
        Map.entry("strimzi_bridge_kafka_consumer_fetch_total", SUM),
        Map.entry("strimzi_bridge_kafka_consumer_fetch_rate", SUM)
    );

    /**
     * Returns the aggregation function for a metric, defaulting to {@link #AVG}.
     *
     * <p>The table is written in catalog spelling, but this is called with the name as it
     * appears in the response — and the Prometheus provider renames rate-converted counters
     * from {@code _total} to {@code _rate_per_second} on the way out. An exact miss therefore
     * retries under the counter's pre-rename name, otherwise every counter entry above would
     * be dead.</p>
     *
     * @param metricName the Prometheus metric name as it appears in the response
     * @return the function to combine source series for this metric
     */
    public static MetricAggregation forMetric(final String metricName) {
        MetricAggregation exact = BY_METRIC.get(metricName);
        if (exact != null) {
            return exact;
        }
        String preRename = MetricNameSuffixes.toTotal(metricName);
        return preRename == null ? AVG : BY_METRIC.getOrDefault(preRename, AVG);
    }

    /**
     * Combines the values recorded at one timestamp by several source series.
     *
     * <p>For {@link #AVG} and {@link #MAX}, an empty list produces {@code 0.0} via
     * {@link java.util.OptionalDouble#orElse}. In practice this cannot occur because the
     * caller always passes at least one value, but callers should not rely on the 0.0
     * fallback for {@link #MAX} with a negative-valued series.</p>
     *
     * @param values the values to combine; must not be null
     * @return the combined value
     */
    public double reduce(final List<Double> values) {
        DoubleStream stream = values.stream().mapToDouble(Double::doubleValue);
        return switch (this) {
            case AVG -> stream.average().orElse(0.0);
            case SUM -> stream.sum();
            case MAX -> stream.max().orElse(0.0);
            case MIN -> stream.min().orElse(0.0);
        };
    }
}
