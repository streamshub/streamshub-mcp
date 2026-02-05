package io.strimzi.mcp.mcp;

import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.strimzi.mcp.config.StrimziAssistantPrompts;
import io.strimzi.mcp.service.infra.KafkaClusterService;
import io.strimzi.mcp.service.infra.KafkaTopicService;
import io.strimzi.mcp.exception.ToolError;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * MCP tools for Kafka cluster operations.
 *
 * Handles cluster discovery, pod management, topics, and bootstrap servers.
 */
@Singleton
public class KafkaClusterMcpTools {

    @Inject
    KafkaClusterService clusterService;

    @Inject
    KafkaTopicService topicService;

    @Tool(
        name = "strimzi_kafka_clusters",
        description = "Discover and list all Kafka clusters with their status and configuration overview. " +
                     "Searches for Kafka custom resources and provides comprehensive information about all deployed clusters, " +
                     "including namespace, status, and basic configuration. " +
                     "Perfect for getting overview of Kafka landscape across all namespaces." +
                     StrimziAssistantPrompts.MCP_AUTO_DISCOVERY_GUIDANCE
    )
    public Object getKafkaClusters(
        @ToolArg(description = StrimziAssistantPrompts.MCP_NAMESPACE_PARAM_DESC)
        String namespace
    ) {
        try {
            return clusterService.getKafkaClusters(namespace);
        } catch (Exception e) {
            return ToolError.of("Failed to retrieve Kafka clusters", e);
        }
    }

    @Tool(
        name = "strimzi_cluster_pods",
        description = "Get comprehensive status of all Kafka cluster pods with detailed component analysis. " +
                     "Retrieves information about brokers, ZooKeeper nodes, entity operators, and cluster operators. " +
                     "Provides health status, restart counts, and component breakdown." +
                     StrimziAssistantPrompts.MCP_AUTO_DISCOVERY_GUIDANCE
    )
    public Object getKafkaClusterPods(
        @ToolArg(description = StrimziAssistantPrompts.MCP_NAMESPACE_PARAM_DESC)
        String namespace,
        @ToolArg(description = StrimziAssistantPrompts.MCP_CLUSTER_NAME_PARAM_DESC)
        String clusterName
    ) {
        try {
            return clusterService.getClusterPods(namespace, clusterName);
        } catch (Exception e) {
            return ToolError.of("Failed to retrieve cluster pods", e);
        }
    }

    @Tool(
        name = "strimzi_kafka_topics",
        description = "Get all Kafka topics for a cluster with detailed configuration including partitions, replicas, and status. " +
                     "Retrieves topic information from Strimzi KafkaTopic custom resources. " +
                     "Shows topic health and configuration details." +
                     StrimziAssistantPrompts.MCP_AUTO_DISCOVERY_GUIDANCE
    )
    public Object getKafkaTopics(
        @ToolArg(description = StrimziAssistantPrompts.MCP_NAMESPACE_PARAM_DESC)
        String namespace,
        @ToolArg(description = StrimziAssistantPrompts.MCP_CLUSTER_NAME_PARAM_DESC)
        String clusterName
    ) {
        try {
            return topicService.getKafkaTopics(namespace, clusterName);
        } catch (Exception e) {
            return ToolError.of("Failed to retrieve Kafka topics", e);
        }
    }

    @Tool(
        name = "strimzi_bootstrap_servers",
        description = "Get Kafka bootstrap servers (connection endpoints) from Kafka Custom Resource. " +
                     "Extracts all available listener addresses and ports for client connections. " +
                     "Returns bootstrap server URLs for internal, external, and other configured listeners. " +
                     "ClusterName is REQUIRED - extract from user request or get from cluster discovery first." +
                     StrimziAssistantPrompts.MCP_AUTO_DISCOVERY_GUIDANCE
    )
    public Object getBootstrapServers(
        @ToolArg(description = StrimziAssistantPrompts.MCP_NAMESPACE_PARAM_DESC)
        String namespace,
        @ToolArg(description = "Name of Kafka cluster to query (REQUIRED, e.g., 'my-cluster'). Must be extracted from user request or obtained from cluster discovery first")
        String clusterName
    ) {
        try {
            return clusterService.getBootstrapServers(namespace, clusterName);
        } catch (Exception e) {
            return ToolError.of("Failed to get bootstrap servers", e);
        }
    }
}