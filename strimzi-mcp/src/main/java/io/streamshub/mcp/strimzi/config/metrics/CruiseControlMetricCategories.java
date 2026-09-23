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
 * Curated metric name categories for Cruise Control metrics.
 * Maps human-friendly category names to lists of Prometheus metric names,
 * and provides interpretation guides for each category.
 */
public final class CruiseControlMetricCategories {

    /**
     * Metric category for Cruise Control sample collection and partition monitoring.
     */
    public static final String SAMPLING = "sampling";

    /**
     * Metric category for Cruise Control anomaly detection and goal optimization.
     */
    public static final String ANOMALY = "anomaly";

    private static final Map<String, List<String>> CATEGORIES = Map.of(
        SAMPLING, List.of(
            "kafka_cruisecontrol_loadmonitor_monitored_partitions_percentage_value",
            "kafka_cruisecontrol_loadmonitor_valid_windows_value",
            "kafka_cruisecontrol_loadmonitor_total_monitored_windows_value",
            "kafka_cruisecontrol_metricfetchermanager_partition_samples_fetcher_failure_rate_count",
            "kafka_cruisecontrol_metricfetchermanager_training_samples_fetcher_failure_rate_count"
        ),
        ANOMALY, List.of(
            "kafka_cruisecontrol_anomalydetector_balancedness_score_value",
            "kafka_cruisecontrol_anomalydetector_goal_violation_rate_count",
            "kafka_cruisecontrol_anomalydetector_disk_failure_rate_count",
            "kafka_cruisecontrol_executor_ongoing_execution_non_kafka_assigner_value",
            "kafka_cruisecontrol_goaloptimizer_proposal_computation_timer_mean",
            "kafka_cruisecontrol_goaloptimizer_proposal_computation_timer_max",
            "kafka_cruisecontrol_goaloptimizer_proposal_computation_timer_99thpercentile",
            "kafka_cruisecontrol_goaloptimizer_proposal_computation_timer_999thpercentile"
        )
    );

    private static final Map<String, String> DESCRIPTIONS = Map.of(
        SAMPLING,
            "**[CRITICAL - SAMPLE COVERAGE & PROPOSAL READINESS]**\n\n"
                + "kafka_cruisecontrol_loadmonitor_monitored_partitions_percentage_value: Percentage of partitions "
                + "for which Cruise Control has valid metrics samples (0.0–1.0). "
                + "**THRESHOLDS**: >=0.95 = ready to compute proposals, <0.95 = Cruise Control will refuse to generate proposals (PendingProposal state). "
                + "Common cause: newly created topics or dead metric fetcher.\n\n"
                + "kafka_cruisecontrol_loadmonitor_valid_windows_value: Number of monitored windows with sufficient sample coverage. "
                + "Cruise Control requires min.monitored.windows before evaluating goals. "
                + "Low value = Cruise Control recently restarted or sampling rate insufficient.\n\n"
                + "kafka_cruisecontrol_loadmonitor_total_monitored_windows_value: Total number of configured sliding windows in the load monitor.\n\n"
                + "**[HIGH - SAMPLE FETCHER FAILURES]**\n\n"
                + "kafka_cruisecontrol_metricfetchermanager_partition_samples_fetcher_failure_rate_count: Cumulative failure count "
                + "when fetching partition metric samples from brokers. Rising count indicates broker connectivity issues.\n\n"
                + "kafka_cruisecontrol_metricfetchermanager_training_samples_fetcher_failure_rate_count: Cumulative failure count "
                + "when fetching model training samples.",
        ANOMALY,
            "**[HIGH - CLUSTER BALANCE & ANOMALY DETECTION]**\n\n"
                + "kafka_cruisecontrol_anomalydetector_balancedness_score_value: Overall cluster balancedness score (0.0–100.0). "
                + "Higher score indicates better resource distribution across brokers.\n\n"
                + "kafka_cruisecontrol_anomalydetector_goal_violation_rate_count: Cumulative goal violations detected. "
                + ">0 indicates one or more optimization goals (disk, CPU, leader count) are currently breached.\n\n"
                + "kafka_cruisecontrol_anomalydetector_disk_failure_rate_count: Cumulative detected disk failure count.\n\n"
                + "kafka_cruisecontrol_executor_ongoing_execution_non_kafka_assigner_value: Active rebalance execution status "
                + "(1 = rebalance currently in progress, 0 = idle).\n\n"
                + "**[MEDIUM - PROPOSAL COMPUTATION LATENCY]**\n\n"
                + "kafka_cruisecontrol_goaloptimizer_proposal_computation_timer_mean: Mean time in milliseconds to compute optimization proposals.\n\n"
                + "kafka_cruisecontrol_goaloptimizer_proposal_computation_timer_max: Maximum proposal computation latency in milliseconds.\n\n"
                + "kafka_cruisecontrol_goaloptimizer_proposal_computation_timer_99thpercentile: 99th percentile proposal computation latency in milliseconds.\n\n"
                + "kafka_cruisecontrol_goaloptimizer_proposal_computation_timer_999thpercentile: 99.9th percentile proposal computation latency in milliseconds."
    );

    private CruiseControlMetricCategories() {
        // Utility class — no instantiation
    }

    /**
     * Returns the finest meaningful aggregation level for the given category.
     * Cruise Control runs as a single pod per cluster, so CLUSTER is the max granularity.
     *
     * @param category the category name (ignored — always returns CLUSTER)
     * @return CLUSTER for all Cruise Control categories
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
