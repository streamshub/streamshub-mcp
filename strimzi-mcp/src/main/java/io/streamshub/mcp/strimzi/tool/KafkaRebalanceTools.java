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
import io.streamshub.mcp.common.config.ToolMetaFields;
import io.streamshub.mcp.common.guardrail.Guarded;
import io.streamshub.mcp.strimzi.config.StrimziToolResources;
import io.streamshub.mcp.strimzi.config.StrimziToolsPrompts;
import io.streamshub.mcp.strimzi.dto.kafkarebalance.KafkaRebalanceResponse;
import io.streamshub.mcp.strimzi.service.kafkarebalance.KafkaRebalanceService;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.util.List;
/**
 * MCP tools for KafkaRebalance operations.
 */
@Singleton
@Guarded
@WrapBusinessError(Exception.class)
public class KafkaRebalanceTools {

    @Inject
    KafkaRebalanceService rebalanceService;

    KafkaRebalanceTools() {
    }

    /**
     * List KafkaRebalances.
     *
     * @param clusterName optional Kafka cluster filter
     * @param namespace   optional namespace filter
     * @return list of rebalance summary responses
     */
    @WithSpan("tool.list_kafka_rebalances")
    @MetaField(name = ToolMetaFields.TYPE, value = ToolMetaFields.Types.LIST)
    @MetaField(name = ToolMetaFields.RESOURCE, value = StrimziToolResources.KAFKA_REBALANCE)
    @Tool(
        name = "list_kafka_rebalances",
        description = "List KafkaRebalance resources with state, mode,"
            + " cluster association, and optimization summary."
            + " Optionally filter by Kafka cluster.",
        annotations = @Tool.Annotations(
            readOnlyHint = true,
            destructiveHint = false,
            idempotentHint = true,
            openWorldHint = false
        )
    )
    public List<KafkaRebalanceResponse> listKafkaRebalances(
        @ToolArg(
            description = StrimziToolsPrompts.CLUSTER_FILTER_REBALANCE_DESC,
            required = false
        ) final String clusterName,
        @ToolArg(
            description = StrimziToolsPrompts.NS_DESC,
            required = false
        ) final String namespace
    ) {
        return rebalanceService.listRebalances(namespace, clusterName);
    }

    /**
     * Get a specific KafkaRebalance.
     *
     * @param rebalanceName the rebalance name
     * @param namespace     optional namespace
     * @return the detailed rebalance response
     */
    @WithSpan("tool.get_kafka_rebalance")
    @MetaField(name = ToolMetaFields.TYPE, value = ToolMetaFields.Types.GET)
    @MetaField(name = ToolMetaFields.RESOURCE, value = StrimziToolResources.KAFKA_REBALANCE)
    @Tool(
        name = "get_kafka_rebalance",
        description = "Get detailed KafkaRebalance information including"
            + " rebalance spec, optimization metrics (data to move,"
            + " replica/leader movements, balancedness scores),"
            + " auto-approval status, and Cruise Control session.",
        annotations = @Tool.Annotations(
            readOnlyHint = true,
            destructiveHint = false,
            idempotentHint = true,
            openWorldHint = false
        )
    )
    public KafkaRebalanceResponse getKafkaRebalance(
        @ToolArg(
            description = StrimziToolsPrompts.REBALANCE_NAME_DESC
        ) final String rebalanceName,
        @ToolArg(
            description = StrimziToolsPrompts.NS_DESC,
            required = false
        ) final String namespace
    ) {
        return rebalanceService.getRebalance(namespace, rebalanceName);
    }
}
