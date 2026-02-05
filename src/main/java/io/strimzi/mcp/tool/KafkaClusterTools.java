package io.strimzi.mcp.tool;

import dev.langchain4j.agent.tool.Tool;
import io.strimzi.mcp.dto.BootstrapServersResult;
import io.strimzi.mcp.dto.ClusterPodsResult;
import io.strimzi.mcp.dto.KafkaClustersResult;
import io.strimzi.mcp.dto.KafkaTopicsResult;
import io.strimzi.mcp.service.infra.KafkaClusterService;
import io.strimzi.mcp.service.infra.KafkaTopicService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * LangChain4J tools for Kafka cluster operations.
 *
 * Handles cluster discovery, pod management, topics, and bootstrap servers.
 */
@ApplicationScoped
public class KafkaClusterTools {

    @Inject
    KafkaClusterService clusterService;

    @Inject
    KafkaTopicService topicService;

    /**
     * Discover and list all Kafka clusters across namespaces with their status.
     *
     * Searches for Kafka custom resources and provides comprehensive information
     * about all deployed Kafka clusters, including their namespace, status, and
     * configuration overview. Useful for getting an overview of the entire Kafka landscape.
     *
     * @param namespace namespace to search (optional - searches all namespaces if not specified)
     * @return structured result with all Kafka clusters and their status
     */
    @Tool("Discover and list all Kafka clusters with status information. Searches all namespaces if not specified.")
    public KafkaClustersResult getKafkaClusters(String namespace) {
        return clusterService.getKafkaClusters(namespace);
    }

    /**
     * Get comprehensive status of all Kafka cluster pods including operators and components.
     *
     * Retrieves detailed information about all pods related to Kafka clusters,
     * including brokers, ZooKeeper nodes, entity operators, and cluster operators.
     * Provides health status, restart counts, and component breakdown.
     *
     * Smart discovery: If namespace not specified, automatically searches for Kafka
     * clusters across all namespaces and suggests specific clusters found.
     *
     * @param namespace namespace of the Kafka cluster (optional - auto-discovered if not specified)
     * @param clusterName name of the specific cluster to query, or null/empty for all clusters in namespace
     * @return structured result with pod details, health status, and component analysis
     */
    @Tool("Get comprehensive status of Kafka cluster pods with health analysis. Auto-discovers clusters if namespace not specified.")
    public ClusterPodsResult getKafkaClusterPods(String namespace, String clusterName) {
        return clusterService.getClusterPods(namespace, clusterName);
    }

    /**
     * Get Kafka topics for a specific cluster with detailed configuration.
     *
     * Retrieves all Kafka topics for a specified cluster, including partition count,
     * replication factor, and topic status. Shows topic health and configuration
     * details from Strimzi KafkaTopic custom resources.
     *
     * Smart discovery: If namespace not specified, automatically searches for
     * Strimzi installations and suggests available clusters.
     *
     * @param namespace namespace of the Kafka cluster (optional - auto-discovered if not specified)
     * @param clusterName name of the Kafka cluster to query topics for (optional - shows all topics if not specified)
     * @return structured result with topic details, partitions, replicas, and status
     */
    @Tool("Get Kafka topics for a cluster with configuration details. Auto-discovers clusters if namespace not specified.")
    public KafkaTopicsResult getKafkaTopics(String namespace, String clusterName) {
        return topicService.getKafkaTopics(namespace, clusterName);
    }

    /**
     * Get Kafka bootstrap servers (connection endpoints) from Kafka Custom Resource.
     *
     * Extracts all available listener addresses and ports for client connections from
     * the Kafka Custom Resource status. Returns bootstrap server URLs for internal,
     * external, and other configured listeners that clients can use to connect.
     *
     * Smart discovery: If namespace not specified, automatically searches for
     * Strimzi installations across all namespaces.
     *
     * @param namespace namespace of the Kafka cluster (optional - auto-discovered if not specified)
     * @param clusterName name of the Kafka cluster to query (required, e.g., 'my-cluster')
     * @return structured result with bootstrap server endpoints, listeners, and connection details
     */
    @Tool("Get Kafka bootstrap servers for client connections. Extracts listener endpoints from Kafka Custom Resource.")
    public BootstrapServersResult getBootstrapServers(String namespace, String clusterName) {
        return clusterService.getBootstrapServers(namespace, clusterName);
    }
}