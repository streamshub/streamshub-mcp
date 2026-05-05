/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.tool.kafkabridge;

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
import io.streamshub.mcp.strimzi.dto.kafkabridge.KafkaBridgeLogsResponse;
import io.streamshub.mcp.strimzi.dto.kafkabridge.KafkaBridgePodsResponse;
import io.streamshub.mcp.strimzi.dto.kafkabridge.KafkaBridgeResponse;
import io.streamshub.mcp.strimzi.service.kafkabridge.KafkaBridgeService;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.List;

/**
 * MCP tools for KafkaBridge operations.
 */
@Singleton
@Guarded
@WrapBusinessError(Exception.class)
public class KafkaBridgeTools {

    private static final int SECONDS_PER_MINUTE = 60;

    @Inject
    KafkaBridgeService bridgeService;

    @ConfigProperty(name = "mcp.log.tail-lines", defaultValue = "200")
    int defaultTailLines;

    KafkaBridgeTools() {
    }

    /**
     * List KafkaBridge resources.
     *
     * @param namespace optional namespace filter
     * @return list of KafkaBridge responses
     */
    @Tool(
        name = "list_kafka_bridges",
        description = "List KafkaBridge resources with status,"
            + " replicas, bootstrap servers, and HTTP URL."
            + " Optionally filter by namespace.",
        annotations = @Tool.Annotations(
            readOnlyHint = true,
            destructiveHint = false,
            idempotentHint = true,
            openWorldHint = false
        )
    )
    public List<KafkaBridgeResponse> listKafkaBridges(
        @ToolArg(
            description = StrimziToolsPrompts.NS_DESC,
            required = false
        ) final String namespace
    ) {
        return bridgeService.listBridges(namespace);
    }

    /**
     * Get a specific KafkaBridge.
     *
     * @param bridgeName the KafkaBridge name
     * @param namespace  optional namespace
     * @return the KafkaBridge response
     */
    @Tool(
        name = "get_kafka_bridge",
        description = "Get detailed information about a specific"
            + " KafkaBridge including status, HTTP configuration,"
            + " CORS, producer/consumer config, authentication, and TLS.",
        annotations = @Tool.Annotations(
            readOnlyHint = true,
            destructiveHint = false,
            idempotentHint = true,
            openWorldHint = false
        )
    )
    public KafkaBridgeResponse getKafkaBridge(
        @ToolArg(
            description = StrimziToolsPrompts.BRIDGE_NAME_DESC
        ) final String bridgeName,
        @ToolArg(
            description = StrimziToolsPrompts.NS_DESC,
            required = false
        ) final String namespace
    ) {
        return bridgeService.getBridge(namespace, bridgeName);
    }

    /**
     * Get pods for a KafkaBridge.
     *
     * @param bridgeName the KafkaBridge name
     * @param namespace  optional namespace
     * @return the bridge pods response
     */
    @Tool(
        name = "get_kafka_bridge_pods",
        description = "Get pod summaries for a KafkaBridge"
            + " with phase, readiness, restarts, and age.",
        annotations = @Tool.Annotations(
            readOnlyHint = true,
            destructiveHint = false,
            idempotentHint = true,
            openWorldHint = false
        )
    )
    public KafkaBridgePodsResponse getKafkaBridgePods(
        @ToolArg(
            description = StrimziToolsPrompts.BRIDGE_NAME_DESC
        ) final String bridgeName,
        @ToolArg(
            description = StrimziToolsPrompts.NS_DESC,
            required = false
        ) final String namespace
    ) {
        return bridgeService.getBridgePods(namespace, bridgeName);
    }

    /**
     * Get logs from KafkaBridge pods.
     *
     * @param bridgeName   the KafkaBridge name
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
     * @return the bridge logs response with error analysis
     */
    @Tool(
        name = "get_kafka_bridge_logs",
        description = "Get logs from KafkaBridge pods with error analysis."
            + " Returns logs from all pods belonging to the bridge.",
        annotations = @Tool.Annotations(
            readOnlyHint = true,
            destructiveHint = false,
            idempotentHint = true,
            openWorldHint = false
        )
    )
    @RateCategory("log")
    public KafkaBridgeLogsResponse getKafkaBridgeLogs(
        @ToolArg(
            description = StrimziToolsPrompts.BRIDGE_NAME_DESC
        ) final String bridgeName,
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
        return bridgeService.getBridgeLogs(namespace, bridgeName, options);
    }
}
