/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.tool;

import io.opentelemetry.instrumentation.annotations.WithSpan;
import io.quarkiverse.mcp.server.MetaField;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.quarkiverse.mcp.server.WrapBusinessError;
import io.streamshub.mcp.common.guardrail.Guarded;
import io.streamshub.mcp.strimzi.config.StrimziToolsPrompts;
import io.streamshub.mcp.strimzi.config.ToolMetaFields;
import io.streamshub.mcp.strimzi.dto.operator.StrimziEventsResponse;
import io.streamshub.mcp.strimzi.service.operator.StrimziEventsService;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
/**
 * MCP tools for Strimzi Kubernetes events.
 */
@Singleton
@Guarded
@WrapBusinessError(Exception.class)
public class StrimziEventsTools {

    @Inject
    StrimziEventsService eventsService;

    StrimziEventsTools() {
    }

    /**
     * Get Kubernetes events for a Strimzi resource and all related pods.
     *
     * @param resourceName the Strimzi resource name
     * @param namespace    optional namespace
     * @param sinceMinutes optional time window in minutes
     * @param resourceKind optional Strimzi resource kind for non-Kafka resources
     * @return events grouped by resource
     */
    @WithSpan("tool.get_strimzi_events")
    @MetaField(name = ToolMetaFields.TYPE, value = ToolMetaFields.Types.EVENTS)
    @MetaField(name = ToolMetaFields.RESOURCE, value = ToolMetaFields.Resources.STRIMZI_EVENT)
    @Tool(
        name = "get_strimzi_events",
        description = "Get Kubernetes events for a Strimzi resource and all related pods."
            + " For Kafka clusters (default), also includes PVC and node pool events."
            + " For KafkaConnect, KafkaMirrorMaker2, or KafkaBridge, pass the resource_kind"
            + " parameter to query events for the correct resource type.",
        annotations = @Tool.Annotations(
            readOnlyHint = true,
            destructiveHint = false,
            idempotentHint = true,
            openWorldHint = false
        )
    )
    public StrimziEventsResponse getStrimziEvents(
        @ToolArg(
            description = "Strimzi resource name (Kafka cluster, KafkaConnect,"
                + " KafkaMirrorMaker2, or KafkaBridge). e.g., 'my-cluster' or 'my-connect'."
        ) final String resourceName,
        @ToolArg(
            description = StrimziToolsPrompts.NS_DESC,
            required = false
        ) final String namespace,
        @ToolArg(
            description = StrimziToolsPrompts.SINCE_MINUTES_EVENTS_DESC,
            required = false
        ) final Integer sinceMinutes,
        @ToolArg(
            description = StrimziToolsPrompts.RESOURCE_KIND_DESC
        ) final String resourceKind
    ) {
        String trimmedKind = resourceKind != null && !resourceKind.isBlank() ? resourceKind.trim() : null;
        return eventsService.getEvents(namespace, resourceName, trimmedKind, sinceMinutes);
    }
}
