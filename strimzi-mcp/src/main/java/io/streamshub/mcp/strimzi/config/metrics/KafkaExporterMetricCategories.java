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
 * Curated metric name categories for Kafka Exporter metrics.
 * Maps human-friendly category names to lists of Prometheus metric names,
 * and provides interpretation guides for each category.
 */
public final class KafkaExporterMetricCategories {

    /**
     * Consumer group lag category (offset, lag, lag seconds).
     */
    public static final String CONSUMER_LAG = "consumer_lag";

    /**
     * Topic partition category (offsets, ISR, under-replicated, replicas).
     */
    public static final String PARTITIONS = "partitions";

    /**
     * JVM and system resource category (heap, GC, CPU, threads).
     */
    public static final String RESOURCES = "resources";

    private static final Map<String, List<String>> CATEGORIES = Map.of(
        CONSUMER_LAG, List.of(
            "kafka_consumergroup_current_offset",
            "kafka_consumergroup_lag",
            "kafka_consumergroup_lag_seconds"
        ),
        PARTITIONS, List.of(
            "kafka_topic_partitions",
            "kafka_topic_partition_current_offset",
            "kafka_topic_partition_oldest_offset",
            "kafka_topic_partition_in_sync_replica",
            "kafka_topic_partition_under_replicated_partition",
            "kafka_topic_partition_replicas"
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
        CONSUMER_LAG,
            "**[HIGH - CONSUMER GROUP LAG]**\n\n"
                + "kafka_consumergroup_lag: Number of messages a consumer group is behind the latest offset. "
                + "**THRESHOLDS**: 0 = fully caught up, <1000 = healthy, 1000-10000 = monitor, "
                + ">10000 = consumers falling behind (scale consumers or investigate slowness). "
                + "Sustained growth = consumers cannot keep up with producer throughput.\n\n"
                + "kafka_consumergroup_lag_seconds: Estimated time in seconds for the consumer group "
                + "to catch up. >60s = significant delay, >300s = investigate consumer health.\n\n"
                + "kafka_consumergroup_current_offset: Current committed offset per consumer group/partition. "
                + "Stalled offset = consumer is stuck or dead. Compare with partition end offset to calculate lag.",
        PARTITIONS,
            "**[HIGH - PARTITION HEALTH]**\n\n"
                + "kafka_topic_partition_under_replicated_partition: Partitions where ISR count < replica count. "
                + "Should be 0. >0 means replicas are lagging — check broker health and disk I/O. "
                + "**TIME-SENSITIVE**: Transient during rolling restarts, persistent = data loss risk.\n\n"
                + "kafka_topic_partition_in_sync_replica: Number of in-sync replicas per partition. "
                + "Should equal kafka_topic_partition_replicas. Drop below min.insync.replicas = "
                + "producers with acks=all will fail.\n\n"
                + "kafka_topic_partition_replicas: Configured replica count per partition.\n\n"
                + "**[MEDIUM - PARTITION OFFSETS]**\n\n"
                + "kafka_topic_partition_current_offset: Latest offset (end of log) per partition. "
                + "Rate of change = write throughput per partition.\n\n"
                + "kafka_topic_partition_oldest_offset: Earliest available offset per partition. "
                + "Gap between oldest and current = retained data volume. "
                + "Oldest offset advancing = log segments being cleaned/compacted.\n\n"
                + "kafka_topic_partitions: Number of partitions per topic.",
        RESOURCES,
            MetricsDescriptions.jvmDescription("get_kafka_cluster_pods",
                "exporter scraping failures or missing metrics")
    );

    private KafkaExporterMetricCategories() {
        // Utility class — no instantiation
    }

    /**
     * Returns the finest meaningful aggregation level for the given category.
     *
     * @param category the category name (case-insensitive)
     * @return the max granularity, defaults to BROKER for null/unknown
     */
    public static AggregationLevel maxGranularity(final String category) {
        if (category == null) {
            return AggregationLevel.BROKER;
        }
        String lower = category.toLowerCase(Locale.ROOT);
        if (CONSUMER_LAG.equals(lower) || PARTITIONS.equals(lower)) {
            return AggregationLevel.PARTITION;
        }
        return AggregationLevel.BROKER;
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
