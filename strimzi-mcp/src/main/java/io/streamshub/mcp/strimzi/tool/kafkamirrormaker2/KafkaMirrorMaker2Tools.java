/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.tool.kafkamirrormaker2;

import io.opentelemetry.instrumentation.annotations.WithSpan;
import io.quarkiverse.mcp.server.Cancellation;
import io.quarkiverse.mcp.server.McpLog;
import io.quarkiverse.mcp.server.MetaField;
import io.quarkiverse.mcp.server.Progress;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.quarkiverse.mcp.server.WrapBusinessError;
import io.streamshub.mcp.common.dto.LogCollectionParams;
import io.streamshub.mcp.common.guardrail.Guarded;
import io.streamshub.mcp.common.guardrail.RateCategory;
import io.streamshub.mcp.common.util.TimeRangeValidator;
import io.streamshub.mcp.strimzi.config.StrimziToolsPrompts;
import io.streamshub.mcp.strimzi.config.ToolMetaFields;
import io.streamshub.mcp.strimzi.dto.kafkamirrormaker2.KafkaMirrorMaker2LogsResponse;
import io.streamshub.mcp.strimzi.dto.kafkamirrormaker2.KafkaMirrorMaker2PodsResponse;
import io.streamshub.mcp.strimzi.dto.kafkamirrormaker2.KafkaMirrorMaker2Response;
import io.streamshub.mcp.strimzi.service.kafkamirrormaker2.KafkaMirrorMaker2Service;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.List;
/**
 * MCP tools for KafkaMirrorMaker2 operations.
 */
@Singleton
@Guarded
@WrapBusinessError(Exception.class)
public class KafkaMirrorMaker2Tools {

    private static final int SECONDS_PER_MINUTE = 60;

    @Inject
    KafkaMirrorMaker2Service mirrorMakerService;

    @ConfigProperty(name = "mcp.log.tail-lines", defaultValue = "200")
    int defaultTailLines;

    KafkaMirrorMaker2Tools() {
    }

    /**
     * List KafkaMirrorMaker2 instances.
     *
     * @param namespace optional namespace filter
     * @return list of MM2 summary responses
     */
    @WithSpan("tool.list_kafka_mirror_makers")
    @MetaField(name = ToolMetaFields.TYPE, value = ToolMetaFields.Types.LIST)
    @MetaField(name = ToolMetaFields.RESOURCE, value = ToolMetaFields.Resources.KAFKA_MIRROR_MAKER_2)
    @Tool(
        name = "list_kafka_mirror_makers",
        description = "List KafkaMirrorMaker2 instances with status,"
            + " replicas, and source/target cluster pairs."
            + " Optionally filter by namespace.",
        annotations = @Tool.Annotations(
            readOnlyHint = true,
            destructiveHint = false,
            idempotentHint = true,
            openWorldHint = false
        )
    )
    public List<KafkaMirrorMaker2Response> listKafkaMirrorMakers(
        @ToolArg(
            description = StrimziToolsPrompts.NS_DESC,
            required = false
        ) final String namespace
    ) {
        return mirrorMakerService.listMirrorMakers(namespace);
    }

    /**
     * Get a specific KafkaMirrorMaker2 instance.
     *
     * @param mirrorMakerName the MM2 name
     * @param namespace       optional namespace
     * @return the detailed MM2 response
     */
    @WithSpan("tool.get_kafka_mirror_maker")
    @MetaField(name = ToolMetaFields.TYPE, value = ToolMetaFields.Types.GET)
    @MetaField(name = ToolMetaFields.RESOURCE, value = ToolMetaFields.Resources.KAFKA_MIRROR_MAKER_2)
    @Tool(
        name = "get_kafka_mirror_maker",
        description = "Get detailed KafkaMirrorMaker2 information including"
            + " mirror configurations, cluster connections, connector statuses,"
            + " and replication topic/group patterns.",
        annotations = @Tool.Annotations(
            readOnlyHint = true,
            destructiveHint = false,
            idempotentHint = true,
            openWorldHint = false
        )
    )
    public KafkaMirrorMaker2Response getKafkaMirrorMaker(
        @ToolArg(
            description = StrimziToolsPrompts.MIRROR_MAKER_NAME_DESC
        ) final String mirrorMakerName,
        @ToolArg(
            description = StrimziToolsPrompts.NS_DESC,
            required = false
        ) final String namespace
    ) {
        return mirrorMakerService.getMirrorMaker(namespace, mirrorMakerName);
    }

