/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.tool;

import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.quarkiverse.mcp.server.WrapBusinessError;
import io.streamshub.mcp.common.guardrail.Guarded;
import io.streamshub.mcp.strimzi.config.StrimziToolsPrompts;
import io.streamshub.mcp.strimzi.dto.StrimziEventsResponse;
import io.streamshub.mcp.strimzi.service.StrimziEventsService;
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
     * Get Kubernetes events for a Kafka cluster and all related resources.
     *
     * @param clusterName  the cluster name
     * @param namespace    optional namespace
     * @param sinceMinutes optional time window in minutes
     * @return events grouped by resource
     */
    @Tool(
        name = "get_strimzi_events",
        description = "Get Kubernetes events for a Kafka cluster and all related resources"
            + " (pods, PVCs, node pools). Shows scheduling, restarts, volume issues,"
            + " and operator reconciliation events.",
        annotations = @Tool.Annotations(
            readOnlyHint = true,
            destructiveHint = false,
            idempotentHint = true,
            openWorldHint = false
        )
    )
    public StrimziEventsResponse getStrimziEvents(
        @ToolArg(
            description = StrimziToolsPrompts.CLUSTER_DESC
        ) final String clusterName,
        @ToolArg(
            description = StrimziToolsPrompts.NS_DESC,
            required = false
        ) final String namespace,
        @ToolArg(
            description = StrimziToolsPrompts.SINCE_MINUTES_EVENTS_DESC,
            required = false
        ) final Integer sinceMinutes
    ) {
        return eventsService.getClusterEvents(namespace, clusterName, sinceMinutes);
    }
}
