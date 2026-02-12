/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.mcp.tool;

import dev.langchain4j.agent.tool.Tool;
import io.strimzi.mcp.dto.OperatorLogsResult;
import io.strimzi.mcp.dto.OperatorStatusResult;
import io.strimzi.mcp.dto.PodsResult;
import io.strimzi.mcp.service.infra.PodsService;
import io.strimzi.mcp.service.infra.StrimziOperatorService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * LangChain4J tools for Strimzi operator operations.
 *
 * Handles operator logs, status, health monitoring, and pod description.
 */
@ApplicationScoped
public class StrimziOperatorTools {

    StrimziOperatorTools() {
    }

    @Inject
    StrimziOperatorService operatorService;

    @Inject
    PodsService podsService;

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

    /**
     * Get detailed description of a specific pod or the first operator pod.
     *
     * Returns kubectl-describe-equivalent information including environment variables,
     * container specs, resource requests/limits, volume mounts, and node placement.
     *
     * If podName is not specified, auto-discovers operator pods and describes the first one.
     * Smart namespace discovery: auto-discovers if namespace not specified.
     *
     * @param namespace namespace where the pod is deployed (optional - auto-discovered if not specified)
     * @param podName name of the specific pod to describe (optional - auto-discovers operator pod if not specified)
     * @param sections comma-separated detail sections: node, labels, env, resources, volumes, conditions, full (optional - summary only if omitted)
     * @return structured pod description with container details, env vars, volumes, and resource info
     */
    @Tool("Get detailed pod description including environment variables, container specs, resource limits, and volumes. Auto-discovers operator namespace if not specified.")
    public PodsResult describePod(String namespace, String podName, String sections) {
        return podsService.describePod(namespace, podName, sections);
    }
}
