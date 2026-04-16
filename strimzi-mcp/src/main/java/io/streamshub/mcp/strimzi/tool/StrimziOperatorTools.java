/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.tool;

import io.quarkiverse.mcp.server.Cancellation;
import io.quarkiverse.mcp.server.McpLog;
import io.quarkiverse.mcp.server.Progress;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.quarkiverse.mcp.server.WrapBusinessError;
import io.streamshub.mcp.common.dto.LogCollectionParams;
import io.streamshub.mcp.common.dto.PodSummaryResponse;
import io.streamshub.mcp.common.guardrail.Guarded;
import io.streamshub.mcp.common.service.PodsService;
import io.streamshub.mcp.common.util.TimeRangeValidator;
import io.streamshub.mcp.strimzi.config.StrimziToolsPrompts;
import io.streamshub.mcp.strimzi.dto.StrimziOperatorLogsResponse;
import io.streamshub.mcp.strimzi.dto.StrimziOperatorResponse;
import io.streamshub.mcp.strimzi.service.StrimziOperatorService;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.List;

/**
 * MCP tools for Strimzi operator operations.
 */
@Singleton
@Guarded
@WrapBusinessError(Exception.class)
public class StrimziOperatorTools {

    private static final int SECONDS_PER_MINUTE = 60;

    @Inject
    StrimziOperatorService operatorService;

    @Inject
    PodsService podsService;

    @ConfigProperty(name = "mcp.log.tail-lines", defaultValue = "200")
    int defaultTailLines;

    StrimziOperatorTools() {
    }

    /**
     * List Strimzi cluster operators.
     *
     * @param namespace optional namespace filter
     * @return list of operator responses
     */
    @Tool(
        name = "list_strimzi_operators",
        description = "List Strimzi cluster operators with deployment status and health."
            + " Optionally filter by namespace."
    )
    public List<StrimziOperatorResponse> listStrimziOperators(
        @ToolArg(
            description = StrimziToolsPrompts.NS_DESC,
            required = false
        ) final String namespace
    ) {
        return operatorService.listOperators(namespace);
    }

    /**
     * Get a specific Strimzi operator.
     *
     * @param operatorName the operator name
     * @param namespace    optional namespace
     * @return the operator response
     */
    @Tool(
        name = "get_strimzi_operator",
        description = "Get detailed information about a specific Strimzi operator"
            + " including status, version, image, and uptime."
    )
    public StrimziOperatorResponse getStrimziOperator(
        @ToolArg(
            description = "Name of the operator deployment (e.g., 'strimzi-cluster-operator')."
        ) final String operatorName,
        @ToolArg(
            description = StrimziToolsPrompts.NS_DESC,
            required = false
        ) final String namespace
    ) {
        return operatorService.getOperator(namespace, operatorName);
    }

    /**
     * Get Strimzi operator logs.
     *
     * @param namespace    optional namespace
     * @param filter       optional log filter
     * @param keywords     optional keyword list for filtering
     * @param sinceMinutes optional time range in minutes
     * @param startTime    optional absolute start time (ISO 8601)
     * @param endTime      optional absolute end time (ISO 8601)
     * @param tailLines    optional number of lines to tail
     * @param previous     optional flag for previous container logs
     * @param mcpLog       MCP log for client notifications
     * @param progress     MCP progress tracking
     * @param cancellation MCP cancellation checking
     * @return the operator logs response
     */
    @Tool(
        name = "get_strimzi_operator_logs",
        description = "Get logs from Strimzi operator pods with error analysis."
            + " Returns logs from all operator pods."
    )
    public StrimziOperatorLogsResponse getStrimziOperatorLogs(
        @ToolArg(
            description = StrimziToolsPrompts.NS_DESC,
            required = false
        ) final String namespace,
        @ToolArg(
            description = StrimziToolsPrompts.LOG_FILTER_DESC,
            required = false
        ) final String filter,
        @ToolArg(
            description = StrimziToolsPrompts.KEYWORDS_DESC,
            required = false
        ) final List<String> keywords,
        @ToolArg(
            description = StrimziToolsPrompts.SINCE_MINUTES_DESC,
            required = false
        ) final Integer sinceMinutes,
        @ToolArg(
            description = StrimziToolsPrompts.START_TIME_DESC,
            required = false
        ) final String startTime,
        @ToolArg(
            description = StrimziToolsPrompts.END_TIME_DESC,
            required = false
        ) final String endTime,
        @ToolArg(
            description = StrimziToolsPrompts.TAIL_LINES_DESC,
            required = false
        ) final Integer tailLines,
        @ToolArg(
            description = StrimziToolsPrompts.PREVIOUS_DESC,
            required = false
        ) final Boolean previous,
        final McpLog mcpLog,
        final Progress progress,
        final Cancellation cancellation
    ) {
        TimeRangeValidator.validateTimeRangeParameters(sinceMinutes, startTime, endTime);
        LogCollectionParams options = LogCollectionParams.builder(
                tailLines != null ? tailLines : defaultTailLines)
            .filter(filter)
            .keywords(keywords)
            .sinceSeconds(sinceMinutes != null ? sinceMinutes * SECONDS_PER_MINUTE : null)
            .startTime(startTime)
            .endTime(endTime)
            .previous(previous)
            .notifier(mcpLog::info)
            .cancelCheck(cancellation::skipProcessingIfCancelled)
            // Only send progress if the client provided a progress token
            .progressCallback(progress.token().isPresent()
                ? (completed, total) -> progress.notificationBuilder()
                    .setProgress(completed).setTotal(total)
                    .setMessage(String.format("Collected logs from %d/%d pods", completed, total))
                    .build().sendAndForget()
                : null)
            .build();
        return operatorService.getOperatorLogs(namespace, null, options);
    }

    /**
     * Get detailed description of an operator pod.
     *
     * @param namespace the namespace
     * @param podName   the pod name
     * @param sections  optional detail sections
     * @return the pod summary response
     */
    @Tool(
        name = "get_strimzi_operator_pod",
        description = "Get detailed description of a Strimzi operator pod including"
            + " environment, resources, volumes, and conditions."
    )
    public PodSummaryResponse getStrimziOperatorPod(
        @ToolArg(
            description = "Kubernetes namespace where the operator pod is deployed."
        ) final String namespace,
        @ToolArg(
            description = "Name of the pod (e.g., 'strimzi-cluster-operator-557fd4bbc-666r6')."
        ) final String podName,
        @ToolArg(
            description = StrimziToolsPrompts.SECTIONS_DESC,
            required = false
        ) final String sections
    ) {
        return podsService.describePod(namespace, podName, sections);
    }
}
