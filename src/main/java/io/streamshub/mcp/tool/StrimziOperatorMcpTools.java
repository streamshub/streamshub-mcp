/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.tool;

import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.streamshub.mcp.config.StrimziToolsPrompts;
import io.streamshub.mcp.dto.ToolError;
import io.streamshub.mcp.service.common.PodsService;
import io.streamshub.mcp.service.infra.StrimziOperatorService;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * MCP tools for Strimzi operator operations.
 * Handles operator logs, status, health monitoring, and pod description.
 */
@Singleton
public class StrimziOperatorMcpTools {

    @Inject
    StrimziOperatorService operatorService;
    @Inject
    PodsService podsService;

    StrimziOperatorMcpTools() {
    }

    /**
     * Get comprehensive logs from Strimzi Kafka operator pods with error analysis.
     *
     * @param namespace namespace where the operator is deployed or null for auto-discovery
     * @return structured logs result with error analysis or error details
     */
    @Tool(
        name = "strimzi_operator_logs",
        description = "Get comprehensive logs from Strimzi Kafka operator pods with error analysis and structured output. " +
            "Retrieves recent logs from all operator pods, highlights errors/warnings, and provides health insights." +
            StrimziToolsPrompts.MCP_AUTO_DISCOVERY_GUIDANCE
    )
    public Object readStrimziOperatorLogs(
        @ToolArg(description = StrimziToolsPrompts.MCP_NAMESPACE_PARAM_DESC)
        String namespace
    ) {
        try {
            return operatorService.getOperatorLogs(namespace);
        } catch (Exception e) {
            return ToolError.of("Failed to retrieve operator logs", e);
        }
    }

    /**
     * Get comprehensive Strimzi operator deployment status with health analysis.
     *
     * @param namespace namespace where the operator is deployed or null for auto-discovery
     * @return structured status result with deployment health or error details
     */
    @Tool(
        name = "strimzi_operator_status",
        description = "Get comprehensive Strimzi operator deployment status with health analysis and version information. " +
            "Checks deployment health, replica status, version info, uptime, and provides actionable diagnostic insights. " +
            "Essential for troubleshooting operator issues across all namespaces." +
            StrimziToolsPrompts.MCP_AUTO_DISCOVERY_GUIDANCE
    )
    public Object getOperatorStatus(
        @ToolArg(description = StrimziToolsPrompts.MCP_NAMESPACE_PARAM_DESC)
        String namespace
    ) {
        try {
            return operatorService.getOperatorStatus(namespace);
        } catch (Exception e) {
            return ToolError.of("Failed to retrieve operator status", e);
        }
    }

    /**
     * Get detailed description of a Strimzi operator pod.
     *
     * @param namespace namespace where the pod is deployed or null for auto-discovery
     * @param podName   name of the specific pod to describe or null for auto-discovery
     * @param sections  comma-separated detail sections to include
     * @return structured pod description or error details
     */
    @Tool(
        name = "strimzi_pod_describe",
        description = "Get detailed description of a Strimzi operator pod including environment variables, " +
            "container specs, resource requests/limits, volume mounts, and node placement. " +
            "If podName is not specified, auto-discovers and describes the first operator pod." +
            StrimziToolsPrompts.MCP_AUTO_DISCOVERY_GUIDANCE
    )
    public Object describePod(
        @ToolArg(description = StrimziToolsPrompts.MCP_NAMESPACE_PARAM_DESC)
        String namespace,
        @ToolArg(description = "Name of a specific pod to describe (e.g., 'strimzi-cluster-operator-557fd4bbc-666r6'). " +
            "If not specified, auto-discovers and describes the first operator pod.")
        String podName,
        @ToolArg(description = StrimziToolsPrompts.MCP_SECTIONS_PARAM_DESC)
        String sections
    ) {
        try {
            return podsService.describePod(namespace, podName, sections);
        } catch (Exception e) {
            return ToolError.of("Failed to describe pod", e);
        }
    }

    /**
     * Discover and list all Strimzi cluster operators with their deployment status.
     *
     * @param namespace namespace to search or null for all namespaces
     * @return structured result with cluster operator information
     */
    @Tool(
        name = "strimzi_cluster_operators",
        description = "Discover and list all Strimzi cluster operators with their deployment status and health information. " +
            "Searches for operator deployments and provides comprehensive information about all deployed operators, " +
            "including namespace, replica status, version, and health. " +
            "Perfect for getting overview of operator landscape across all namespaces or in a specific namespace." +
            StrimziToolsPrompts.MCP_AUTO_DISCOVERY_GUIDANCE
    )
    public Object getClusterOperators(
        @ToolArg(description = StrimziToolsPrompts.MCP_NAMESPACE_PARAM_DESC)
        String namespace
    ) {
        try {
            return operatorService.getClusterOperators(namespace);
        } catch (Exception e) {
            return ToolError.of("Failed to retrieve cluster operators", e);
        }
    }
}
