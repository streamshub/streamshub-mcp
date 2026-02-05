package io.strimzi.mcp.mcp;

import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.strimzi.mcp.config.StrimziAssistantPrompts;
import io.strimzi.mcp.service.infra.StrimziOperatorService;
import io.strimzi.mcp.exception.ToolError;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * MCP tools for Strimzi operator operations.
 *
 * Handles operator logs, status, and health monitoring.
 */
@Singleton
public class StrimziOperatorMcpTools {

    @Inject
    StrimziOperatorService operatorService;

    @Tool(
        name = "strimzi_operator_logs",
        description = "Get comprehensive logs from Strimzi Kafka operator pods with error analysis and structured output. " +
                     "Retrieves recent logs from all operator pods, highlights errors/warnings, and provides health insights." +
                     StrimziAssistantPrompts.MCP_AUTO_DISCOVERY_GUIDANCE
    )
    public Object readStrimziOperatorLogs(
        @ToolArg(description = StrimziAssistantPrompts.MCP_NAMESPACE_PARAM_DESC)
        String namespace
    ) {
        try {
            return operatorService.getOperatorLogs(namespace);
        } catch (Exception e) {
            return ToolError.of("Failed to retrieve operator logs", e);
        }
    }


    @Tool(
        name = "strimzi_operator_status",
        description = "Get comprehensive Strimzi operator deployment status with health analysis and version information. " +
                     "Checks deployment health, replica status, version info, uptime, and provides actionable diagnostic insights. " +
                     "Essential for troubleshooting operator issues across all namespaces." +
                     StrimziAssistantPrompts.MCP_AUTO_DISCOVERY_GUIDANCE
    )
    public Object getOperatorStatus(
        @ToolArg(description = StrimziAssistantPrompts.MCP_NAMESPACE_PARAM_DESC)
        String namespace
    ) {
        try {
            return operatorService.getOperatorStatus(namespace);
        } catch (Exception e) {
            return ToolError.of("Failed to retrieve operator status", e);
        }
    }

}
