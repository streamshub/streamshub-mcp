/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.systemtest.metrics;

import java.util.List;

/**
 * Metric names the server advertises, restated here so a live cluster can confirm them.
 * <p>
 * Deliberately a copy of the {@code *MetricCategories} lists rather than an import: the
 * systemtest module asserts against the deployed server's contract, not against the same
 * constants the server was built from. If the two drift, these tests fail — which is the point.
 * That is also the difference between this and {@code known-series-jmx-exporter.txt}, which can
 * only ever prove a name is written down in a second place.
 * <p>
 * Each list is a category subset chosen to be emitted unconditionally by a freshly deployed
 * component, so a failure means a real regression rather than a workload that never ran.
 */
final class CatalogNames {

    /** {@code KafkaMetricCategories.REPLICATION}. */
    static final List<String> KAFKA_REPLICATION = List.of(
        "kafka_server_replicamanager_underreplicatedpartitions",
        "kafka_server_replicamanager_leadercount",
        "kafka_server_replicamanager_partitioncount",
        "kafka_server_replicamanager_offlinereplicacount",
        "kafka_controller_kafkacontroller_offlinepartitionscount",
        "kafka_controller_controllerstats_uncleanleaderelections_total",
        "kafka_controller_kafkacontroller_activecontrollercount",
        "kafka_server_replicafetchermanager_maxlag");

    /**
     * {@code KafkaBridgeMetricCategories.RESOURCES} — the JVM block, not the HTTP block. JVM
     * gauges exist from process start, while the HTTP counters are only registered on the first
     * request and would tie the assertion to traffic these tests do not generate.
     */
    static final List<String> BRIDGE_RESOURCES = List.of(
        "jvm_memory_used_bytes",
        "jvm_memory_max_bytes",
        "jvm_gc_pause_seconds_count",
        "jvm_gc_pause_seconds_sum",
        "process_cpu_usage",
        "jvm_threads_live_threads");

    /** {@code KafkaConnectMetricCategories.WORKER} — worker MBeans, present with zero connectors. */
    static final List<String> CONNECT_WORKER = List.of(
        "kafka_connect_worker_connector_count",
        "kafka_connect_worker_task_count",
        "kafka_connect_worker_connector_startup_failure_total",
        "kafka_connect_worker_connector_startup_success_total",
        "kafka_connect_worker_task_startup_failure_total",
        "kafka_connect_worker_task_startup_success_total");

    /**
     * {@code StrimziOperatorMetricCategories.RECONCILIATION} — emitted once anything reconciles.
     * Without {@code strimzi_reconciliations_failed_total}: the operator's counters are created
     * per label set on first increment, so on a cluster that reconciles cleanly that series has
     * never existed.
     */
    static final List<String> OPERATOR_RECONCILIATION = List.of(
        "strimzi_reconciliations_successful_total",
        "strimzi_reconciliations_total",
        "strimzi_reconciliations_duration_seconds_sum",
        "strimzi_reconciliations_duration_seconds_count",
        "strimzi_reconciliations_locked_total",
        "strimzi_reconciliations_periodical_total");

    private CatalogNames() {
    }
}
