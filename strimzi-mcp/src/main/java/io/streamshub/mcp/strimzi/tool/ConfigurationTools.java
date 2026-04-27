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
import io.streamshub.mcp.strimzi.dto.KafkaEffectiveConfigResponse;
import io.streamshub.mcp.strimzi.service.KafkaConfigService;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * MCP tools for Kafka cluster configuration inspection.
 */
@Singleton
@Guarded
@WrapBusinessError(Exception.class)
public class ConfigurationTools {

    @Inject
    KafkaConfigService kafkaConfigService;

    ConfigurationTools() {
    }

    /**
     * Get the effective configuration of a Kafka cluster.
     *
     * @param clusterName the Kafka cluster name
     * @param namespace   optional namespace
     * @return the effective configuration response
     */
    @Tool(
        name = "get_kafka_cluster_config",
        description = "Returns the effective configuration of a Kafka cluster"
            + " including broker config, resources, JVM options, listeners,"
            + " authorization, metrics, logging, Entity Operator,"
            + " Cruise Control, Kafka Exporter, and per-node-pool overrides."
            + " Resolves referenced ConfigMap content for metrics and logging.",
        annotations = @Tool.Annotations(
            readOnlyHint = true,
            destructiveHint = false,
            idempotentHint = true,
            openWorldHint = false
        )
    )
    public KafkaEffectiveConfigResponse getKafkaClusterConfig(
        @ToolArg(description = StrimziToolsPrompts.CLUSTER_DESC) final String clusterName,
        @ToolArg(description = StrimziToolsPrompts.NS_DESC, required = false) final String namespace
    ) {
        return kafkaConfigService.getEffectiveConfig(namespace, clusterName);
    }
}
