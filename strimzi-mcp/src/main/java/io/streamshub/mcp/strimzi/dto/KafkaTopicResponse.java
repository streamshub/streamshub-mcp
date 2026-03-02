/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Response containing Kafka topic resource information.
 * Avoids naming conflicts with Kubernetes API classes.
 *
 * @param name       the topic name
 * @param cluster    the Kafka cluster this topic belongs to
 * @param partitions the number of partitions
 * @param replicas   the number of replicas
 * @param status     the topic status
 */
public record KafkaTopicResponse(
    @JsonProperty("name") String name,
    @JsonProperty("cluster") String cluster,
    @JsonProperty("partitions") Integer partitions,
    @JsonProperty("replicas") Integer replicas,
    @JsonProperty("status") String status
) {
}
