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
 * Curated metric name categories for KafkaBridge metrics.
 * Maps human-friendly category names to lists of Prometheus metric names,
 * and provides interpretation guides for each category.
 */
public final class KafkaBridgeMetricCategories {

    /**
     * HTTP request category (request counts, durations, active connections).
     */
    public static final String HTTP = "http";

    /**
     * Kafka producer category (record sends, request latency, batch size).
     */
    public static final String PRODUCER = "producer";

    /**
     * Kafka consumer category (fetch latency, records consumed, lag).
     */
    public static final String CONSUMER = "consumer";

    /**
     * JVM and system resource category (heap, GC, CPU, threads).
     */
    public static final String RESOURCES = "resources";

    private static final Map<String, List<String>> CATEGORIES = Map.of(
        HTTP, List.of(
            "strimzi_bridge_http_server_requestCount_total",
            "strimzi_bridge_http_server_request_size_bytes",
            "strimzi_bridge_http_server_response_size_bytes",
            "strimzi_bridge_http_server_active_connections",
            "strimzi_bridge_http_server_active_requests"
        ),
        PRODUCER, List.of(
            "strimzi_bridge_kafka_producer_record_send_total",
            "strimzi_bridge_kafka_producer_record_send_rate",
            "strimzi_bridge_kafka_producer_record_error_total",
            "strimzi_bridge_kafka_producer_record_error_rate",
            "strimzi_bridge_kafka_producer_request_latency_avg",
            "strimzi_bridge_kafka_producer_request_latency_max",
            "strimzi_bridge_kafka_producer_batch_size_avg",
            "strimzi_bridge_kafka_producer_byte_total"
        ),
        CONSUMER, List.of(
            "strimzi_bridge_kafka_consumer_fetch_latency_avg",
            "strimzi_bridge_kafka_consumer_fetch_latency_max",
            "strimzi_bridge_kafka_consumer_records_consumed_total",
            "strimzi_bridge_kafka_consumer_records_consumed_rate",
            "strimzi_bridge_kafka_consumer_bytes_consumed_total",
            "strimzi_bridge_kafka_consumer_bytes_consumed_rate",
            "strimzi_bridge_kafka_consumer_fetch_total",
            "strimzi_bridge_kafka_consumer_fetch_rate"
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
        HTTP,
            "**[HIGH - HTTP REQUEST METRICS]**\n\n"
                + "strimzi_bridge_http_server_requestCount_total: Total HTTP requests handled by the bridge. "
                + "Rate of change = throughput. Sudden drops may indicate connectivity issues.\n\n"
                + "strimzi_bridge_http_server_active_connections: Current active HTTP connections. "
                + "Sustained high values may indicate connection leaks or slow consumers.\n\n"
                + "strimzi_bridge_http_server_active_requests: In-flight HTTP requests. "
                + "High values indicate the bridge is under heavy load or Kafka is slow to respond.\n\n"
                + "strimzi_bridge_http_server_request_size_bytes / response_size_bytes: "
                + "Request and response payload sizes. Monitor for unusually large messages.",
        PRODUCER,
            "**[HIGH - PRODUCER METRICS]**\n\n"
                + "strimzi_bridge_kafka_producer_record_send_total / rate: Total records and rate sent to Kafka via HTTP produce API. "
                + "Rate drop = producers stopped or bridge issue.\n\n"
                + "strimzi_bridge_kafka_producer_record_error_total / rate: Failed record sends. Should be 0. "
                + ">0 = investigate Kafka broker health or topic configuration.\n\n"
                + "strimzi_bridge_kafka_producer_request_latency_avg / max: Latency for produce requests to Kafka. "
                + "**THRESHOLDS**: <10ms = healthy, 10-100ms = monitor, >100ms = investigate broker load.\n\n"
                + "strimzi_bridge_kafka_producer_batch_size_avg: Average batch size. Small batches = low throughput efficiency.",
        CONSUMER,
            "**[HIGH - CONSUMER METRICS]**\n\n"
                + "strimzi_bridge_kafka_consumer_fetch_latency_avg / max: Time to fetch records from Kafka. "
                + "**THRESHOLDS**: <50ms = healthy, 50-200ms = monitor, >200ms = investigate.\n\n"
                + "strimzi_bridge_kafka_consumer_records_consumed_total / rate: Records consumed via HTTP consumer API. "
                + "Rate changes indicate consumer activity shifts.\n\n"
                + "strimzi_bridge_kafka_consumer_bytes_consumed_total / rate: Bytes consumed. "
                + "Monitor for throughput changes and capacity planning.",
        RESOURCES,
            MetricsDescriptions.jvmDescription("get_kafka_bridge_pods",
                "bridge request failures or slow HTTP responses")
    );

    private KafkaBridgeMetricCategories() {
    }

    /**
     * Returns the finest meaningful aggregation level for the given category.
     * All bridge categories aggregate at cluster level.
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
