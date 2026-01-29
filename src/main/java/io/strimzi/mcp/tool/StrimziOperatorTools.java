package io.strimzi.mcp.tool;

import dev.langchain4j.agent.tool.Tool;
import io.strimzi.mcp.dto.*;
import io.strimzi.mcp.service.StrimziOperatorService;
import io.strimzi.mcp.service.KafkaClusterService;
import io.strimzi.mcp.service.KafkaTopicService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * LangChain4J tools for Strimzi operations.
 *
 * This class directly uses specialized services for focused operations,
 * providing clean separation of concerns and direct access to business logic.
 */
@ApplicationScoped
public class StrimziOperatorTools {

    @Inject
    StrimziOperatorService operatorService;

    @Inject
    KafkaClusterService clusterService;

    @Inject
    KafkaTopicService topicService;

    /**
     * Get comprehensive logs from the Strimzi Kafka operator pods with error analysis.
     *
     * Retrieves recent logs from all Strimzi operator pods, automatically highlights
     * errors and warnings, and provides structured analysis of operator health.
     *
     * Smart namespace discovery: If namespace is not specified, automatically searches
     * across all namespaces to find the Strimzi operator. If multiple operators found,
     * prompts user to be specific.
     *
     * @param namespace namespace where the operator is deployed (optional - auto-discovered if not specified)
     * @return structured logs result with error analysis and pod status
     */
    @Tool("Get comprehensive logs from Strimzi operator pods with error analysis. Auto-discovers operator namespace if not specified.")
    public OperatorLogsResult readLogsFromOperator(String namespace) {
        return operatorService.getOperatorLogs(namespace);
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
     * Get comprehensive Strimzi operator deployment status and health information.
     *
     * Checks the deployment status of the Strimzi cluster operator, including
     * replica health, version information, uptime, and overall operational status.
     * Provides actionable insights for troubleshooting operator issues.
     *
     * Smart discovery: If namespace not specified, automatically searches for
     * Strimzi operator across all namespaces.
     *
     * @param namespace namespace where the operator is deployed (optional - auto-discovered if not specified)
     * @return structured status result with deployment health, version info, and diagnostic messages
     */
    @Tool("Get Strimzi operator deployment status with health analysis. Auto-discovers operator namespace if not specified.")
    public OperatorStatusResult getOperatorStatus(String namespace) {
        return operatorService.getOperatorStatus(namespace);
    }

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
}
