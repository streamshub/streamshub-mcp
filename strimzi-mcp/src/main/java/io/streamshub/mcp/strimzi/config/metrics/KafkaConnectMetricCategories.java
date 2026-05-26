/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.config.metrics;

import io.streamshub.mcp.common.dto.metrics.AggregationLevel;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
/**
 * Curated metric name categories for KafkaConnect metrics.
 * Maps human-friendly category names to lists of Prometheus metric names,
 * and provides interpretation guides for each category.
 */
public final class KafkaConnectMetricCategories {

    /**
     * Connect worker category (connector count, task count, startup metrics).
     */
    public static final String WORKER = "worker";

    /**
     * Connector task category (batch size, commit latency, running ratio).
     */
    public static final String CONNECTOR = "connector";

    /**
     * Source connector task category (records polled, records written, poll latency).
     */
    public static final String SOURCE = "source";

    /**
     * Sink connector task category (records read, records sent, put latency).
     */
    public static final String SINK = "sink";

    /**
     * JVM and system resource category (heap, GC, CPU, threads).
     */
    public static final String RESOURCES = "resources";

    private static final Map<String, List<String>> CATEGORIES = Map.of(
        WORKER, List.of(
            "kafka_connect_worker_connector_count",
            "kafka_connect_worker_task_count",
            "kafka_connect_worker_connector_startup_failure_total",
            "kafka_connect_worker_connector_startup_success_total",
            "kafka_connect_worker_task_startup_failure_total",
            "kafka_connect_worker_task_startup_success_total"
        ),
        CONNECTOR, List.of(
            "kafka_connect_connector_task_batch_size_avg",
            "kafka_connect_connector_task_batch_size_max",
            "kafka_connect_connector_task_offset_commit_avg_time_ms",
            "kafka_connect_connector_task_running_ratio",
            "kafka_connect_connector_task_status"
        ),
        SOURCE, List.of(
            "kafka_connect_source_task_source_record_poll_total",
            "kafka_connect_source_task_source_record_write_total",
            "kafka_connect_source_task_poll_batch_avg_time_ms"
        ),
        SINK, List.of(
            "kafka_connect_sink_task_sink_record_read_total",
            "kafka_connect_sink_task_sink_record_send_total",
            "kafka_connect_sink_task_put_batch_avg_time_ms",
            "kafka_connect_sink_task_offset_commit_completion_total"
        ),
        RESOURCES, List.of(
            "jvm_memory_used_bytes",
            "jvm_memory_max_bytes",
            "jvm_gc_collection_seconds_count",
            "jvm_gc_collection_seconds_sum",
            "process_cpu_seconds_total",
            "jvm_threads_current"
        )
    );

    private static final Map<String, String> DESCRIPTIONS = Map.of(
        WORKER,
            "**[HIGH - CONNECT WORKER METRICS]**\n\n"
                + "kafka_connect_worker_connector_count: Number of connectors deployed on this worker. "
                + "A drop may indicate connectors have been removed or failed to start.\n\n"
                + "kafka_connect_worker_task_count: Number of active tasks across all connectors. "
                + "Should match expected tasksMax sum. A drop indicates task failures.\n\n"
                + "kafka_connect_worker_connector_startup_failure_total / success_total: Cumulative startup "
                + "attempts. Rising failure count = connector configuration or dependency issues.\n\n"
                + "kafka_connect_worker_task_startup_failure_total / success_total: Cumulative task startup "
                + "attempts. Rising failure count = task-level configuration or resource issues.",
        CONNECTOR,
            "**[HIGH - CONNECTOR TASK METRICS]**\n\n"
                + "kafka_connect_connector_task_batch_size_avg / max: Average and maximum batch sizes. "
                + "Small batches = low throughput efficiency. Large batches = good throughput.\n\n"
                + "kafka_connect_connector_task_offset_commit_avg_time_ms: Average time to commit offsets. "
                + "**THRESHOLDS**: <100ms = healthy, 100-500ms = monitor, >500ms = investigate Kafka broker load.\n\n"
                + "kafka_connect_connector_task_running_ratio: Fraction of time the task spends running "
                + "vs waiting. **THRESHOLDS**: >0.9 = healthy, 0.5-0.9 = monitor, <0.5 = investigate.\n\n"
                + "kafka_connect_connector_task_status: Task state (0=unassigned, 1=running, 2=paused, 3=failed).",
        SOURCE,
            "**[HIGH - SOURCE CONNECTOR METRICS]**\n\n"
                + "kafka_connect_source_task_source_record_poll_total: Total records polled from the source system. "
                + "Rate of change = source throughput.\n\n"
                + "kafka_connect_source_task_source_record_write_total: Total records written to Kafka. "
                + "Should closely track poll_total. A growing gap indicates write failures.\n\n"
                + "kafka_connect_source_task_poll_batch_avg_time_ms: Average time per poll batch. "
                + "**THRESHOLDS**: <100ms = healthy, 100-1000ms = monitor source system, >1000ms = investigate.",
        SINK,
            "**[HIGH - SINK CONNECTOR METRICS]**\n\n"
                + "kafka_connect_sink_task_sink_record_read_total: Total records read from Kafka. "
                + "Rate of change = consumption throughput.\n\n"
                + "kafka_connect_sink_task_sink_record_send_total: Total records sent to the sink system. "
                + "Should closely track read_total. A growing gap indicates write failures.\n\n"
                + "kafka_connect_sink_task_put_batch_avg_time_ms: Average time per put batch to the sink. "
                + "**THRESHOLDS**: <100ms = healthy, 100-1000ms = monitor sink system, >1000ms = investigate.\n\n"
                + "kafka_connect_sink_task_offset_commit_completion_total: Completed offset commits. "
                + "A stall indicates commit failures — check Kafka broker connectivity.",
        RESOURCES,
            MetricsDescriptions.jvmDescription("get_kafka_connect_pods",
                "connector task failures or slow processing")
    );

    private KafkaConnectMetricCategories() {
    }

    /**
     * Returns the finest meaningful aggregation level for the given category.
     * All connect categories aggregate at cluster level.
     *
     * @param category the category name (ignored — always returns CLUSTER)
     * @return CLUSTER for all categories
     */
    public static AggregationLevel maxGranularity(final String category) {
        return AggregationLevel.CLUSTER;
    }

    /**
     * Resolves a category name to its list of metric names.
     *
     * @param category the category name (case-insensitive)
     * @return the list of metric names, or an empty list if the category is unknown
     */
    public static List<String> resolve(final String category) {
        if (category == null) {
            return List.of();
        }
        return CATEGORIES.getOrDefault(category.toLowerCase(Locale.ROOT), List.of());
    }

    /**
     * Returns all available category names.
     *
     * @return the set of category names
     */
    public static Set<String> allCategories() {
        return CATEGORIES.keySet();
    }

    /**
     * Returns an interpretation guide for the given categories.
     *
     * @param categories the category names to get descriptions for
     * @return a combined interpretation guide, or null if no categories match
     */
    public static String interpretation(final List<String> categories) {
        if (categories == null || categories.isEmpty()) {
            return null;
        }
        String result = categories.stream()
            .map(c -> c.toLowerCase(Locale.ROOT))
            .filter(DESCRIPTIONS::containsKey)
            .map(DESCRIPTIONS::get)
            .collect(Collectors.joining("\n\n"));
        return result.isEmpty() ? null : result;
    }
}
