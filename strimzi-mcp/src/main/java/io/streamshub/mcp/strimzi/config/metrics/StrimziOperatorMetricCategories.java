/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.config.metrics;

import io.streamshub.mcp.common.dto.metrics.AggregationLevel;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
/**
 * Curated metric name categories for Strimzi cluster operator metrics.
 * Maps human-friendly category names to lists of Prometheus metric names,
 * and provides interpretation guides for each category.
 */
public final class StrimziOperatorMetricCategories {

    /**
     * Reconciliation category (successful, failed, total, duration).
     */
    public static final String RECONCILIATION = "reconciliation";

    /**
     * Strimzi resource state category.
     */
    public static final String RESOURCES = "resources";

    /**
     * JVM metrics category (Micrometer: heap, GC pause, CPU usage, threads).
     */
    public static final String JVM = "jvm";

    private static final Map<String, List<String>> CATEGORIES = Map.of(
        RECONCILIATION, List.of(
            "strimzi_reconciliations_successful_total",
            "strimzi_reconciliations_failed_total",
            "strimzi_reconciliations_total",
            "strimzi_reconciliations_duration_seconds_sum",
            "strimzi_reconciliations_duration_seconds_count",
            "strimzi_reconciliations_locked_total",
            "strimzi_reconciliations_periodical_total"
        ),
        RESOURCES, List.of(
            "strimzi_resources",
            // Removed from the operator in Strimzi 1.2.0; kept for clusters still on 1.1.x.
            "strimzi_resource_state",
            "strimzi_certificate_expiration_timestamp_ms"
        ),
        JVM, List.of(
            "jvm_memory_used_bytes",
            "jvm_memory_max_bytes",
            "jvm_gc_pause_seconds_count",
            "jvm_gc_pause_seconds_sum",
            "process_cpu_usage",
            "jvm_threads_live_threads"
        )
    );

    private static final Map<String, String> DESCRIPTIONS = Map.of(
        RECONCILIATION,
            "**[HIGH - OPERATOR HEALTH]**\n\n"
                + "strimzi_reconciliations_successful_total: Cumulative count of successful reconciliations. "
                + "Should increase steadily. Flat line = no reconciliation activity (check if expected).\n\n"
                + "**[CRITICAL - RECONCILIATION FAILURES]**\n\n"
                + "strimzi_reconciliations_failed_total: Cumulative count of failed reconciliations. "
                + "Should be 0 or stable. **IMMEDIATE ACTION**: Increasing count = operator errors, "
                + "resources not being managed correctly. Check operator logs for root cause.\n\n"
                + "strimzi_reconciliations_total: Total reconciliations attempted. "
                + "Compare with successful + failed to verify consistency.\n\n"
                + "**[MEDIUM - RECONCILIATION PERFORMANCE]**\n\n"
                + "strimzi_reconciliations_duration_seconds_count: Number of reconciliation cycles. "
                + "strimzi_reconciliations_duration_seconds_sum: Total time spent in reconciliations. "
                + "Divide sum by count for average. **THRESHOLDS**: <30s = good, 30-60s = acceptable, "
                + ">60s = slow (may indicate resource contention, complex configurations, or K8s API slowness). "
                + "Increasing trend = operator struggling to keep up.\n\n"
                + "strimzi_reconciliations_locked_total: Count of reconciliations skipped due to lock contention. "
                + "Non-zero = the operator's reconciliation lock is contended; concurrent changes are blocking "
                + "each other. **THRESHOLD**: >5% of total reconciliations warrants investigation.\n\n"
                + "strimzi_reconciliations_periodical_total: Count of periodic (timer-triggered) reconciliations. "
                + "These run on a schedule regardless of resource changes. "
                + "A low ratio of periodic/total = periodic scans are not running as expected.",
        RESOURCES,
            "**[HIGH - RESOURCE MANAGEMENT]**\n\n"
                + "strimzi_resources: Count of Strimzi custom resources (Kafka, KafkaTopic, KafkaUser, etc.) "
                + "managed by the operator. Sudden changes = resources added/removed. "
                + "**BASELINE**: Should match expected resource count. Discrepancy = operator not discovering resources.\n\n"
                + "**[CRITICAL - RESOURCE HEALTH]**\n\n"
                + "strimzi_resource_state: Health state of each managed resource. "
                + "1 = healthy/ready, 0 = unhealthy/not ready. "
                + "**IMMEDIATE ACTION**: Any value != 1 indicates a resource that needs attention. "
                + "Check resource status with get_kafka_cluster or get_kafka_topics. "
                + "**DEPRECATED**: removed from the Strimzi operator in 1.2.0, so it is absent on "
                + "1.2.0 and later — its absence is expected there, not a fault. Fall back to the "
                + "resource's own status condition via get_kafka_cluster.\n\n"
                + "**[MEDIUM - CERTIFICATE LIFECYCLE]**\n\n"
                + "strimzi_certificate_expiration_timestamp_ms: Epoch milliseconds until which Strimzi-managed "
                + "certificates are valid. Declining values = certificates approaching expiry. "
                + "Pairs with get_kafka_cluster_certificates for renewal planning.",
        JVM,
            MetricsDescriptions.micrometerJvmDescription("get_strimzi_operator_pod",
                "slow reconciliations or pod restarts")
    );

    private StrimziOperatorMetricCategories() {
        // Utility class — no instantiation
    }

    /**
     * Returns the finest meaningful aggregation level for the given category.
     * All operator categories aggregate at cluster level.
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
            .map(c -> c.toLowerCase(java.util.Locale.ROOT))
            .filter(DESCRIPTIONS::containsKey)
            .map(DESCRIPTIONS::get)
            .collect(Collectors.joining("\n\n"));
        return result.isEmpty() ? null : result;
    }
}