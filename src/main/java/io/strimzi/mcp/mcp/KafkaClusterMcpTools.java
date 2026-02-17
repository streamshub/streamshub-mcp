/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.mcp.mcp;

import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.strimzi.mcp.config.StrimziAssistantPrompts;
import io.strimzi.mcp.exception.ToolError;
import io.strimzi.mcp.service.infra.KafkaClusterService;
import io.strimzi.mcp.service.infra.KafkaTopicService;
import io.strimzi.mcp.service.infra.PodsService;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * MCP tools for Kafka cluster operations.
 * <p>
 * Handles cluster discovery, pod management, topics, and bootstrap servers.
 */
@Singleton
public class KafkaClusterMcpTools {

    KafkaClusterMcpTools() {
    }

    @Inject
    KafkaClusterService clusterService;

    @Inject
    KafkaTopicService topicService;

    @Inject
    PodsService podsService;

    /**
     * Discover and list all Kafka clusters with their status and configuration overview.
     *
     * @param namespace namespace to search or null for auto-discovery
     * @return structured result with Kafka clusters or error details
     */
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

    /**
     * Get lightweight summaries of all Kafka cluster pods with component analysis.
     *
     * @param namespace   namespace to search or null for auto-discovery
     * @param clusterName name of the specific cluster or null for all clusters
     * @return structured result with pod summaries or error details
     */
    @Tool(
        name = "strimzi_cluster_pods",
        description = "Get lightweight summaries of all Kafka cluster pods with component analysis. " +
            "Returns name, phase, ready, component, restarts, and age for each pod. " +
            "Use strimzi_cluster_pod_describe with sections parameter for detailed info on a specific pod." +
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

    /**
     * Get all Kafka topics for a cluster with detailed configuration.
     *
     * @param namespace   namespace to search or null for auto-discovery
     * @param clusterName name of the Kafka cluster or null for all clusters
     * @return structured result with topic details or error details
     */
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

    /**
     * Get Kafka bootstrap servers (connection endpoints) from Kafka Custom Resource.
     *
     * @param namespace   namespace to search or null for auto-discovery
     * @param clusterName name of Kafka cluster to query
     * @return structured result with bootstrap server endpoints or error details
     */
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

    /**
     * Get detailed description of a Kafka cluster pod.
     *
     * @param namespace namespace where the pod is deployed or null for auto-discovery
     * @param podName   name of the specific pod to describe
     * @param sections  comma-separated detail sections to include
     * @return structured pod description or error details
     */
    @Tool(
        name = "strimzi_cluster_pod_describe",
        description = "Get detailed description of a Kafka cluster pod including environment variables, " +
            "container specs, resource requests/limits, volume mounts, and node placement. " +
            "Use this to drill down from cluster pod summaries (strimzi_cluster_pods) into detailed pod info." +
            StrimziAssistantPrompts.MCP_AUTO_DISCOVERY_GUIDANCE
    )
    public Object describePod(
        @ToolArg(description = StrimziAssistantPrompts.MCP_NAMESPACE_PARAM_DESC)
        String namespace,
        @ToolArg(description = "Name of a specific pod to describe (e.g., 'my-cluster-kafka-0'). Required.")
        String podName,
        @ToolArg(description = StrimziAssistantPrompts.MCP_SECTIONS_PARAM_DESC)
        String sections
    ) {
        try {
            return podsService.describePod(namespace, podName, sections);
        } catch (Exception e) {
            return ToolError.of("Failed to describe pod", e);
        }
    }
}
