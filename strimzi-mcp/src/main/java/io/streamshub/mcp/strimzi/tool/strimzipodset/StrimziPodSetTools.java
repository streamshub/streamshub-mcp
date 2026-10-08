/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.tool.strimzipodset;

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
import io.streamshub.mcp.strimzi.dto.strimzipodset.StrimziPodSetListResponse;
import io.streamshub.mcp.strimzi.dto.strimzipodset.StrimziPodSetResponse;
import io.streamshub.mcp.strimzi.service.strimzipodset.StrimziPodSetService;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.validation.constraints.NotBlank;

import java.util.List;

/**
 * MCP tools for StrimziPodSet operations.
 */
@Singleton
@MeasuredTool
@WrapBusinessError(value = Exception.class, unless = {ToolCallException.class, McpException.class})
public class StrimziPodSetTools {

    @Inject
    StrimziPodSetService podSetService;

    StrimziPodSetTools() {
    }

    /**
     * List the StrimziPodSets owned by a Kafka cluster with rolling-update state.
     *
     * @param clusterName the cluster name
     * @param namespace   optional namespace
     * @return the StrimziPodSet list response
     */
    @WithSpan("tool.get_strimzi_pod_sets")
    @MetaField(name = ToolMetaFields.TYPE, value = ToolMetaFields.Types.GET)
    @MetaField(name = ToolMetaFields.RESOURCE, value = StrimziToolResources.STRIMZI_POD_SET)
    @Tool(
        name = "get_strimzi_pod_sets",
        structuredContent = true,
        description = "Lists StrimziPodSets for a Kafka cluster to inspect rolling-update state, showing"
            + " which pods are on the current revision and which are still on a previous one."
            + " StrimziPodSet is a Strimzi-internal resource managed by the operator.",
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
    public StrimziPodSetListResponse getStrimziPodSets(
        @NotBlank @ToolArg(
            description = StrimziToolsPrompts.CLUSTER_DESC
        ) final String clusterName,
        @ToolArg(
            description = StrimziToolsPrompts.NS_DESC,
            required = false
        ) final String namespace
    ) {
        List<StrimziPodSetResponse> items = podSetService.listPodSets(namespace, clusterName);
        return new StrimziPodSetListResponse(items, items.size());
    }
}
