package io.strimzi.mcp.tool;

import dev.langchain4j.agent.tool.Tool;
import io.strimzi.mcp.dto.OperatorLogsResult;
import io.strimzi.mcp.dto.OperatorStatusResult;
import io.strimzi.mcp.service.infra.StrimziOperatorService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * LangChain4J tools for Strimzi operator operations.
 *
 * Handles operator logs, status, and health monitoring.
 */
@ApplicationScoped
public class StrimziOperatorTools {

    @Inject
    StrimziOperatorService operatorService;

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

}
