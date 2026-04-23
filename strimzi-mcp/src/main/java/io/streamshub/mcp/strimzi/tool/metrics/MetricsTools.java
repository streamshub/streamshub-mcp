/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.tool.metrics;

import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.quarkiverse.mcp.server.WrapBusinessError;
import io.streamshub.mcp.common.guardrail.Guarded;
import io.streamshub.mcp.common.guardrail.RateCategory;
import io.streamshub.mcp.strimzi.config.StrimziToolsPrompts;
import io.streamshub.mcp.strimzi.dto.metrics.KafkaExporterMetricsResponse;
import io.streamshub.mcp.strimzi.dto.metrics.KafkaMetricsResponse;
import io.streamshub.mcp.strimzi.dto.metrics.StrimziOperatorMetricsResponse;
import io.streamshub.mcp.strimzi.service.metrics.KafkaExporterMetricsService;
import io.streamshub.mcp.strimzi.service.metrics.KafkaMetricsService;
import io.streamshub.mcp.strimzi.service.metrics.StrimziOperatorMetricsService;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * MCP tools for metrics retrieval from Kafka clusters and Strimzi operators.
 */
@Singleton
@Guarded
@WrapBusinessError(Exception.class)
public class MetricsTools {

    @Inject
    KafkaMetricsService kafkaMetricsService;

    @Inject
    KafkaExporterMetricsService kafkaExporterMetricsService;

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
     * @param startTime    optional absolute start time in ISO 8601 format
     * @param endTime      optional absolute end time in ISO 8601 format
     * @param stepSeconds  optional range query step in seconds
     * @return the metrics response
     */
    @Tool(
        name = "get_kafka_metrics",
        description = "Retrieves Prometheus metrics from Kafka cluster pods by category or explicit metric names."
            + " Returns samples with an interpretation guide for thresholds and diagnostics.",
        annotations = @Tool.Annotations(
            readOnlyHint = true,
            destructiveHint = false,
            idempotentHint = true,
            openWorldHint = false
        )
    )
    @RateCategory("metrics")
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
            description = StrimziToolsPrompts.START_TIME_DESC,
            required = false
        ) final String startTime,
        @ToolArg(
            description = StrimziToolsPrompts.END_TIME_DESC,
            required = false
        ) final String endTime,
        @ToolArg(
            description = StrimziToolsPrompts.STEP_SECONDS_DESC,
            required = false
        ) final Integer stepSeconds
    ) {
        return kafkaMetricsService.getKafkaMetrics(
            namespace, clusterName, category, metricNames, rangeMinutes, startTime, endTime, stepSeconds);
    }

    /**
     * Retrieves metrics from Kafka Exporter pods.
     *
     * @param clusterName  the Kafka cluster name
     * @param namespace    optional namespace
     * @param category     optional metric category
     * @param metricNames  optional explicit metric names
     * @param rangeMinutes optional range duration in minutes
     * @param startTime    optional absolute start time in ISO 8601 format
     * @param endTime      optional absolute end time in ISO 8601 format
     * @param stepSeconds  optional range query step in seconds
     * @return the Kafka Exporter metrics response
     */
    @Tool(
        name = "get_kafka_exporter_metrics",
        description = "Retrieves Prometheus metrics from Kafka Exporter pods by category or explicit metric names."
            + " Returns consumer group lag, topic partition offsets, and JVM metrics with interpretation guide.",
        annotations = @Tool.Annotations(
            readOnlyHint = true,
            destructiveHint = false,
            idempotentHint = true,
            openWorldHint = false
        )
    )
    @RateCategory("metrics")
    public KafkaExporterMetricsResponse getKafkaExporterMetrics(
        @ToolArg(
            description = StrimziToolsPrompts.CLUSTER_DESC
        ) final String clusterName,
        @ToolArg(
            description = StrimziToolsPrompts.NS_DESC,
            required = false
        ) final String namespace,
        @ToolArg(
            description = StrimziToolsPrompts.EXPORTER_METRICS_CATEGORY_DESC,
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
            description = StrimziToolsPrompts.START_TIME_DESC,
            required = false
        ) final String startTime,
        @ToolArg(
            description = StrimziToolsPrompts.END_TIME_DESC,
            required = false
        ) final String endTime,
        @ToolArg(
            description = StrimziToolsPrompts.STEP_SECONDS_DESC,
            required = false
        ) final Integer stepSeconds
    ) {
        return kafkaExporterMetricsService.getKafkaExporterMetrics(
            namespace, clusterName, category, metricNames, rangeMinutes, startTime, endTime, stepSeconds);
    }

    /**
     * Retrieves metrics from Strimzi operator pods.
     * When {@code clusterName} is provided, also includes entity operator
     * (user-operator and topic-operator) metrics for that cluster.
     *
     * @param operatorName optional operator deployment name
     * @param namespace    optional namespace
     * @param clusterName  optional Kafka cluster name for entity operator metrics
     * @param category     optional metric category
     * @param metricNames  optional explicit metric names
     * @param rangeMinutes optional range duration in minutes
     * @param startTime    optional absolute start time in ISO 8601 format
     * @param endTime      optional absolute end time in ISO 8601 format
     * @param stepSeconds  optional range query step in seconds
     * @return the operator metrics response
     */
    @Tool(
        name = "get_strimzi_operator_metrics",
        description = "Retrieves Prometheus metrics from Strimzi operator pods by category or explicit metric names."
            + " When clusterName is provided, also includes entity operator (user-operator and topic-operator) metrics."
            + " Returns samples with an interpretation guide for thresholds and diagnostics.",
        annotations = @Tool.Annotations(
            readOnlyHint = true,
            destructiveHint = false,
            idempotentHint = true,
            openWorldHint = false
        )
    )
    @RateCategory("metrics")
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
            description = StrimziToolsPrompts.OPERATOR_CLUSTER_DESC,
            required = false
        ) final String clusterName,
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
            description = StrimziToolsPrompts.START_TIME_DESC,
            required = false
        ) final String startTime,
        @ToolArg(
            description = StrimziToolsPrompts.END_TIME_DESC,
            required = false
        ) final String endTime,
        @ToolArg(
            description = StrimziToolsPrompts.STEP_SECONDS_DESC,
            required = false
        ) final Integer stepSeconds
    ) {
        return strimziOperatorMetricsService.getOperatorMetrics(
            namespace, operatorName, clusterName, category, metricNames, rangeMinutes, startTime, endTime, stepSeconds);
    }
}
