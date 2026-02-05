package io.strimzi.mcp.mcp;

import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.strimzi.mcp.service.StrimziOperatorService;
import io.strimzi.mcp.service.KafkaClusterService;
import io.strimzi.mcp.service.KafkaTopicService;
import io.strimzi.mcp.tool.ToolError;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * MCP tools for Strimzi operations.
 *
 * This class directly uses specialized services for focused operations,
 * providing clean separation of concerns and direct access to business logic.
 */
@Singleton
public class StrimziOperatorMcpTools {

    @Inject
    StrimziOperatorService operatorService;

    @Inject
    KafkaClusterService clusterService;

    @Inject
    KafkaTopicService topicService;

    @Tool(
        name = "strimzi_operator_logs",
        description = "Get comprehensive logs from Strimzi Kafka operator pods with error analysis and structured output. " +
                     "Retrieves recent logs from all operator pods, highlights errors/warnings, and provides health insights. " +
                     "Smart discovery: If namespace not specified, automatically searches all namespaces to find Strimzi operator. " +
                     "Namespace format: 'kafka', 'strimzi-system', 'default', etc."
    )
    public Object readStrimziOperatorLogs(
        @ToolArg(description = "Namespace where the Strimzi operator is deployed (optional - auto-discovered if not specified)")
        String namespace
    ) {
        try {
            return operatorService.getOperatorLogs(namespace);
        } catch (Exception e) {
            return ToolError.of("Failed to retrieve operator logs", e);
        }
    }

    @Tool(
        name = "strimzi_cluster_pods",
        description = "Get comprehensive status of all Kafka cluster pods with detailed component analysis. " +
                     "Retrieves information about brokers, ZooKeeper nodes, entity operators, and cluster operators. " +
                     "Provides health status, restart counts, and component breakdown. " +
                     "Smart discovery: If namespace not specified, automatically searches for Kafka clusters and suggests options. " +
                     "Leave clusterName empty to see all clusters in namespace."
    )
    public Object getKafkaClusterPods(
        @ToolArg(description = "Namespace of the Kafka cluster (optional - auto-discovered if not specified)")
        String namespace,
        @ToolArg(description = "Name of specific Kafka cluster to query (e.g., 'my-cluster') or empty/null for all clusters in namespace")
        String clusterName
    ) {
        try {
            return clusterService.getClusterPods(namespace, clusterName);
        } catch (Exception e) {
            return ToolError.of("Failed to retrieve cluster pods", e);
        }
    }

    @Tool(
        name = "strimzi_operator_status",
        description = "Get comprehensive Strimzi operator deployment status with health analysis and version information. " +
                     "Checks deployment health, replica status, version info, uptime, and provides actionable diagnostic insights. " +
                     "Smart discovery: If namespace not specified, automatically searches for Strimzi operator. " +
                     "Essential for troubleshooting operator issues."
    )
    public Object getOperatorStatus(
        @ToolArg(description = "Namespace where the Strimzi operator is deployed (optional - auto-discovered if not specified)")
        String namespace
    ) {
        try {
            return operatorService.getOperatorStatus(namespace);
        } catch (Exception e) {
            return ToolError.of("Failed to retrieve operator status", e);
        }
    }

    @Tool(
        name = "strimzi_kafka_clusters",
        description = "Discover and list all Kafka clusters with their status and configuration overview. " +
                     "Searches for Kafka custom resources and provides comprehensive information about all deployed clusters, " +
                     "including namespace, status, and basic configuration. Perfect for getting an overview of the Kafka landscape."
    )
    public Object getKafkaClusters(
        @ToolArg(description = "Namespace to search for clusters (optional - searches all namespaces if not specified)")
        String namespace
    ) {
        try {
            return clusterService.getKafkaClusters(namespace);
        } catch (Exception e) {
            return ToolError.of("Failed to retrieve Kafka clusters", e);
        }
    }

    @Tool(
        name = "strimzi_kafka_topics",
        description = "Get all Kafka topics for a cluster with detailed configuration including partitions, replicas, and status. " +
                     "Retrieves topic information from Strimzi KafkaTopic custom resources. " +
                     "Smart discovery: If namespace not specified, automatically searches for Strimzi installations. " +
                     "Shows topic health and configuration details."
    )
    public Object getKafkaTopics(
        @ToolArg(description = "Namespace of the Kafka cluster (optional - auto-discovered if not specified)")
        String namespace,
        @ToolArg(description = "Name of the Kafka cluster to query topics for (optional - shows all topics if not specified)")
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
                     "Smart discovery: If namespace not specified, automatically searches for Strimzi installations."
    )
    public Object getBootstrapServers(
        @ToolArg(description = "Namespace of the Kafka cluster (optional - auto-discovered if not specified)")
        String namespace,
        @ToolArg(description = "Name of the Kafka cluster to query (required, e.g., 'my-cluster')")
        String clusterName
    ) {
        try {
            return clusterService.getBootstrapServers(namespace, clusterName);
        } catch (Exception e) {
            return ToolError.of("Failed to get bootstrap servers", e);
        }
    }
}
