/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.tool;

import io.opentelemetry.instrumentation.annotations.WithSpan;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.quarkiverse.mcp.server.WrapBusinessError;
import io.streamshub.mcp.common.guardrail.Guarded;
import io.streamshub.mcp.strimzi.config.StrimziToolsPrompts;
import io.streamshub.mcp.strimzi.dto.KafkaTopicListResponse;
import io.streamshub.mcp.strimzi.dto.KafkaTopicResponse;
import io.streamshub.mcp.strimzi.service.KafkaTopicService;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * MCP tools for Kafka topic operations.
 */
@Singleton
@Guarded
@WrapBusinessError(Exception.class)
public class KafkaTopicTools {

    @Inject
    KafkaTopicService topicService;

    KafkaTopicTools() {
    }

    /**
     * List Kafka topics for a cluster with pagination.
     *
     * @param clusterName the cluster name
     * @param namespace   optional namespace
     * @param limit       optional max topics per page
     * @param offset      optional zero-based offset
     * @return paginated topic list response
     */
    @WithSpan("tool.list_kafka_topics")
    @Tool(
        name = "list_kafka_topics",
        description = "List Kafka topics for a cluster with partitions, replicas, and status."
            + " Returns paginated results (default 100 per page). Use offset to get more.",
        annotations = @Tool.Annotations(
            readOnlyHint = true,
            destructiveHint = false,
            idempotentHint = true,
            openWorldHint = false
        )
    )
    public KafkaTopicListResponse listKafkaTopics(
        @ToolArg(
            description = StrimziToolsPrompts.CLUSTER_DESC
        ) final String clusterName,
        @ToolArg(
            description = StrimziToolsPrompts.NS_DESC,
            required = false
        ) final String namespace,
        @ToolArg(
            description = "Maximum number of topics to return per page.",
            required = false
        ) final Integer limit,
        @ToolArg(
            description = "Zero-based offset for pagination (default: 0).",
            required = false
        ) final Integer offset
    ) {
        return topicService.listTopics(namespace, clusterName, offset, limit);
    }

    /**
     * Get details for a specific Kafka topic.
     *
     * @param clusterName the cluster name
     * @param topicName   the topic name
     * @param namespace   optional namespace
     * @return the topic response
     */
    @WithSpan("tool.get_kafka_topic")
    @Tool(
        name = "get_kafka_topic",
        description = "Get detailed information for a specific Kafka topic including"
            + " configuration, partitions, replicas, and status.",
        annotations = @Tool.Annotations(
            readOnlyHint = true,
            destructiveHint = false,
            idempotentHint = true,
            openWorldHint = false
        )
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
