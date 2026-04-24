/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.tool.kafkaconnect;

import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.quarkiverse.mcp.server.WrapBusinessError;
import io.streamshub.mcp.common.guardrail.Guarded;
import io.streamshub.mcp.strimzi.config.StrimziToolsPrompts;
import io.streamshub.mcp.strimzi.dto.kafkaconnect.KafkaConnectorResponse;
import io.streamshub.mcp.strimzi.service.kafkaconnect.KafkaConnectorService;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.util.List;

/**
 * MCP tools for KafkaConnector operations.
 */
@Singleton
@Guarded
@WrapBusinessError(Exception.class)
public class KafkaConnectorTools {

    @Inject
    KafkaConnectorService connectorService;

    KafkaConnectorTools() {
    }

    /**
     * List KafkaConnectors.
     *
     * @param namespace      optional namespace filter
     * @param connectCluster optional parent KafkaConnect cluster filter
     * @return list of connector responses
     */
    @Tool(
        name = "list_kafka_connectors",
        description = "List KafkaConnectors with class, state,"
            + " and task configuration. Optionally filter by"
            + " namespace or parent KafkaConnect cluster."
    )
    public List<KafkaConnectorResponse> listKafkaConnectors(
        @ToolArg(
            description = StrimziToolsPrompts.NS_DESC,
            required = false
        ) final String namespace,
        @ToolArg(
            description = StrimziToolsPrompts.CONNECT_CLUSTER_FILTER_DESC,
            required = false
        ) final String connectCluster
    ) {
        return connectorService.listConnectors(namespace, connectCluster);
    }

    /**
     * Get a specific KafkaConnector.
     *
     * @param connectorName the connector name
     * @param namespace     optional namespace
     * @return the connector response
     */
    @Tool(
        name = "get_kafka_connector",
        description = "Get detailed information about a specific"
            + " KafkaConnector including class, state, tasks,"
            + " auto-restart status, topics, and configuration."
    )
    public KafkaConnectorResponse getKafkaConnector(
        @ToolArg(
            description = StrimziToolsPrompts.CONNECTOR_NAME_DESC
        ) final String connectorName,
        @ToolArg(
            description = StrimziToolsPrompts.NS_DESC,
            required = false
        ) final String namespace
    ) {
        return connectorService.getConnector(namespace, connectorName);
    }
}
