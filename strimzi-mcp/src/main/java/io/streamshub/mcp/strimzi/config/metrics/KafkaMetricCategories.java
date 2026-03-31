/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.config.metrics;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Curated metric name categories for Kafka broker metrics.
 * Maps human-friendly category names to lists of Prometheus metric names.
 */
public final class KafkaMetricCategories {

    private static final Map<String, List<String>> CATEGORIES = Map.of(
        "replication", List.of(
            "kafka_server_replicamanager_underreplicatedpartitions",
            "kafka_server_replicamanager_leadercount",
            "kafka_server_replicamanager_partitioncount",
            "kafka_server_replicamanager_offlinereplicacount",
            "kafka_controller_kafkacontroller_offlinepartitionscount",
            "kafka_server_replicafetchermanager_maxlag"
        ),
        "throughput", List.of(
            "kafka_server_brokertopicmetrics_messagesin_total",
            "kafka_server_brokertopicmetrics_bytesin_total",
            "kafka_server_brokertopicmetrics_bytesout_total",
            "kafka_server_brokertopicmetrics_totalproducerequests_total",
            "kafka_server_brokertopicmetrics_totalfetchrequests_total"
        ),
        "resources", List.of(
            "jvm_memory_used_bytes",
            "jvm_memory_max_bytes",
            "jvm_gc_collection_seconds_count",
            "jvm_gc_collection_seconds_sum",
            "process_cpu_seconds_total",
            "jvm_threads_current"
        ),
        "performance", List.of(
            "kafka_network_requestmetrics_totaltimems",
            "kafka_server_kafkarequesthandlerpool_brokerrequesthandleravgidle_percent",
            "kafka_network_requestmetrics_requestqueuetimems",
            "kafka_network_requestmetrics_responsequeuetimems",
            "kafka_network_socketserver_networkprocessoravgidle_percent"
        )
    );

    private KafkaMetricCategories() {
        // Utility class — no instantiation
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
        return CATEGORIES.getOrDefault(category.toLowerCase(java.util.Locale.ROOT), List.of());
    }

    /**
     * Returns all available category names.
     *
     * @return the set of category names
     */
    public static Set<String> allCategories() {
        return CATEGORIES.keySet();
    }
}
