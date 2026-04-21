/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.tool.kafkaconnect;

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
import io.streamshub.mcp.strimzi.dto.kafkaconnect.KafkaConnectLogsResponse;
import io.streamshub.mcp.strimzi.dto.kafkaconnect.KafkaConnectPodsResponse;
import io.streamshub.mcp.strimzi.dto.kafkaconnect.KafkaConnectResponse;
import io.streamshub.mcp.strimzi.service.kafkaconnect.KafkaConnectService;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.List;

/**
 * MCP tools for KafkaConnect cluster operations.
 */
@Singleton
@Guarded
@WrapBusinessError(Exception.class)
public class KafkaConnectTools {

    private static final int SECONDS_PER_MINUTE = 60;

    @Inject
    KafkaConnectService connectService;

    @ConfigProperty(name = "mcp.log.tail-lines", defaultValue = "200")
    int defaultTailLines;

    KafkaConnectTools() {
    }

    /**
     * List KafkaConnect clusters.
     *
     * @param namespace optional namespace filter
     * @return list of KafkaConnect responses
     */
    @Tool(
        name = "list_kafka_connects",
        description = "List KafkaConnect clusters with status,"
            + " replicas, and connector plugin counts."
            + " Optionally filter by namespace."
    )
    public List<KafkaConnectResponse> listKafkaConnects(
        @ToolArg(
            description = StrimziToolsPrompts.NS_DESC,
            required = false
        ) final String namespace
    ) {
        return connectService.listConnects(namespace);
    }

    /**
     * Get a specific KafkaConnect cluster.
     *
     * @param connectName the KafkaConnect cluster name
     * @param namespace   optional namespace
     * @return the KafkaConnect response
     */
    @Tool(
        name = "get_kafka_connect",
        description = "Get detailed information about a specific"
            + " KafkaConnect cluster including status, bootstrap"
            + " servers, REST API URL, and available connector plugins."
    )
    public KafkaConnectResponse getKafkaConnect(
        @ToolArg(
            description = StrimziToolsPrompts.CONNECT_CLUSTER_DESC
        ) final String connectName,
        @ToolArg(
            description = StrimziToolsPrompts.NS_DESC,
            required = false
        ) final String namespace
    ) {
        return connectService.getConnect(namespace, connectName);
    }

    /**
     * Get pods for a KafkaConnect cluster.
     *
     * @param connectName the KafkaConnect cluster name
     * @param namespace   optional namespace
     * @return the Connect pods response
     */
    @Tool(
        name = "get_kafka_connect_pods",
        description = "Get pod summaries for a KafkaConnect cluster"
            + " with phase, readiness, restarts, and age."
    )
    public KafkaConnectPodsResponse getKafkaConnectPods(
        @ToolArg(
            description = StrimziToolsPrompts.CONNECT_CLUSTER_DESC
        ) final String connectName,
        @ToolArg(
            description = StrimziToolsPrompts.NS_DESC,
            required = false
        ) final String namespace
    ) {
        return connectService.getConnectPods(namespace, connectName);
    }

    /**
     * Get logs from KafkaConnect cluster pods.
     *
     * @param connectName  the KafkaConnect cluster name
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
     * @return the Connect logs response with error analysis
     */
    @Tool(
        name = "get_kafka_connect_logs",
        description = "Get logs from KafkaConnect pods with error analysis."
            + " Returns logs from all pods belonging to the Connect cluster."
    )
    @RateCategory("log")
    public KafkaConnectLogsResponse getKafkaConnectLogs(
        @ToolArg(
            description = StrimziToolsPrompts.CONNECT_CLUSTER_DESC
        ) final String connectName,
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
        return connectService.getConnectLogs(namespace, connectName, options);
    }
}
