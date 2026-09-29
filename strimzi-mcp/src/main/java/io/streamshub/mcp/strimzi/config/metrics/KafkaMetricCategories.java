/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.config.metrics;

import io.streamshub.mcp.common.dto.metrics.AggregationLevel;
import io.streamshub.mcp.strimzi.util.MetricNameResolver;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Curated metric name categories for Kafka broker metrics.
 * Maps human-friendly category names to lists of Prometheus metric names,
 * and provides interpretation guides for each category.
 */
public final class KafkaMetricCategories {

    /**
     * Replication health category (under-replicated partitions, offline partitions, ISR lag).
     */
    public static final String REPLICATION = "replication";

    /**
     * Throughput category (bytes in/out, messages in, produce/fetch requests).
     */
    public static final String THROUGHPUT = "throughput";

    /**
     * JVM and system resource category (heap, GC, CPU, threads).
     */
    public static final String RESOURCES = "resources";

    /**
     * Request performance category (handler idle, queue times, network processor idle).
     */
    public static final String PERFORMANCE = "performance";

    /**
     * KRaft quorum health category (leader, epoch, state, commit latency, append/fetch rates, channel I/O).
     */
    public static final String KRAFT = "kraft";

    /**
     * Per-partition replica health category (under min ISR, at min ISR, replica count).
     */
    public static final String PARTITIONS = "partitions";

    private static final Map<String, List<String>> CATEGORIES = Map.of(
        REPLICATION, List.of(
            "kafka_server_replicamanager_underreplicatedpartitions",
            "kafka_server_replicamanager_leadercount",
            "kafka_server_replicamanager_partitioncount",
            "kafka_server_replicamanager_offlinereplicacount",
            "kafka_controller_kafkacontroller_offlinepartitionscount",
            "kafka_controller_controllerstats_uncleanleaderelections_total",
            "kafka_controller_kafkacontroller_activecontrollercount",
            "kafka_server_replicafetchermanager_maxlag"
        ),
        THROUGHPUT, List.of(
            "kafka_server_brokertopicmetrics_messagesin_total",
            "kafka_server_brokertopicmetrics_bytesin_total",
            "kafka_server_brokertopicmetrics_bytesout_total",
            "kafka_server_brokertopicmetrics_totalproducerequests_total",
            "kafka_server_brokertopicmetrics_totalfetchrequests_total",
            "kafka_server_brokertopicmetrics_failedproducerequests_total",
            "kafka_server_brokertopicmetrics_failedfetchrequests_total",
            "kafka_server_socket_server_metrics_connection_count"
        ),
        RESOURCES, List.of(
            "jvm_memory_used_bytes",
            "jvm_memory_max_bytes",
            "jvm_gc_collection_seconds_count",
            "jvm_gc_collection_seconds_sum",
            "process_cpu_seconds_total",
            "process_open_fds",
            "jvm_threads_current"
        ),
        PERFORMANCE, List.of(
            "kafka_network_requestmetrics_totaltimems",
            "kafka_server_kafkarequesthandlerpool_requesthandleravgidle_percent",
            "kafka_network_requestmetrics_requestqueuetimems",
            "kafka_network_requestmetrics_responsequeuetimems",
            "kafka_network_socketserver_networkprocessoravgidle_percent"
        ),
        KRAFT, List.of(
            "kafka_server_raftmetrics_current_state",
            "kafka_server_raftmetrics_current_leader",
            "kafka_server_raftmetrics_current_epoch",
            "kafka_server_raftmetrics_current_vote",
            "kafka_server_raftmetrics_high_watermark",
            "kafka_server_raftmetrics_log_end_offset",
            "kafka_server_raftmetrics_commit_latency_avg",
            "kafka_server_raftmetrics_append_records_rate",
            "kafka_server_raftmetrics_fetch_records_rate",
            "kafka_server_raftchannelmetrics_incoming_byte_total",
            "kafka_server_raftchannelmetrics_outgoing_byte_total",
            "kafka_server_raftchannelmetrics_request_total",
            "kafka_server_raftchannelmetrics_response_total"
        ),
        PARTITIONS, List.of(
            "kafka_cluster_partition_underminisr",
            "kafka_cluster_partition_atminisr",
            "kafka_cluster_partition_replicascount"
        )
    );

