/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.dto.kafkatopic;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;
/**
 * Response containing Kafka topic resource information.
 * Avoids naming conflicts with Kubernetes API classes.
 *
 * @param name          the topic name
 * @param cluster       the Kafka cluster this topic belongs to
 * @param partitions    the number of partitions
 * @param replicas      the number of replicas
 * @param status        the topic status
 * @param configuration the topic configuration
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record KafkaTopicResponse(
    @JsonProperty("name") String name,
    @JsonProperty("cluster") String cluster,
    @JsonProperty("partitions") Integer partitions,
    @JsonProperty("replicas") Integer replicas,
    @JsonProperty("status") String status,
    @JsonProperty("configuration") Map<String, Object> configuration
) {

    /**
     * Creates a topic response with the given fields.
     *
     * @param name          the topic name
     * @param cluster       the Kafka cluster name
     * @param partitions    the number of partitions
     * @param replicas      the number of replicas
     * @param status        the topic status
     * @param configuration the topic configuration
     * @return a new topic response
     */
    public static KafkaTopicResponse of(String name, String cluster,
                                         Integer partitions, Integer replicas,
                                         String status, Map<String, Object> configuration) {
        return new KafkaTopicResponse(name, cluster, partitions, replicas, status, configuration);
    }
}