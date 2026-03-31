/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.config.metrics;

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

    private static final Map<String, List<String>> CATEGORIES = Map.of(
        "reconciliation", List.of(
            "strimzi_reconciliations_successful_total",
            "strimzi_reconciliations_failed_total",
            "strimzi_reconciliations_total",
            "strimzi_reconciliations_duration_seconds_sum",
            "strimzi_reconciliations_duration_seconds_count"
        ),
        "resources", List.of(
            "strimzi_resources",
            "strimzi_resource_state"
        ),
        "jvm", List.of(
            "jvm_memory_used_bytes",
            "jvm_memory_max_bytes",
            "jvm_gc_collection_seconds_count",
            "jvm_gc_collection_seconds_sum",
            "process_cpu_seconds_total",
            "jvm_threads_current"
        )
    );

    private static final Map<String, String> DESCRIPTIONS = Map.of(
        "reconciliation",
            "strimzi_reconciliations_successful_total: Cumulative count of successful reconciliations. "
                + "Should increase steadily.\n"
                + "strimzi_reconciliations_failed_total: Cumulative count of failed reconciliations. "
                + "Should be 0 or stable. Increasing = operator errors, check operator logs.\n"
                + "strimzi_reconciliations_total: Total reconciliations attempted. "
                + "Compare with successful + failed to verify consistency.\n"
                + "strimzi_reconciliations_duration_seconds_sum/count: Reconciliation duration. "
                + "Divide sum by count for average. >60s average = slow reconciliation, "
                + "may indicate resource contention or complex configurations.",
        "resources",
            "strimzi_resources: Count of Strimzi custom resources (Kafka, KafkaTopic, etc.) "
                + "managed by the operator. Sudden changes = resources added/removed.\n"
                + "strimzi_resource_state: Health state of each managed resource. "
                + "1 = healthy/ready, 0 = unhealthy/not ready. "
                + "Any value != 1 indicates a resource that needs attention.",
        "jvm",
            "jvm_memory_used_bytes: Current JVM heap/non-heap memory usage. "
                + "Java normally uses most of its allocated heap — high usage alone is not a problem. "
                + "Only flag as concerning if combined with pod restarts (check with "
                + "get_strimzi_operator_pod), OOM errors in logs, or excessive GC overhead.\n"
                + "jvm_memory_max_bytes: Maximum JVM memory available per pool.\n"
                + "jvm_gc_collection_seconds_sum/count: GC time and frequency. "
                + "High sum/count ratio = long GC pauses. Rapidly increasing count = GC thrashing. "
                + "Only concerning if it correlates with slow reconciliations or pod restarts.\n"
                + "process_cpu_seconds_total: Cumulative CPU time. Rate of change = CPU utilization.\n"
                + "jvm_threads_current: Active JVM thread count. "
                + "Sudden increases may indicate thread leaks or excessive concurrency."
    );

    private StrimziOperatorMetricCategories() {
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