    private static final Map<String, String> DESCRIPTIONS = Map.of(
        REPLICATION,
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
                + "Should be 0. >0 = broker or disk issues. Check pod status and logs.\n\n"
                + "**[CRITICAL - DATA INTEGRITY]**\n\n"
                + "kafka_controller_controllerstats_uncleanleaderelections_total: Unclean leader elections "
                + "where a non-ISR replica became leader. Should be 0. >0 indicates data loss has occurred. "
                + "**CRITICAL ALERT**: Unclean leader election results in unrecoverable message loss for affected partitions.\n\n"
                + "**[HIGH - CONTROLLER HEALTH]**\n\n"
                + "kafka_controller_kafkacontroller_activecontrollercount: Number of active controllers across the cluster. "
                + "Should be exactly 1. 0 = no active controller (cluster metadata operations stalled), "
                + ">1 = split-brain condition (investigate controller network connectivity).",
        THROUGHPUT,
            "kafka_server_brokertopicmetrics_messagesin_total: Cumulative messages received. "
                + "Rate of change = messages/sec. Sudden drops = producer issues.\n"
                + "kafka_server_brokertopicmetrics_bytesin_total: Cumulative bytes received. "
                + "Request aggregation=broker to compare brokers — large imbalance = hot partitions.\n"
                + "kafka_server_brokertopicmetrics_bytesout_total: Cumulative bytes sent to consumers. "
                + "bytesout >> bytesin can indicate replication or high consumer fan-out.\n"
                + "kafka_server_brokertopicmetrics_totalproducerequests_total: Total produce requests. "
                + "Rate = produce request throughput.\n"
                + "kafka_server_brokertopicmetrics_totalfetchrequests_total: Total fetch requests. "
                + "Includes consumer and follower fetches.\n"
                + "kafka_server_brokertopicmetrics_failedproducerequests_total: Failed produce requests per second (under Prometheus). "
                + "Rate of failed produce attempts. >0 indicates producer-side authentication, authorization, or format errors.\n"
                + "kafka_server_brokertopicmetrics_failedfetchrequests_total: Failed fetch requests per second (under Prometheus). "
                + "Rate of failed fetch attempts. >0 indicates consumer-side errors or invalid offsets.\n"
                + "kafka_server_socket_server_metrics_connection_count: Current active socket connection count per listener and network processor.\n\n"
                + "**[AGGREGATION]** These are cluster totals, summed across brokers — each series "
                + "carries `aggregation_fn` = \"sum\". Kafka publishes each of them per topic *and* as a "
                + "broker-wide roll-up; at the default level only the roll-up is used. Request "
                + "aggregation=topic for a per-topic breakdown — that response also carries one row "
                + "with no `topic` label, which is the total of the others, so do not sum the rows.",
        RESOURCES,
            MetricsDescriptions.jvmDescription("get_kafka_cluster_pods",
                "performance degradation or pod restarts"),
        PERFORMANCE,
            "**[HIGH - BROKER CAPACITY]**\n\n"
                + "kafka_server_kafkarequesthandlerpool_requesthandleravgidle_percent: "
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
                + "ratio. **THRESHOLDS**: >0.5 = healthy, 0.3-0.5 = monitor, <0.3 = network bottleneck.",
        KRAFT,
            "**[CRITICAL - KRAFT QUORUM STATE]**\n\n"
                + "kafka_server_raftmetrics_current_state: Current role of this node in the KRaft quorum. "
                + "**NOTE**: This is an info metric (gauge value is always 1). The actual role is carried "
                + "in the current_state label (leader, follower, candidate, voter, unattached, observer). "
                + "Read the label on the sample, not the numeric value.\n\n"
                + "kafka_server_raftmetrics_current_leader: Node ID of the current active KRaft leader. "
                + "-1 indicates no leader elected (election in progress or quorum lost).\n\n"
                + "kafka_server_raftmetrics_current_epoch: Current leader epoch number. "
                + "Increments with each leader election. Rapidly increasing epoch indicates leader instability.\n\n"
                + "kafka_server_raftmetrics_current_vote: Node ID that this node voted for in the current epoch. "
                + "-1 indicates no vote cast in this epoch.\n\n"
                + "**[HIGH - KRAFT REPLICATION PROGRESS]**\n\n"
                + "kafka_server_raftmetrics_high_watermark: High watermark offset of the metadata log. "
                + "All committed metadata records are <= this offset across the quorum.\n\n"
                + "kafka_server_raftmetrics_log_end_offset: Log end offset of the metadata log on this node. "
                + "Compare across quorum nodes — difference between leader LEO and follower LEO indicates metadata replication lag.\n\n"
                + "kafka_server_raftmetrics_commit_latency_avg: Average latency in milliseconds to commit metadata records to quorum.\n\n"
                + "**[MEDIUM - KRAFT THROUGHPUT & CHANNEL METRICS]**\n\n"
                + "kafka_server_raftmetrics_append_records_rate: Rate of metadata record appends per second.\n\n"
                + "kafka_server_raftmetrics_fetch_records_rate: Rate of metadata fetch requests per second.\n\n"
                + "kafka_server_raftchannelmetrics_incoming_byte_total: Cumulative incoming bytes on the Raft channel. "
                + "Under Prometheus, rate-converted to bytes/sec (rate per second).\n\n"
                + "kafka_server_raftchannelmetrics_outgoing_byte_total: Cumulative outgoing bytes on the Raft channel. "
                + "Under Prometheus, rate-converted to bytes/sec (rate per second).\n\n"
                + "kafka_server_raftchannelmetrics_request_total: Cumulative Raft requests sent/received. "
                + "Under Prometheus, rate-converted to requests/sec (rate per second).\n\n"
                + "kafka_server_raftchannelmetrics_response_total: Cumulative Raft responses sent/received. "
                + "Under Prometheus, rate-converted to responses/sec (rate per second).",
        PARTITIONS,
            "**[CRITICAL - PRODUCER AVAILABILITY]**\n\n"
                + "kafka_cluster_partition_underminisr: Partitions where in-sync replicas are below min.insync.replicas. "
                + "Should be 0. >0 means producers with acks=all are actively failing with NotEnoughReplicasException. "
                + "**IMMEDIATE ACTION REQUIRED**: Identify affected partitions and check follower broker health.\n\n"
                + "**[HIGH - PARTITION AT-RISK]**\n\n"
                + "kafka_cluster_partition_atminisr: Partitions where in-sync replicas are exactly at min.insync.replicas. "
                + "Partitions are functioning but have no redundancy buffer — one more broker failure will cause producer errors.\n\n"
                + "kafka_cluster_partition_replicascount: Total configured replica count per partition."
    );

