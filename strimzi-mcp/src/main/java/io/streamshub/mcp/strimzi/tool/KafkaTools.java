/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.tool;

import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.quarkiverse.mcp.server.WrapBusinessError;
import io.streamshub.mcp.strimzi.config.StrimziToolsPrompts;
import io.streamshub.mcp.strimzi.dto.KafkaBootstrapResponse;
import io.streamshub.mcp.strimzi.dto.KafkaClusterPodsResponse;
import io.streamshub.mcp.strimzi.dto.KafkaClusterResponse;
import io.streamshub.mcp.strimzi.service.KafkaService;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.util.List;

/**
 * MCP tools for Kafka cluster operations.
 */
@Singleton
@WrapBusinessError(Exception.class)
public class KafkaTools {

    @Inject
    KafkaService kafkaService;

    KafkaTools() {
    }

    /**
     * List Kafka clusters.
     *
     * @param namespace optional namespace filter
     * @return list of cluster responses
     */
    @Tool(
        name = "list_kafka_clusters",
        description = "List Kafka clusters with status"
            + " and configuration."
            + " Optionally filter by namespace."
    )
    public List<KafkaClusterResponse> listKafkaClusters(
        @ToolArg(
            description = StrimziToolsPrompts.NS_DESC,
            required = false
        ) final String namespace
    ) {
        return kafkaService.listClusters(namespace);
    }

    /**
     * Get a specific Kafka cluster.
     *
     * @param clusterName the cluster name
     * @param namespace   optional namespace
     * @return the cluster response
     */
    @Tool(
        name = "get_kafka_cluster",
        description = "Get detailed information about"
            + " a specific Kafka cluster including"
            + " status, version, and configuration."
    )
    public KafkaClusterResponse getKafkaCluster(
        @ToolArg(
            description = StrimziToolsPrompts.CLUSTER_DESC
        ) final String clusterName,
        @ToolArg(
            description = StrimziToolsPrompts.NS_DESC,
            required = false
        ) final String namespace
    ) {
        return kafkaService.getCluster(namespace, clusterName);
    }

    /**
     * Get pods for a Kafka cluster.
     *
     * @param clusterName the cluster name
     * @param namespace   optional namespace
     * @return the cluster pods response
     */
    @Tool(
        name = "get_kafka_cluster_pods",
        description = "Get pod summaries for a Kafka"
            + " cluster with component type, phase,"
            + " readiness, restarts, and age."
    )
    public KafkaClusterPodsResponse getKafkaClusterPods(
        @ToolArg(
            description = StrimziToolsPrompts.CLUSTER_DESC
        ) final String clusterName,
        @ToolArg(
            description = StrimziToolsPrompts.NS_DESC,
            required = false
        ) final String namespace
    ) {
        return kafkaService.getClusterPods(
            namespace, clusterName
        );
    }

    /**
     * Get bootstrap server addresses for a Kafka cluster.
     *
     * @param clusterName the cluster name
     * @param namespace   optional namespace
     * @return the bootstrap response with listener addresses
     */
    @Tool(
        name = "get_kafka_bootstrap_servers",
        description = "Get bootstrap server addresses for a Kafka cluster"
            + " with listener names, types, hosts, and ports."
    )
    public KafkaBootstrapResponse getKafkaBootstrapServers(
        @ToolArg(
            description = StrimziToolsPrompts.CLUSTER_DESC
        ) final String clusterName,
        @ToolArg(
            description = StrimziToolsPrompts.NS_DESC,
            required = false
        ) final String namespace
    ) {
        return kafkaService.getBootstrapServers(namespace, clusterName);
    }
}
