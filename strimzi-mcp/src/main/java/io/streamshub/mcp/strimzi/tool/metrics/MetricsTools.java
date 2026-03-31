/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.tool.metrics;

import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.quarkiverse.mcp.server.WrapBusinessError;
import io.streamshub.mcp.strimzi.config.StrimziToolsPrompts;
import io.streamshub.mcp.strimzi.dto.metrics.KafkaMetricsResponse;
import io.streamshub.mcp.strimzi.dto.metrics.StrimziOperatorMetricsResponse;
import io.streamshub.mcp.strimzi.service.metrics.KafkaMetricsService;
import io.streamshub.mcp.strimzi.service.metrics.StrimziOperatorMetricsService;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * MCP tools for metrics retrieval from Kafka clusters and Strimzi operators.
 */
@Singleton
@WrapBusinessError(Exception.class)
public class MetricsTools {

    @Inject
    KafkaMetricsService kafkaMetricsService;

    @Inject
    StrimziOperatorMetricsService strimziOperatorMetricsService;

    MetricsTools() {
        // package-private no-arg constructor for CDI
    }

    /**
     * Retrieves metrics for a Kafka cluster.
     *
     * @param clusterName  the Kafka cluster name
     * @param namespace    optional namespace
     * @param category     optional metric category
     * @param metricNames  optional explicit metric names
     * @param rangeMinutes optional range duration in minutes
     * @param stepSeconds  optional range query step in seconds
     * @return the metrics response
     */
    @Tool(
        name = "get_kafka_metrics",
        description = "Retrieves Prometheus metrics from Kafka cluster pods."
            + " Returns metric samples by category or explicit metric names."
    )
    public KafkaMetricsResponse getKafkaMetrics(
        @ToolArg(
            description = StrimziToolsPrompts.CLUSTER_DESC
        ) final String clusterName,
        @ToolArg(
            description = StrimziToolsPrompts.NS_DESC,
            required = false
        ) final String namespace,
        @ToolArg(
            description = StrimziToolsPrompts.METRICS_CATEGORY_DESC,
            required = false
        ) final String category,
        @ToolArg(
            description = StrimziToolsPrompts.METRICS_NAMES_DESC,
            required = false
        ) final String metricNames,
        @ToolArg(
            description = StrimziToolsPrompts.RANGE_MINUTES_DESC,
            required = false
        ) final Integer rangeMinutes,
        @ToolArg(
            description = StrimziToolsPrompts.STEP_SECONDS_DESC,
            required = false
        ) final Integer stepSeconds
    ) {
        return kafkaMetricsService.getKafkaMetrics(
            namespace, clusterName, category, metricNames, rangeMinutes, stepSeconds);
    }

    /**
     * Retrieves metrics from Strimzi cluster operator pods.
     *
     * @param operatorName optional operator deployment name
     * @param namespace    optional namespace
     * @param category     optional metric category
     * @param metricNames  optional explicit metric names
     * @param rangeMinutes optional range duration in minutes
     * @param stepSeconds  optional range query step in seconds
     * @return the operator metrics response
     */
    @Tool(
        name = "get_strimzi_operator_metrics",
        description = "Retrieves Prometheus metrics from Strimzi cluster operator pods."
            + " Returns metric samples by category or explicit metric names."
    )
    public StrimziOperatorMetricsResponse getStrimziOperatorMetrics(
        @ToolArg(
            description = StrimziToolsPrompts.OPERATOR_NAME_DESC,
            required = false
        ) final String operatorName,
        @ToolArg(
            description = StrimziToolsPrompts.NS_DESC,
            required = false
        ) final String namespace,
        @ToolArg(
            description = StrimziToolsPrompts.OPERATOR_METRICS_CATEGORY_DESC,
            required = false
        ) final String category,
        @ToolArg(
            description = StrimziToolsPrompts.METRICS_NAMES_DESC,
            required = false
        ) final String metricNames,
        @ToolArg(
            description = StrimziToolsPrompts.RANGE_MINUTES_DESC,
            required = false
        ) final Integer rangeMinutes,
        @ToolArg(
            description = StrimziToolsPrompts.STEP_SECONDS_DESC,
            required = false
        ) final Integer stepSeconds
    ) {
        return strimziOperatorMetricsService.getOperatorMetrics(
            namespace, operatorName, category, metricNames, rangeMinutes, stepSeconds);
    }
}
