/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.tool;

import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.quarkiverse.mcp.server.WrapBusinessError;
import io.streamshub.mcp.common.dto.PodSummaryResponse;
import io.streamshub.mcp.common.guardrail.Guarded;
import io.streamshub.mcp.strimzi.config.StrimziToolsPrompts;
import io.streamshub.mcp.strimzi.dto.KafkaNodePoolResponse;
import io.streamshub.mcp.strimzi.service.KafkaNodePoolService;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.util.List;

/**
 * MCP tools for KafkaNodePool operations.
 */
@Singleton
@Guarded
@WrapBusinessError(Exception.class)
public class KafkaNodePoolTools {

    @Inject
    KafkaNodePoolService nodePoolService;

    KafkaNodePoolTools() {
    }

    /**
     * List KafkaNodePools for a cluster.
     *
     * @param clusterName the cluster name
     * @param namespace   optional namespace
     * @return list of node pool responses
     */
    @Tool(
        name = "list_kafka_node_pools",
        description = "List KafkaNodePools for a cluster showing roles, replicas, and storage configuration.",
        annotations = @Tool.Annotations(
            readOnlyHint = true,
            destructiveHint = false,
            idempotentHint = true,
            openWorldHint = false
        )
    )
    public List<KafkaNodePoolResponse> listKafkaNodePools(
        @ToolArg(
            description = StrimziToolsPrompts.CLUSTER_DESC
        ) final String clusterName,
        @ToolArg(
            description = StrimziToolsPrompts.NS_DESC,
            required = false
        ) final String namespace
    ) {
        return nodePoolService.listNodePools(namespace, clusterName);
    }

    /**
     * Get a specific KafkaNodePool.
     *
     * @param clusterName  the cluster name
     * @param nodePoolName the node pool name
     * @param namespace    optional namespace
     * @return the node pool response
     */
    @Tool(
        name = "get_kafka_node_pool",
        description = "Get detailed information about a specific KafkaNodePool including roles, replicas, and storage.",
        annotations = @Tool.Annotations(
            readOnlyHint = true,
            destructiveHint = false,
            idempotentHint = true,
            openWorldHint = false
        )
    )
    public KafkaNodePoolResponse getKafkaNodePool(
        @ToolArg(
            description = StrimziToolsPrompts.CLUSTER_DESC
        ) final String clusterName,
        @ToolArg(
            description = "Name of the node pool (e.g., 'kafka', 'controller')."
        ) final String nodePoolName,
        @ToolArg(
            description = StrimziToolsPrompts.NS_DESC,
            required = false
        ) final String namespace
    ) {
        return nodePoolService.getNodePool(namespace, clusterName, nodePoolName);
    }

    /**
     * Get pods for a KafkaNodePool.
     *
     * @param clusterName  the cluster name
     * @param nodePoolName the node pool name
     * @param namespace    optional namespace
     * @return list of pod info summaries
     */
    @Tool(
        name = "get_kafka_node_pool_pods",
        description = "Get pod summaries for a KafkaNodePool with phase, readiness, restarts, and age.",
        annotations = @Tool.Annotations(
            readOnlyHint = true,
            destructiveHint = false,
            idempotentHint = true,
            openWorldHint = false
        )
    )
    public List<PodSummaryResponse.PodInfo> getKafkaNodePoolPods(
        @ToolArg(
            description = StrimziToolsPrompts.CLUSTER_DESC
        ) final String clusterName,
        @ToolArg(
            description = "Name of the node pool (e.g., 'kafka', 'controller')."
        ) final String nodePoolName,
        @ToolArg(
            description = StrimziToolsPrompts.NS_DESC,
            required = false
        ) final String namespace
    ) {
        return nodePoolService.getNodePoolPods(namespace, clusterName, nodePoolName);
    }
}
