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
 * Curated metric name categories for Kafka broker metrics.
 * Maps human-friendly category names to lists of Prometheus metric names,
 * and provides interpretation guides for each category.
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

    private static final Map<String, String> DESCRIPTIONS = Map.of(
        "replication",
            "**[CRITICAL - CLUSTER AVAILABILITY]**\n\n"
                + "kafka_server_replicamanager_underreplicatedpartitions: Partitions with fewer in-sync "
                + "replicas than configured. Should be 0. >0 means data loss risk. "
                + "**TIME-SENSITIVE**: If >0 for >5 minutes during normal operations, indicates broker "
                + "overload, network issues, or disk I/O problems. Transient spikes during rolling "
                + "restarts are expected (2-3 minutes per broker).\n\n"
                + "**[CRITICAL - PARTITION AVAILABILITY]**\n\n"
                + "kafka_controller_kafkacontroller_offlinepartitionscount: Partitions with no active "
                + "leader. Should be 0. >0 is critical — those partitions are unavailable to producers "
                + "and consumers. **IMMEDIATE ACTION REQUIRED**. Check broker health and controller logs.\n\n"
                + "**[HIGH - REPLICATION LAG]**\n\n"
                + "kafka_server_replicafetchermanager_maxlag: Maximum replica lag in messages. "
                + "Growing value = followers falling behind. Transient spikes during restarts are normal. "
                + "**THRESHOLDS**: <1000 = healthy, 1000-10000 = monitor, >10000 = investigate broker load.\n\n"
                + "kafka_server_replicamanager_leadercount: Number of partition leaders per broker. "
                + "Should be roughly equal across brokers. Large imbalance = uneven load. "
                + "**THRESHOLD**: >20% variance indicates need for partition reassignment.\n\n"
                + "kafka_server_replicamanager_partitioncount: Total partitions per broker. "
                + "Should be balanced across brokers.\n\n"
                + "kafka_server_replicamanager_offlinereplicacount: Replicas that are offline. "
                + "Should be 0. >0 = broker or disk issues. Check pod status and logs.",
        "throughput",
            "kafka_server_brokertopicmetrics_messagesin_total: Cumulative messages received. "
                + "Rate of change = messages/sec. Sudden drops = producer issues.\n"
                + "kafka_server_brokertopicmetrics_bytesin_total: Cumulative bytes received. "
                + "Compare across brokers — large imbalance = hot partitions.\n"
                + "kafka_server_brokertopicmetrics_bytesout_total: Cumulative bytes sent to consumers. "
                + "bytesout >> bytesin can indicate replication or high consumer fan-out.\n"
                + "kafka_server_brokertopicmetrics_totalproducerequests_total: Total produce requests. "
                + "Rate = produce request throughput.\n"
                + "kafka_server_brokertopicmetrics_totalfetchrequests_total: Total fetch requests. "
                + "Includes consumer and follower fetches.",
        "resources",
            MetricsDescriptions.jvmDescription("get_kafka_cluster_pods",
                "performance degradation or pod restarts"),
        "performance",
            "**[HIGH - BROKER CAPACITY]**\n\n"
                + "kafka_server_kafkarequesthandlerpool_brokerrequesthandleravgidle_percent: "
                + "Request handler thread idle ratio. **CRITICAL THRESHOLDS**: "
                + ">0.5 = healthy headroom, 0.3-0.5 = monitor closely, <0.3 = overloaded (add capacity), "
                + "<0.1 = critical (clients experiencing timeouts).\n\n"
                + "**[HIGH - REQUEST LATENCY]**\n\n"
                + "kafka_network_requestmetrics_requestqueuetimems: Time requests spend waiting "
                + "in the request queue. **THRESHOLDS**: <50ms = good, 50-100ms = acceptable, "
                + ">100ms = bottleneck, >500ms = severe (clients timing out). "
                + "Increasing trend = broker can't keep up with load.\n\n"
                + "kafka_network_requestmetrics_totaltimems: Total time for request processing "
                + "(queue + local + remote + response). High values = slow requests.\n\n"
                + "kafka_network_requestmetrics_responsequeuetimems: Time responses wait before being "
                + "sent. High values = network thread bottleneck.\n\n"
                + "**[MEDIUM - NETWORK CAPACITY]**\n\n"
                + "kafka_network_socketserver_networkprocessoravgidle_percent: Network thread idle "
                + "ratio. **THRESHOLDS**: >0.5 = healthy, 0.3-0.5 = monitor, <0.3 = network bottleneck."
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