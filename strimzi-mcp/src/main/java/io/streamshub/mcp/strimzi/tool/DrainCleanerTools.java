/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.tool;

import io.opentelemetry.instrumentation.annotations.WithSpan;
import io.quarkiverse.mcp.server.Cancellation;
import io.quarkiverse.mcp.server.McpLog;
import io.quarkiverse.mcp.server.Progress;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.quarkiverse.mcp.server.WrapBusinessError;
import io.streamshub.mcp.common.dto.LogCollectionParams;
import io.streamshub.mcp.common.guardrail.Guarded;
import io.streamshub.mcp.common.guardrail.RateCategory;
import io.streamshub.mcp.common.util.TimeRangeValidator;
import io.streamshub.mcp.strimzi.config.StrimziToolsPrompts;
import io.streamshub.mcp.strimzi.dto.DrainCleanerLogsResponse;
import io.streamshub.mcp.strimzi.dto.DrainCleanerReadinessResponse;
import io.streamshub.mcp.strimzi.dto.DrainCleanerResponse;
import io.streamshub.mcp.strimzi.service.DrainCleanerService;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.List;

/**
 * MCP tools for Strimzi Drain Cleaner operations.
 */
@Singleton
@Guarded
@WrapBusinessError(Exception.class)
public class DrainCleanerTools {

    private static final int SECONDS_PER_MINUTE = 60;

    @Inject
    DrainCleanerService drainCleanerService;

    @ConfigProperty(name = "mcp.log.tail-lines", defaultValue = "200")
    int defaultTailLines;

    DrainCleanerTools() {
    }

    /**
     * List Strimzi Drain Cleaner deployments.
     *
     * @param namespace optional namespace filter
     * @return list of drain cleaner responses
     */
    @WithSpan("tool.list_drain_cleaners")
    @Tool(
        name = "list_drain_cleaners",
        description = "List Strimzi Drain Cleaner deployments with status and webhook configuration."
            + " Drain Cleaner handles graceful pod evictions during node drains.",
        annotations = @Tool.Annotations(
            readOnlyHint = true,
            destructiveHint = false,
            idempotentHint = true,
            openWorldHint = false
        )
    )
    public List<DrainCleanerResponse> listDrainCleaners(
        @ToolArg(
            description = StrimziToolsPrompts.NS_DESC,
            required = false
        ) final String namespace
    ) {
        return drainCleanerService.listDrainCleaners(namespace);
    }

    /**
     * Get a specific Strimzi Drain Cleaner.
     *
     * @param drainCleanerName the drain cleaner deployment name
     * @param namespace        optional namespace
     * @return the drain cleaner response
     */
    @WithSpan("tool.get_drain_cleaner")
    @Tool(
        name = "get_drain_cleaner",
        description = "Get detailed information about a Strimzi Drain Cleaner deployment"
            + " including webhook configuration, failure policy, operating mode, and TLS certificate status.",
        annotations = @Tool.Annotations(
            readOnlyHint = true,
            destructiveHint = false,
            idempotentHint = true,
            openWorldHint = false
        )
    )
    public DrainCleanerResponse getDrainCleaner(
        @ToolArg(
            description = StrimziToolsPrompts.DRAIN_CLEANER_NAME_DESC
        ) final String drainCleanerName,
        @ToolArg(
            description = StrimziToolsPrompts.NS_DESC,
            required = false
        ) final String namespace
    ) {
        return drainCleanerService.getDrainCleaner(namespace, drainCleanerName);
    }

    /**
     * Get Strimzi Drain Cleaner logs.
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
     * @return the drain cleaner logs response
     */
    @WithSpan("tool.get_drain_cleaner_logs")
    @Tool(
        name = "get_drain_cleaner_logs",
        description = "Get logs from Strimzi Drain Cleaner pods."
            + " Useful for checking behavior during node drains and rolling updates.",
        annotations = @Tool.Annotations(
            readOnlyHint = true,
            destructiveHint = false,
            idempotentHint = true,
            openWorldHint = false
        )
    )
    @RateCategory("log")
    public DrainCleanerLogsResponse getDrainCleanerLogs(
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
            .progressCallback(progress.token().isPresent()
                ? (completed, total) -> progress.notificationBuilder()
                                        .setProgress(completed).setTotal(total)
                                        .setMessage(String.format("Collected logs from %d/%d pods", completed, total))
                                        .build().sendAndForget()
                : null)
            .build();
        return drainCleanerService.getDrainCleanerLogs(namespace, null, options);
    }

    /**
     * Check production readiness of Strimzi Drain Cleaner.
     *
     * @param namespace optional namespace
     * @return the readiness response
     */
    @WithSpan("tool.check_drain_cleaner_readiness")
    @Tool(
        name = "check_drain_cleaner_readiness",
        description = "Check readiness of Strimzi Drain Cleaner."
            + " Assesses deployment health, webhook configuration, TLS certificates,"
            + " operating mode, and failure policy.",
        annotations = @Tool.Annotations(
            readOnlyHint = true,
            destructiveHint = false,
            idempotentHint = true,
            openWorldHint = false
        )
    )
    public DrainCleanerReadinessResponse checkDrainCleanerReadiness(
        @ToolArg(
            description = StrimziToolsPrompts.NS_DESC,
            required = false
        ) final String namespace
    ) {
        return drainCleanerService.checkReadiness(namespace);
    }
}
