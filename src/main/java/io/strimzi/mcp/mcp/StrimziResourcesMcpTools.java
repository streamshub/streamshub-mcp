/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.mcp.mcp;

import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.strimzi.mcp.config.StrimziAssistantPrompts;
import io.strimzi.mcp.exception.ToolError;
import io.strimzi.mcp.service.infra.KafkaBridgeService;
import io.strimzi.mcp.service.infra.KafkaConnectService;
import io.strimzi.mcp.service.infra.KafkaConnectorService;
import io.strimzi.mcp.service.infra.KafkaMirrorMaker2Service;
import io.strimzi.mcp.service.infra.KafkaNodePoolService;
import io.strimzi.mcp.service.infra.KafkaRebalanceService;
import io.strimzi.mcp.service.infra.KafkaUserService;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * MCP tools for Strimzi custom resource operations.
 * <p>
 * Handles discovery and listing of various Strimzi Kafka resources including
 * node pools, connectors, bridges, users, and other custom resources.
 */
@Singleton
public class StrimziResourcesMcpTools {

    StrimziResourcesMcpTools() {
    }

    @Inject
    KafkaNodePoolService nodePoolService;

    @Inject
    KafkaConnectService connectService;

    @Inject
    KafkaConnectorService connectorService;

    @Inject
    KafkaBridgeService bridgeService;

    @Inject
    KafkaUserService userService;

    @Inject
    KafkaMirrorMaker2Service mirrorMaker2Service;

    @Inject
    KafkaRebalanceService rebalanceService;

    /**
     * Discover and list Kafka node pools with replica and configuration info.
     *
     * @param namespace   namespace to search or null for auto-discovery
     * @param clusterName cluster name to filter by or null for all clusters
     * @return structured result with node pool information or error details
     */
    @Tool(
        name = "strimzi_kafka_node_pools",
        description = "Discover and list Kafka node pools with replica and configuration info. " +
            "Searches for KafkaNodePool custom resources and provides comprehensive information " +
            "about node pool configurations, including roles, replica counts, and status. " +
            "Perfect for understanding Kafka cluster node topology and scaling configuration." +
            StrimziAssistantPrompts.MCP_AUTO_DISCOVERY_GUIDANCE
    )
    public Object getKafkaNodePools(
        @ToolArg(description = StrimziAssistantPrompts.MCP_NAMESPACE_PARAM_DESC)
        String namespace,
        @ToolArg(description = StrimziAssistantPrompts.MCP_CLUSTER_NAME_PARAM_DESC)
        String clusterName
    ) {
        try {
            return nodePoolService.getKafkaNodePools(namespace, clusterName);
        } catch (Exception e) {
            return ToolError.of("Failed to retrieve Kafka node pools", e);
        }
    }

    /**
     * Discover and list Kafka Connect clusters with configuration and status info.
     *
     * @param namespace   namespace to search or null for auto-discovery
     * @param clusterName cluster name to filter by or null for all clusters
     * @return structured result with Connect cluster information or error details
     */
    @Tool(
        name = "strimzi_kafka_connect",
        description = "Discover and list Kafka Connect clusters with configuration and status info. " +
            "Searches for KafkaConnect custom resources and provides comprehensive information " +
            "about Connect cluster configurations, including replicas, version, REST API endpoints, and status. " +
            "Perfect for understanding Kafka Connect deployment topology and connectivity." +
            StrimziAssistantPrompts.MCP_AUTO_DISCOVERY_GUIDANCE
    )
    public Object getKafkaConnect(
        @ToolArg(description = StrimziAssistantPrompts.MCP_NAMESPACE_PARAM_DESC)
        String namespace,
        @ToolArg(description = StrimziAssistantPrompts.MCP_CLUSTER_NAME_PARAM_DESC)
        String clusterName
    ) {
        try {
            return connectService.getKafkaConnects(namespace, clusterName);
        } catch (Exception e) {
            return ToolError.of("Failed to retrieve Kafka Connect clusters", e);
        }
    }

    /**
     * Discover and list Kafka Connectors with configuration and runtime status.
     *
     * @param namespace      namespace to search or null for auto-discovery
     * @param connectCluster connect cluster name to filter by or null for all clusters
     * @return structured result with Connector information or error details
     */
    @Tool(
        name = "strimzi_kafka_connectors",
        description = "Discover and list Kafka Connectors with configuration and runtime status. " +
            "Searches for KafkaConnector custom resources and provides comprehensive information " +
            "about connector configurations, including connector class, tasks, state, and status. " +
            "Perfect for understanding Kafka Connect pipeline topology and connector health." +
            StrimziAssistantPrompts.MCP_AUTO_DISCOVERY_GUIDANCE
    )
    public Object getKafkaConnectors(
        @ToolArg(description = StrimziAssistantPrompts.MCP_NAMESPACE_PARAM_DESC)
        String namespace,
        @ToolArg(description = "Connect cluster name (e.g., 'my-connect-cluster') or null for all connect clusters. " +
            "Extract connect cluster name only if user specifically mentions it")
        String connectCluster
    ) {
        try {
            return connectorService.getKafkaConnectors(namespace, connectCluster);
        } catch (Exception e) {
            return ToolError.of("Failed to retrieve Kafka Connectors", e);
        }
    }

