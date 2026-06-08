/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.dto.kafka;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;
/**
 * Aggregated health overview across all Kafka clusters.
 * Provides status distribution, total broker count, per-cluster summaries,
 * and warnings for clusters that need attention.
 *
 * @param totalClusters      the total number of Kafka clusters
 * @param totalBrokers       the total number of broker replicas across all clusters
 * @param statusDistribution the count of clusters by readiness status
 * @param clusters           per-cluster summary list
 * @param warnings           clusters with health issues (capped at 20)
 * @param timestamp          when this overview was generated
 * @param namespaceFilter    the namespace filter applied, or null for all namespaces
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record KafkaFleetOverviewResponse(
    @JsonProperty("total_clusters") int totalClusters,
    @JsonProperty("total_brokers") int totalBrokers,
    @JsonProperty("status_distribution") StatusDistribution statusDistribution,
    @JsonProperty("clusters") List<ClusterSummary> clusters,
    @JsonProperty("warnings") List<ClusterWarning> warnings,
    @JsonProperty("timestamp") Instant timestamp,
    @JsonProperty("namespace_filter") String namespaceFilter
) {

    /**
     * Count of clusters by readiness status.
     *
     * @param ready    clusters in Ready state
     * @param notReady clusters in NotReady state
     * @param error    clusters in Error state
     * @param unknown  clusters in Unknown state
     */
    public record StatusDistribution(
        @JsonProperty("ready") int ready,
        @JsonProperty("not_ready") int notReady,
        @JsonProperty("error") int error,
        @JsonProperty("unknown") int unknown
    ) {
    }

    /**
     * Minimal summary of a single Kafka cluster with relationship counts.
     *
     * @param name             the cluster name
     * @param namespace        the Kubernetes namespace
     * @param readiness        the cluster readiness status
     * @param kafkaVersion     the Kafka version
     * @param brokers          the expected broker replica count
     * @param readyBrokers     the ready broker replica count
     * @param ageMinutes       the age of the cluster in minutes
     * @param topicCount       number of KafkaTopic resources (null if unavailable)
     * @param userCount        number of KafkaUser resources (null if unavailable)
     * @param activeRebalances number of active KafkaRebalance resources (null if unavailable)
     * @param connectCount     number of connected KafkaConnect clusters (null if unavailable)
     * @param bridgeCount      number of connected KafkaBridge instances (null if unavailable)
     * @param mirrorMakerCount number of connected KafkaMirrorMaker2 instances (null if unavailable)
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ClusterSummary(
        @JsonProperty("name") String name,
        @JsonProperty("namespace") String namespace,
        @JsonProperty("readiness") String readiness,
        @JsonProperty("kafka_version") String kafkaVersion,
        @JsonProperty("brokers") int brokers,
        @JsonProperty("ready_brokers") int readyBrokers,
        @JsonProperty("age_minutes") Long ageMinutes,
        @JsonProperty("topic_count") Integer topicCount,
        @JsonProperty("user_count") Integer userCount,
        @JsonProperty("active_rebalances") Integer activeRebalances,
        @JsonProperty("connect_count") Integer connectCount,
        @JsonProperty("bridge_count") Integer bridgeCount,
        @JsonProperty("mirror_maker_count") Integer mirrorMakerCount
    ) {
    }

    /**
     * Warning for a cluster that needs attention.
     *
     * @param clusterName the cluster name
     * @param namespace   the Kubernetes namespace
     * @param warningType the type of warning (e.g., NotReady, Error, ReplicaMismatch)
     * @param message     human-readable warning description
     */
    public record ClusterWarning(
        @JsonProperty("cluster_name") String clusterName,
        @JsonProperty("namespace") String namespace,
        @JsonProperty("warning_type") String warningType,
        @JsonProperty("message") String message
    ) {
    }
}
