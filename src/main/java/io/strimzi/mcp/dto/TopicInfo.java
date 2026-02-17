/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.mcp.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Information about a Kafka topic.
 *
 * @param name       the topic name
 * @param cluster    the Kafka cluster this topic belongs to
 * @param partitions the number of partitions
 * @param replicas   the number of replicas
 * @param status     the topic status
 */
public record TopicInfo(
    @JsonProperty("name") String name,
    @JsonProperty("cluster") String cluster,
    @JsonProperty("partitions") Integer partitions,
    @JsonProperty("replicas") Integer replicas,
    @JsonProperty("status") String status
) {
}
