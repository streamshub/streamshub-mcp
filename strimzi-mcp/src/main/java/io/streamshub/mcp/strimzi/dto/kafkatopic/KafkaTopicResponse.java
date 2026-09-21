/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.dto.kafkatopic;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.streamshub.mcp.common.dto.ConditionInfo;
import io.streamshub.mcp.common.dto.ReconciliationInfo;

import java.util.List;
import java.util.Map;
/**
 * Response containing Kafka topic resource information.
 * Avoids naming conflicts with Kubernetes API classes.
 *
 * @param name           the topic resource name
 * @param namespace      the Kubernetes namespace
 * @param cluster        the Kafka cluster this topic belongs to
 * @param partitions     the number of partitions
 * @param replicas       the number of replicas
 * @param status         the topic status (Ready, NotReady, etc.)
 * @param topicId        the Kafka topic ID from status
 * @param topicName      the actual Kafka topic name from status
 * @param replicasChange ongoing or pending replication factor changes
 * @param conditions     the status conditions
 * @param configuration  the topic configuration
 * @param reconciliation reconciliation status tracking (generation vs observedGeneration)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record KafkaTopicResponse(
    @JsonProperty("name") String name,
    @JsonProperty("namespace") String namespace,
    @JsonProperty("cluster") String cluster,
    @JsonProperty("partitions") Integer partitions,
    @JsonProperty("replicas") Integer replicas,
    @JsonProperty("status") String status,
    @JsonProperty("topic_id") String topicId,
    @JsonProperty("topic_name") String topicName,
    @JsonProperty("replicas_change") ReplicasChangeInfo replicasChange,
    @JsonProperty("conditions") List<ConditionInfo> conditions,
    @JsonProperty("configuration") Map<String, Object> configuration,
    @JsonProperty("reconciliation") ReconciliationInfo reconciliation
) {

    /**
     * Replication factor change details from status.
     *
     * @param targetReplicas requested target replicas
     * @param state          state of change (e.g. pending, ongoing)
     * @param sessionId      session ID for change request
     * @param message        descriptive message or transient error
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ReplicasChangeInfo(
        @JsonProperty("target_replicas") Integer targetReplicas,
        @JsonProperty("state") String state,
        @JsonProperty("session_id") String sessionId,
        @JsonProperty("message") String message
    ) {
    }

    /**
     * Creates a topic response with the given fields.
     *
     * @param name           the topic name
     * @param namespace      the Kubernetes namespace
     * @param cluster        the Kafka cluster name
     * @param partitions     the number of partitions
     * @param replicas       the number of replicas
     * @param status         the topic status
     * @param topicId        the topic ID
     * @param topicName      the actual topic name
     * @param replicasChange ongoing replication change status
     * @param conditions     the status conditions
     * @param configuration  the topic configuration
     * @param reconciliation reconciliation status tracking
     * @return a new topic response
     */
    @SuppressWarnings("checkstyle:ParameterNumber")
    public static KafkaTopicResponse of(String name, String namespace, String cluster,
                                         Integer partitions, Integer replicas,
                                         String status, String topicId, String topicName,
                                         ReplicasChangeInfo replicasChange,
                                         List<ConditionInfo> conditions,
                                         Map<String, Object> configuration,
                                         ReconciliationInfo reconciliation) {
        return new KafkaTopicResponse(name, namespace, cluster, partitions, replicas,
            status, topicId, topicName, replicasChange, conditions, configuration, reconciliation);
    }
}