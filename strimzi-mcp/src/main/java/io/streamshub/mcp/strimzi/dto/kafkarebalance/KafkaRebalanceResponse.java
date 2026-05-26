/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.dto.kafkarebalance;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.streamshub.mcp.common.dto.ConditionInfo;

import java.util.List;
import java.util.Map;
/**
 * Response containing KafkaRebalance information.
 * Used for both list (summary) and get (detail) operations.
 *
 * @param name               the KafkaRebalance resource name
 * @param namespace           the Kubernetes namespace
 * @param cluster             the Kafka cluster name from the strimzi.io/cluster label
 * @param state               the rebalance state extracted from conditions
 * @param mode                the rebalance mode (full, add-brokers, remove-brokers, remove-disks)
 * @param autoApproval        whether auto-approval is enabled via annotation
 * @param sessionId           the Cruise Control session ID
 * @param optimizationResult  key metrics from the optimization result (null if no result)
 * @param spec                rebalance spec details (null for list operations)
 * @param progressConfigMap   name of the ConfigMap with progress info (null for list operations)
 * @param conditions          the list of status conditions
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record KafkaRebalanceResponse(
    @JsonProperty("name") String name,
    @JsonProperty("namespace") String namespace,
    @JsonProperty("cluster") String cluster,
    @JsonProperty("state") String state,
    @JsonProperty("mode") String mode,
    @JsonProperty("auto_approval") Boolean autoApproval,
    @JsonProperty("session_id") String sessionId,
    @JsonProperty("optimization_result") OptimizationResultInfo optimizationResult,
    @JsonProperty("spec") RebalanceSpecInfo spec,
    @JsonProperty("progress_config_map") String progressConfigMap,
    @JsonProperty("conditions") List<ConditionInfo> conditions
) {

    /**
     * Key metrics extracted from the Cruise Control optimization result.
     *
     * @param dataToMoveMb                     estimated data to move in MB
     * @param numReplicaMovements              number of partition replica movements
     * @param numLeaderMovements               number of leader movements
     * @param numIntraBrokerReplicaMovements   number of intra-broker replica movements
     * @param monitoredPartitionsPercentage     percentage of partitions monitored
     * @param balancednessScoreBefore          cluster balancedness score before rebalance
     * @param balancednessScoreAfter           cluster balancedness score after rebalance
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record OptimizationResultInfo(
        @JsonProperty("data_to_move_mb") Long dataToMoveMb,
        @JsonProperty("num_replica_movements") Integer numReplicaMovements,
        @JsonProperty("num_leader_movements") Integer numLeaderMovements,
        @JsonProperty("num_intra_broker_replica_movements") Integer numIntraBrokerReplicaMovements,
        @JsonProperty("monitored_partitions_percentage") Double monitoredPartitionsPercentage,
        @JsonProperty("balancedness_score_before") Double balancednessScoreBefore,
        @JsonProperty("balancedness_score_after") Double balancednessScoreAfter
    ) {

        /**
         * Extracts key metrics from the raw optimization result map.
         *
         * @param map the raw optimization result from KafkaRebalanceStatus
         * @return extracted metrics, or null if the map is null or empty
         */
        public static OptimizationResultInfo fromMap(final Map<String, Object> map) {
            if (map == null || map.isEmpty()) {
                return null;
            }
            return new OptimizationResultInfo(
                extractLong(map, "dataToMoveMB"),
                extractInt(map, "numReplicaMovements"),
                extractInt(map, "numLeaderMovements"),
                extractInt(map, "numIntraBrokerReplicaMovements"),
                extractDouble(map, "monitoredPartitionsPercentage"),
                extractDouble(map, "onDemandBalancednessScoreBefore"),
                extractDouble(map, "onDemandBalancednessScoreAfter"));
        }

        private static Long extractLong(final Map<String, Object> map, final String key) {
            Object val = map.get(key);
            return val instanceof Number n ? n.longValue() : null;
        }

        private static Integer extractInt(final Map<String, Object> map, final String key) {
            Object val = map.get(key);
            return val instanceof Number n ? n.intValue() : null;
        }

        private static Double extractDouble(final Map<String, Object> map, final String key) {
            Object val = map.get(key);
            return val instanceof Number n ? n.doubleValue() : null;
        }
    }

    /**
     * Rebalance spec details shown only in get (detail) operations.
     *
     * @param mode                                  the rebalance mode
     * @param brokers                               broker IDs for add/remove operations
     * @param goals                                 optimization goals
     * @param skipHardGoalCheck                     whether hard goals can be skipped
     * @param rebalanceDisk                         whether intra-broker disk balancing is enabled
     * @param excludedTopics                        regex for excluded topics
     * @param concurrentPartitionMovementsPerBroker max concurrent partition movements per broker
     * @param concurrentIntraBrokerPartitionMovements max concurrent intra-broker movements
     * @param concurrentLeaderMovements             max concurrent leader movements
     * @param replicationThrottle                   bandwidth limit in bytes/sec
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RebalanceSpecInfo(
        @JsonProperty("mode") String mode,
        @JsonProperty("brokers") List<Integer> brokers,
        @JsonProperty("goals") List<String> goals,
        @JsonProperty("skip_hard_goal_check") Boolean skipHardGoalCheck,
        @JsonProperty("rebalance_disk") Boolean rebalanceDisk,
        @JsonProperty("excluded_topics") String excludedTopics,
        @JsonProperty("concurrent_partition_movements_per_broker") Integer concurrentPartitionMovementsPerBroker,
        @JsonProperty("concurrent_intra_broker_partition_movements") Integer concurrentIntraBrokerPartitionMovements,
        @JsonProperty("concurrent_leader_movements") Integer concurrentLeaderMovements,
        @JsonProperty("replication_throttle") Long replicationThrottle
    ) {
    }

    /**
     * Creates a summary response for list operations (no spec or progress details).
     *
     * @param name               the rebalance name
     * @param namespace          the namespace
     * @param cluster            the cluster name
     * @param state              the rebalance state
     * @param mode               the rebalance mode
     * @param autoApproval       auto-approval status
     * @param sessionId          the session ID
     * @param optimizationResult the optimization metrics
     * @param conditions         the status conditions
     * @return a summary response
     */
    @SuppressWarnings("checkstyle:ParameterNumber")
    public static KafkaRebalanceResponse summary(final String name, final String namespace,
                                                  final String cluster, final String state,
                                                  final String mode, final Boolean autoApproval,
                                                  final String sessionId,
                                                  final OptimizationResultInfo optimizationResult,
                                                  final List<ConditionInfo> conditions) {
        return new KafkaRebalanceResponse(name, namespace, cluster, state, mode,
            autoApproval, sessionId, optimizationResult, null, null, conditions);
    }

    /**
     * Creates a detailed response for get operations with all fields.
     *
     * @param name               the rebalance name
     * @param namespace          the namespace
     * @param cluster            the cluster name
     * @param state              the rebalance state
     * @param mode               the rebalance mode
     * @param autoApproval       auto-approval status
     * @param sessionId          the session ID
     * @param optimizationResult the optimization metrics
     * @param spec               the rebalance spec details
     * @param progressConfigMap  the progress ConfigMap name
     * @param conditions         the status conditions
     * @return a detailed response
     */
    @SuppressWarnings("checkstyle:ParameterNumber")
    public static KafkaRebalanceResponse of(final String name, final String namespace,
                                             final String cluster, final String state,
                                             final String mode, final Boolean autoApproval,
                                             final String sessionId,
                                             final OptimizationResultInfo optimizationResult,
                                             final RebalanceSpecInfo spec,
                                             final String progressConfigMap,
                                             final List<ConditionInfo> conditions) {
        return new KafkaRebalanceResponse(name, namespace, cluster, state, mode,
            autoApproval, sessionId, optimizationResult, spec, progressConfigMap, conditions);
    }
}
