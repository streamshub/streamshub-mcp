package io.strimzi.mcp.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Information about a Kafka topic.
 */
public record TopicInfo(
    @JsonProperty("name") String name,
    @JsonProperty("cluster") String cluster,
    @JsonProperty("partitions") Integer partitions,
    @JsonProperty("replicas") Integer replicas,
    @JsonProperty("status") String status
) {}