    /**
     * Get pods for a KafkaMirrorMaker2 instance.
     *
     * @param mirrorMakerName the MM2 name
     * @param namespace       optional namespace
     * @return the pods response
     */
    @WithSpan("tool.get_kafka_mirror_maker_pods")
    @MetaField(name = ToolMetaFields.TYPE, value = ToolMetaFields.Types.GET)
    @MetaField(name = ToolMetaFields.RESOURCE, value = ToolMetaFields.Resources.KAFKA_MIRROR_MAKER_2)
    @Tool(
        name = "get_kafka_mirror_maker_pods",
        description = "Get pod summaries for a KafkaMirrorMaker2 instance"
            + " with phase, readiness, restart counts, and age.",
        annotations = @Tool.Annotations(
            readOnlyHint = true,
            destructiveHint = false,
            idempotentHint = true,
            openWorldHint = false
        )
    )
    public KafkaMirrorMaker2PodsResponse getKafkaMirrorMakerPods(
        @ToolArg(
            description = StrimziToolsPrompts.MIRROR_MAKER_NAME_DESC
        ) final String mirrorMakerName,
        @ToolArg(
            description = StrimziToolsPrompts.NS_DESC,
            required = false
        ) final String namespace
    ) {
        return mirrorMakerService.getMirrorMakerPods(namespace, mirrorMakerName);
    }

    /**
     * Get logs from KafkaMirrorMaker2 pods.
     *
     * @param mirrorMakerName the MM2 name
     * @param namespace       optional namespace
     * @param filter          optional log filter
     * @param keywords        optional keywords
     * @param sinceMinutes    optional time window
     * @param startTime       optional absolute start time
     * @param endTime         optional absolute end time
     * @param tailLines       optional line count
     * @param previous        optional previous container flag
     * @param mcpLog          MCP log notifications
     * @param progress        MCP progress tracking
     * @param cancellation    MCP cancellation checking
     * @return the logs response
     */
    @SuppressWarnings("checkstyle:ParameterNumber")
    @WithSpan("tool.get_kafka_mirror_maker_logs")
    @MetaField(name = ToolMetaFields.TYPE, value = ToolMetaFields.Types.LOGS)
    @MetaField(name = ToolMetaFields.RESOURCE, value = ToolMetaFields.Resources.KAFKA_MIRROR_MAKER_2)
    @Tool(
        name = "get_kafka_mirror_maker_logs",
        description = "Get logs from KafkaMirrorMaker2 pods with error analysis."
            + " Supports filtering, time ranges, and keyword search.",
        annotations = @Tool.Annotations(
            readOnlyHint = true,
            destructiveHint = false,
            idempotentHint = true,
            openWorldHint = false
        )
    )
    @RateCategory("log")
    public KafkaMirrorMaker2LogsResponse getKafkaMirrorMakerLogs(
        @ToolArg(description = StrimziToolsPrompts.MIRROR_MAKER_NAME_DESC) final String mirrorMakerName,
        @ToolArg(description = StrimziToolsPrompts.NS_DESC, required = false) final String namespace,
        @ToolArg(description = StrimziToolsPrompts.LOG_FILTER_DESC, required = false) final String filter,
        @ToolArg(description = StrimziToolsPrompts.KEYWORDS_DESC, required = false) final List<String> keywords,
        @ToolArg(description = StrimziToolsPrompts.SINCE_MINUTES_DESC, required = false) final Integer sinceMinutes,
        @ToolArg(description = StrimziToolsPrompts.START_TIME_DESC, required = false) final String startTime,
        @ToolArg(description = StrimziToolsPrompts.END_TIME_DESC, required = false) final String endTime,
        @ToolArg(description = StrimziToolsPrompts.TAIL_LINES_DESC, required = false) final Integer tailLines,
        @ToolArg(description = StrimziToolsPrompts.PREVIOUS_DESC, required = false) final Boolean previous,
        final McpLog mcpLog,
        final Progress progress,
        final Cancellation cancellation
    ) {
        TimeRangeValidator.validateTimeRangeParameters(sinceMinutes, startTime, endTime);
        LogCollectionParams options = LogCollectionParams.builder(tailLines != null ? tailLines : defaultTailLines)
            .filter(filter)
            .keywords(keywords)
            .sinceSeconds(sinceMinutes != null ? sinceMinutes * SECONDS_PER_MINUTE : null)
            .startTime(startTime)
            .endTime(endTime)
            .previous(Boolean.TRUE.equals(previous))
            .notifier(mcpLog::info)
            .cancelCheck(cancellation::skipProcessingIfCancelled)
            .progressCallback(progress.token().isPresent()
                ? (completed, total) -> progress.notificationBuilder()
                    .setProgress(completed).setTotal(total)
                    .setMessage(String.format("Collected logs from %d/%d pods", completed, total))
                    .build().sendAndForget()
                : null)
            .build();
        return mirrorMakerService.getMirrorMakerLogs(namespace, mirrorMakerName, options);
    }
}
