/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.tool;

import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.quarkiverse.mcp.server.WrapBusinessError;
import io.streamshub.mcp.strimzi.config.StrimziToolsPrompts;
import io.streamshub.mcp.strimzi.dto.KafkaTopicResponse;
import io.streamshub.mcp.strimzi.service.KafkaTopicService;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.util.List;

/**
 * MCP tools for Kafka topic operations.
 */
@Singleton
@WrapBusinessError(Exception.class)
public class KafkaTopicTools {

    @Inject
    KafkaTopicService topicService;

    KafkaTopicTools() {
    }

    /**
     * List Kafka topics for a cluster.
     *
     * @param clusterName the cluster name
     * @param namespace   optional namespace
     * @return list of topic responses
     */
    @Tool(
        name = "list_kafka_topics",
        description = "List Kafka topics for a cluster with partitions, replicas, and status."
            + " Optionally filter by namespace."
    )
    public List<KafkaTopicResponse> listKafkaTopics(
        @ToolArg(
            description = StrimziToolsPrompts.CLUSTER_DESC
        ) final String clusterName,
        @ToolArg(
            description = StrimziToolsPrompts.NS_DESC,
            required = false
        ) final String namespace
    ) {
        return topicService.listTopics(namespace, clusterName);
    }

    /**
     * Get details for a specific Kafka topic.
     *
     * @param clusterName the cluster name
     * @param topicName   the topic name
     * @param namespace   optional namespace
     * @return the topic response
     */
    @Tool(
        name = "get_kafka_topic",
        description = "Get detailed information for a specific Kafka topic including"
            + " configuration, partitions, replicas, and status."
    )
    public KafkaTopicResponse getKafkaTopic(
        @ToolArg(
            description = StrimziToolsPrompts.CLUSTER_DESC
        ) final String clusterName,
        @ToolArg(
            description = "Name of the topic (e.g., 'user-events')."
        ) final String topicName,
        @ToolArg(
            description = StrimziToolsPrompts.NS_DESC,
            required = false
        ) final String namespace
    ) {
        return topicService.getTopic(namespace, clusterName, topicName);
    }
}
