/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.tool.operator;

import io.opentelemetry.instrumentation.annotations.WithSpan;
import io.quarkiverse.mcp.server.McpException;
import io.quarkiverse.mcp.server.MetaField;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.quarkiverse.mcp.server.ToolCallException;
import io.quarkiverse.mcp.server.ToolGuardrails;
import io.quarkiverse.mcp.server.WrapBusinessError;
import io.streamshub.mcp.common.config.ToolMetaFields;
import io.streamshub.mcp.common.guardrail.ArgumentSanitizationGuardrail;
import io.streamshub.mcp.common.guardrail.GeneralRateLimitGuardrail;
import io.streamshub.mcp.common.guardrail.LogRedactionGuardrail;
import io.streamshub.mcp.common.guardrail.ResponseSizeLimitGuardrail;
import io.streamshub.mcp.common.observability.MeasuredTool;
import io.streamshub.mcp.strimzi.config.StrimziToolResources;
import io.streamshub.mcp.strimzi.config.StrimziToolsPrompts;
import io.streamshub.mcp.strimzi.dto.operator.StrimziEventsResponse;
import io.streamshub.mcp.strimzi.service.operator.StrimziEventsService;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
/**
 * MCP tools for Strimzi Kubernetes events.
 */
@Singleton
@MeasuredTool
@WrapBusinessError(value = Exception.class, unless = {ToolCallException.class, McpException.class})
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
     * @param resourceKind required Strimzi resource kind (see supported values)
     * @return events grouped by resource
     */
    @WithSpan("tool.get_strimzi_events")
    @MetaField(name = ToolMetaFields.TYPE, value = ToolMetaFields.Types.EVENTS)
    @MetaField(name = ToolMetaFields.RESOURCE, value = StrimziToolResources.STRIMZI_EVENT)
    @Tool(
        name = "get_strimzi_events",
        description = "Get Kubernetes events for a Strimzi resource and all related pods."
            + " resource_kind is required; for Kafka it also includes PVC and node pool events.",
        structuredContent = true,
        annotations = @Tool.Annotations(
            readOnlyHint = true,
            destructiveHint = false,
            idempotentHint = true,
            openWorldHint = false
        )
    )
    @ToolGuardrails(
        input  = { GeneralRateLimitGuardrail.class, ArgumentSanitizationGuardrail.class },
        output = { LogRedactionGuardrail.class, ResponseSizeLimitGuardrail.class })
    public StrimziEventsResponse getStrimziEvents(
        @NotBlank @ToolArg(
            description = "Name of the Strimzi resource identified by resource_kind,"
                + " e.g. 'my-cluster' or 'my-connect'."
        ) final String resourceName,
        @ToolArg(
            description = StrimziToolsPrompts.NS_DESC,
            required = false
        ) final String namespace,
        @Min(1) @ToolArg(
            description = StrimziToolsPrompts.SINCE_MINUTES_EVENTS_DESC,
            required = false
        ) final Integer sinceMinutes,
        @NotBlank @ToolArg(
            description = StrimziToolsPrompts.RESOURCE_KIND_DESC
        ) final String resourceKind
    ) {
        return eventsService.getEvents(namespace, resourceName, resourceKind, sinceMinutes);
    }
}