    private KafkaMetricCategories() {
        // Utility class — no instantiation
    }

    /**
     * Returns the JMX Exporter → Strimzi Metrics Reporter alias map for Kafka broker metrics.
     *
     * <p>Keys are JMX Exporter metric names (what this catalog uses by default).
     * Values are the corresponding Strimzi Metrics Reporter names. When the value is
     * {@link MetricNameResolver#UNMAPPED} the metric cannot be queried under SMR and must
     * be excluded from the resolved list for that backend.</p>
     *
     * <p>Names that are identical in both backends are not listed here — the resolver
     * passes them through unchanged.</p>
     *
     * @return an unmodifiable alias map; never null
     */
    public static Map<String, String> aliasMap() {
        return Map.ofEntries(
            // REPLICATION — ControllerStats meters follow the same PerSec convention as
            // BrokerTopicMetrics: the JMX exporter rules strip PerSec, SMR keeps it.
            Map.entry("kafka_controller_controllerstats_uncleanleaderelections_total",
                "kafka_controller_controllerstats_uncleanleaderelectionspersec_total"),
            // REPLICATION — SMR exposes no ReplicaFetcherManager MBeans at all (verified by
            // scraping a 4.3.1 SMR broker: no kafka_server_replicafetcher* series exist).
            // UNMAPPED excludes this name from the SMR query.
            Map.entry("kafka_server_replicafetchermanager_maxlag",
                MetricNameResolver.UNMAPPED),
            // THROUGHPUT — broker-topic metrics: JMX adds PerSec, SMR adds PerSec + _total
            Map.entry("kafka_server_brokertopicmetrics_messagesin_total",
                "kafka_server_brokertopicmetrics_messagesinpersec_total"),
            Map.entry("kafka_server_brokertopicmetrics_bytesin_total",
                "kafka_server_brokertopicmetrics_bytesinpersec_total"),
            Map.entry("kafka_server_brokertopicmetrics_bytesout_total",
                "kafka_server_brokertopicmetrics_bytesoutpersec_total"),
            Map.entry("kafka_server_brokertopicmetrics_totalproducerequests_total",
                "kafka_server_brokertopicmetrics_totalproducerequestspersec_total"),
            Map.entry("kafka_server_brokertopicmetrics_totalfetchrequests_total",
                "kafka_server_brokertopicmetrics_totalfetchrequestspersec_total"),
            Map.entry("kafka_server_brokertopicmetrics_failedproducerequests_total",
                "kafka_server_brokertopicmetrics_failedproducerequestspersec_total"),
            Map.entry("kafka_server_brokertopicmetrics_failedfetchrequests_total",
                "kafka_server_brokertopicmetrics_failedfetchrequestspersec_total"),
            // PERFORMANCE — requesthandleravgidle: SMR exposes as nanosecond counter, not 0-1 gauge.
            // Semantics differ by ~1e9; there is no expression channel to fix this (F7).
            // UNMAPPED excludes this name from the SMR query.
            Map.entry("kafka_server_kafkarequesthandlerpool_requesthandleravgidle_percent",
                MetricNameResolver.UNMAPPED),
            // PERFORMANCE — networkprocessoravgidle: name change only, no unit change.
            // Confirmed against a live SMR broker.
            Map.entry("kafka_network_socketserver_networkprocessoravgidle_percent",
                "kafka_network_socketserver_networkprocessoravgidlepercent"),
            // KRAFT — 13 quorum metrics (JMX -> SMR aliases per §4.1)
            Map.entry("kafka_server_raftmetrics_current_state",
                "kafka_server_raft_metrics_current_state_info"),
            Map.entry("kafka_server_raftmetrics_current_leader",
                "kafka_server_raft_metrics_current_leader"),
            Map.entry("kafka_server_raftmetrics_current_epoch",
                "kafka_server_raft_metrics_current_epoch"),
            Map.entry("kafka_server_raftmetrics_current_vote",
                "kafka_server_raft_metrics_current_vote"),
            Map.entry("kafka_server_raftmetrics_high_watermark",
                "kafka_server_raft_metrics_high_watermark"),
            Map.entry("kafka_server_raftmetrics_log_end_offset",
                "kafka_server_raft_metrics_log_end_offset"),
            Map.entry("kafka_server_raftmetrics_commit_latency_avg",
                "kafka_server_raft_metrics_commit_latency_avg"),
            Map.entry("kafka_server_raftmetrics_append_records_rate",
                "kafka_server_raft_metrics_append_records_rate"),
            Map.entry("kafka_server_raftmetrics_fetch_records_rate",
                "kafka_server_raft_metrics_fetch_records_rate"),
            // The JMX names are counters, so the Prometheus provider rate-converts them to
            // *_rate_per_second. SMR exposes both a cumulative series and a *_rate sibling;
            // alias to the rate one, otherwise the SMR backend returns a raw running total
            // where the JMX backend returns a per-second value.
            Map.entry("kafka_server_raftchannelmetrics_incoming_byte_total",
                "kafka_server_raft_channel_metrics_incoming_byte_rate"),
            Map.entry("kafka_server_raftchannelmetrics_outgoing_byte_total",
                "kafka_server_raft_channel_metrics_outgoing_byte_rate"),
            Map.entry("kafka_server_raftchannelmetrics_request_total",
                "kafka_server_raft_channel_metrics_request_rate"),
            Map.entry("kafka_server_raftchannelmetrics_response_total",
                "kafka_server_raft_channel_metrics_response_rate")
        );
    }

    /**
     * Returns the finest meaningful aggregation level for the given category.
     *
     * @param category the category name (case-insensitive)
     * @return the max granularity, defaults to BROKER for null/unknown
     */
    public static AggregationLevel maxGranularity(final String category) {
        if (category != null) {
            String lower = category.toLowerCase(Locale.ROOT);
            if (PARTITIONS.equals(lower)) {
                return AggregationLevel.PARTITION;
            }
            if (THROUGHPUT.equals(lower)) {
                return AggregationLevel.TOPIC;
            }
            if (KRAFT.equals(lower)) {
                return AggregationLevel.BROKER;
            }
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

