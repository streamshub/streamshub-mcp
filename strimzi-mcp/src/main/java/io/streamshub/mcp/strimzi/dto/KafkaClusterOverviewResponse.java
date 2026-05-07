/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Overview of a Kafka cluster and all related Strimzi resources.
 * Provides a dependency graph showing what operator manages the cluster,
 * which node pools, topics, users, and rebalances belong to it, and
 * which KafkaConnect/Bridge resources connect to it.
 *
 * @param cluster       the Kafka cluster identity and health
 * @param operator      the Strimzi operator managing this cluster (null if not found)
 * @param nodePools     the node pools belonging to this cluster
 * @param topics        topic count and readiness breakdown
 * @param users         user count and readiness breakdown
 * @param rebalances    rebalance count, active count, and state breakdown
 * @param kafkaConnects KafkaConnect clusters connected to this Kafka cluster
 * @param kafkaBridges  KafkaBridge instances connected to this Kafka cluster
 * @param drainCleaner  Drain Cleaner readiness (null if not deployed)
 * @param timestamp     when this overview was generated
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record KafkaClusterOverviewResponse(
    @JsonProperty("cluster") ClusterSummary cluster,
    @JsonProperty("operator") OperatorSummary operator,
    @JsonProperty("node_pools") List<NodePoolSummary> nodePools,
    @JsonProperty("topics") ResourceCount topics,
    @JsonProperty("users") ResourceCount users,
    @JsonProperty("rebalances") RebalanceSummary rebalances,
    @JsonProperty("kafka_connects") List<ConnectSummary> kafkaConnects,
    @JsonProperty("kafka_bridges") List<BridgeSummary> kafkaBridges,
    @JsonProperty("drain_cleaner") DrainCleanerSummary drainCleaner,
    @JsonProperty("timestamp") Instant timestamp
) {

    /**
     * Kafka cluster identity and health summary.
     *
     * @param name            the cluster name
     * @param namespace       the Kubernetes namespace
     * @param kafkaVersion    the Kafka version
     * @param readiness       the cluster readiness status
     * @param expectedReplicas expected broker/controller replicas
     * @param readyReplicas   ready broker/controller replicas
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ClusterSummary(
        @JsonProperty("name") String name,
        @JsonProperty("namespace") String namespace,
        @JsonProperty("kafka_version") String kafkaVersion,
        @JsonProperty("readiness") String readiness,
        @JsonProperty("expected_replicas") Integer expectedReplicas,
        @JsonProperty("ready_replicas") Integer readyReplicas
    ) {
    }

    /**
     * Strimzi operator summary.
     *
     * @param name      the operator deployment name
     * @param namespace the operator namespace
     * @param version   the operator version
     * @param status    the operator status (Available, Progressing, etc.)
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record OperatorSummary(
        @JsonProperty("name") String name,
        @JsonProperty("namespace") String namespace,
        @JsonProperty("version") String version,
        @JsonProperty("status") String status
    ) {
    }

    /**
     * KafkaNodePool summary.
     *
     * @param name          the node pool name
     * @param roles         the node roles (broker, controller)
     * @param replicas      total replicas
     * @param readyReplicas ready replicas
     * @param readiness     the readiness status
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record NodePoolSummary(
        @JsonProperty("name") String name,
        @JsonProperty("roles") List<String> roles,
        @JsonProperty("replicas") Integer replicas,
        @JsonProperty("ready_replicas") Integer readyReplicas,
        @JsonProperty("readiness") String readiness
    ) {
    }

    /**
     * Count of resources with readiness breakdown.
     *
     * @param total    total resource count
     * @param ready    ready resources
     * @param notReady not-ready resources
     */
    public record ResourceCount(
        @JsonProperty("total") int total,
        @JsonProperty("ready") int ready,
        @JsonProperty("not_ready") int notReady
    ) {

        /**
         * Creates a resource count.
         *
         * @param total    total count
         * @param ready    ready count
         * @param notReady not-ready count
         * @return a new resource count
         */
        public static ResourceCount of(final int total, final int ready, final int notReady) {
            return new ResourceCount(total, ready, notReady);
        }
    }

    /**
     * Rebalance summary with state breakdown.
     *
     * @param total  total rebalance count
     * @param active active rebalances (New, PendingProposal, ProposalReady, Rebalancing, Stopped)
     * @param states count of rebalances per state
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RebalanceSummary(
        @JsonProperty("total") int total,
        @JsonProperty("active") int active,
        @JsonProperty("states") Map<String, Integer> states
    ) {
    }

    /**
     * KafkaConnect cluster summary.
     *
     * @param name           the Connect cluster name
     * @param namespace      the namespace
     * @param readiness      the readiness status
     * @param replicas       the replica count
     * @param connectorCount number of KafkaConnector resources deployed
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ConnectSummary(
        @JsonProperty("name") String name,
        @JsonProperty("namespace") String namespace,
        @JsonProperty("readiness") String readiness,
        @JsonProperty("replicas") Integer replicas,
        @JsonProperty("connector_count") int connectorCount
    ) {
    }

    /**
     * KafkaBridge summary.
     *
     * @param name      the bridge name
     * @param namespace the namespace
     * @param readiness the readiness status
     * @param replicas  the replica count
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record BridgeSummary(
        @JsonProperty("name") String name,
        @JsonProperty("namespace") String namespace,
        @JsonProperty("readiness") String readiness,
        @JsonProperty("replicas") Integer replicas
    ) {
    }

    /**
     * Drain Cleaner summary.
     *
     * @param name  the drain cleaner deployment name
     * @param ready whether the drain cleaner is ready
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DrainCleanerSummary(
        @JsonProperty("name") String name,
        @JsonProperty("ready") boolean ready
    ) {
    }
}