    /**
     * Discover and list Kafka Bridges with HTTP API endpoint information.
     *
     * @param namespace   namespace to search or null for auto-discovery
     * @param clusterName cluster name to filter by or null for all clusters
     * @return structured result with Bridge information or error details
     */
    @Tool(
        name = "strimzi_kafka_bridge",
        description = "Discover and list Kafka Bridges with HTTP API endpoint information. " +
            "Searches for KafkaBridge custom resources and provides comprehensive information " +
            "about HTTP bridge configurations, including API endpoints, port, and status. " +
            "Perfect for understanding Kafka HTTP API integration points." +
            StrimziAssistantPrompts.MCP_AUTO_DISCOVERY_GUIDANCE
    )
    public Object getKafkaBridge(
        @ToolArg(description = StrimziAssistantPrompts.MCP_NAMESPACE_PARAM_DESC)
        String namespace,
        @ToolArg(description = StrimziAssistantPrompts.MCP_CLUSTER_NAME_PARAM_DESC)
        String clusterName
    ) {
        try {
            return bridgeService.getKafkaBridges(namespace, clusterName);
        } catch (Exception e) {
            return ToolError.of("Failed to retrieve Kafka Bridges", e);
        }
    }

    /**
     * Discover and list Kafka Users with authentication and authorization info.
     *
     * @param namespace   namespace to search or null for auto-discovery
     * @param clusterName cluster name to filter by or null for all clusters
     * @return structured result with User information or error details
     */
    @Tool(
        name = "strimzi_kafka_users",
        description = "Discover and list Kafka Users with authentication and authorization info. " +
            "Searches for KafkaUser custom resources and provides comprehensive information " +
            "about user configurations, including authentication type, ACL rules, and status. " +
            "Perfect for understanding Kafka security and user management topology." +
            StrimziAssistantPrompts.MCP_AUTO_DISCOVERY_GUIDANCE
    )
    public Object getKafkaUsers(
        @ToolArg(description = StrimziAssistantPrompts.MCP_NAMESPACE_PARAM_DESC)
        String namespace,
        @ToolArg(description = StrimziAssistantPrompts.MCP_CLUSTER_NAME_PARAM_DESC)
        String clusterName
    ) {
        try {
            return userService.getKafkaUsers(namespace, clusterName);
        } catch (Exception e) {
            return ToolError.of("Failed to retrieve Kafka Users", e);
        }
    }

    /**
     * Discover and list Kafka MirrorMaker2 instances with cross-cluster replication info.
     *
     * @param namespace namespace to search or null for auto-discovery
     * @return structured result with MirrorMaker2 information or error details
     */
    @Tool(
        name = "strimzi_kafka_mirror_maker2",
        description = "Discover and list Kafka MirrorMaker2 instances with cross-cluster replication info. " +
            "Searches for KafkaMirrorMaker2 custom resources and provides comprehensive information " +
            "about replication configurations, including source/target clusters and status. " +
            "Perfect for understanding cross-cluster data replication topology." +
            StrimziAssistantPrompts.MCP_AUTO_DISCOVERY_GUIDANCE
    )
    public Object getKafkaMirrorMaker2(
        @ToolArg(description = StrimziAssistantPrompts.MCP_NAMESPACE_PARAM_DESC)
        String namespace
    ) {
        try {
            return mirrorMaker2Service.getKafkaMirrorMaker2(namespace);
        } catch (Exception e) {
            return ToolError.of("Failed to retrieve Kafka MirrorMaker2 instances", e);
        }
    }

    /**
     * Discover and list Kafka Rebalance operations with optimization goal info.
     *
     * @param namespace   namespace to search or null for auto-discovery
     * @param clusterName cluster name to filter by or null for all clusters
     * @return structured result with Rebalance information or error details
     */
    @Tool(
        name = "strimzi_kafka_rebalance",
        description = "Discover and list Kafka Rebalance operations with optimization goal info. " +
            "Searches for KafkaRebalance custom resources and provides comprehensive information " +
            "about rebalancing configurations, including goals, mode, session ID, and status. " +
            "Perfect for understanding cluster rebalancing operations and optimization." +
            StrimziAssistantPrompts.MCP_AUTO_DISCOVERY_GUIDANCE
    )
    public Object getKafkaRebalance(
        @ToolArg(description = StrimziAssistantPrompts.MCP_NAMESPACE_PARAM_DESC)
        String namespace,
        @ToolArg(description = StrimziAssistantPrompts.MCP_CLUSTER_NAME_PARAM_DESC)
        String clusterName
    ) {
        try {
            return rebalanceService.getKafkaRebalances(namespace, clusterName);
        } catch (Exception e) {
            return ToolError.of("Failed to retrieve Kafka Rebalance operations", e);
        }
    }
}