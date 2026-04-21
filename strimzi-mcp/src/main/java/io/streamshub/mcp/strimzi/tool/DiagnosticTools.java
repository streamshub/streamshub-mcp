/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.tool;

import io.quarkiverse.mcp.server.Cancellation;
import io.quarkiverse.mcp.server.Elicitation;
import io.quarkiverse.mcp.server.McpLog;
import io.quarkiverse.mcp.server.Progress;
import io.quarkiverse.mcp.server.Sampling;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.quarkiverse.mcp.server.WrapBusinessError;
import io.streamshub.mcp.common.guardrail.Guarded;
import io.streamshub.mcp.strimzi.config.StrimziToolsPrompts;
import io.streamshub.mcp.strimzi.dto.KafkaClusterDiagnosticReport;
import io.streamshub.mcp.strimzi.dto.KafkaConnectivityDiagnosticReport;
import io.streamshub.mcp.strimzi.dto.kafkaconnect.KafkaConnectorDiagnosticReport;
import io.streamshub.mcp.strimzi.dto.KafkaMetricsDiagnosticReport;
import io.streamshub.mcp.strimzi.dto.OperatorMetricsDiagnosticReport;
import io.streamshub.mcp.strimzi.service.KafkaClusterDiagnosticService;
import io.streamshub.mcp.strimzi.service.KafkaConnectivityDiagnosticService;
import io.streamshub.mcp.strimzi.service.kafkaconnect.KafkaConnectorDiagnosticService;
import io.streamshub.mcp.strimzi.service.KafkaMetricsDiagnosticService;
import io.streamshub.mcp.strimzi.service.OperatorMetricsDiagnosticService;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * MCP tool for composite Kafka cluster diagnosis.
 *
 * <p>Runs a multi-step diagnostic workflow in a single tool call,
 * using Sampling for LLM analysis and Elicitation for user input.</p>
 */
@Singleton
@Guarded
@WrapBusinessError(Exception.class)
public class DiagnosticTools {

    @Inject
    KafkaClusterDiagnosticService clusterDiagnosticService;

    @Inject
    KafkaConnectivityDiagnosticService connectivityDiagnosticService;

    @Inject
    KafkaMetricsDiagnosticService metricsDiagnosticService;

    @Inject
    OperatorMetricsDiagnosticService operatorMetricsDiagnosticService;

    @Inject
    KafkaConnectorDiagnosticService connectorDiagnosticService;

    DiagnosticTools() {
    }

    /**
     * Run a composite diagnostic workflow for a KafkaConnector.
     *
     * @param connectorName the KafkaConnector name
     * @param namespace     optional namespace
     * @param symptom       optional symptom description
     * @param sinceMinutes  optional time window for logs and events
     * @param sampling      MCP Sampling for LLM analysis
     * @param elicitation   MCP Elicitation for user input
     * @param mcpLog        MCP log for progress notifications
     * @param progress      MCP progress tracking
     * @param cancellation  MCP cancellation checking
     * @return a consolidated connector diagnostic report
     */
    @Tool(
        name = "diagnose_kafka_connector",
        description = "Runs a multi-step diagnostic workflow for a KafkaConnector."
            + " Gathers connector status, parent KafkaConnect cluster health,"
            + " Connect pod status, logs, and events in a single call."
            + " Uses Sampling for LLM analysis and Elicitation for disambiguation."
            + " Falls back to gathering all data when Sampling is not supported."
    )
    public KafkaConnectorDiagnosticReport diagnoseKafkaConnector(
        @ToolArg(
            description = StrimziToolsPrompts.CONNECTOR_NAME_DESC
        ) final String connectorName,
        @ToolArg(
            description = StrimziToolsPrompts.NS_DESC,
            required = false
        ) final String namespace,
        @ToolArg(
            description = StrimziToolsPrompts.SYMPTOM_DESC,
            required = false
        ) final String symptom,
        @ToolArg(
            description = StrimziToolsPrompts.SINCE_MINUTES_DESC,
            required = false
        ) final Integer sinceMinutes,
        final Sampling sampling,
        final Elicitation elicitation,
        final McpLog mcpLog,
        final Progress progress,
        final Cancellation cancellation
    ) {
        return connectorDiagnosticService.diagnose(
            namespace, connectorName, symptom, sinceMinutes,
            sampling, elicitation, mcpLog, progress, cancellation);
    }

    /**
     * Run a composite diagnostic workflow for a Kafka cluster.
     *
     * @param clusterName  the Kafka cluster name
     * @param namespace    optional namespace
     * @param symptom      optional symptom description
     * @param sinceMinutes optional time window for logs and events
     * @param sampling     MCP Sampling for LLM analysis
     * @param elicitation  MCP Elicitation for user input
     * @param mcpLog       MCP log for progress notifications
     * @param progress     MCP progress tracking
     * @param cancellation MCP cancellation checking
     * @return a consolidated diagnostic report
     */
    @Tool(
        name = "diagnose_kafka_cluster",
        description = "Runs a multi-step diagnostic workflow for a Kafka cluster."
            + " Gathers cluster status, node pools, pods, operator logs, cluster logs,"
            + " events, and metrics in a single call."
            + " Uses Sampling to get LLM analysis of intermediate results"
            + " and Elicitation to resolve ambiguity (e.g., multiple namespaces)."
            + " Falls back to gathering all data when Sampling is not supported.",
        annotations = @Tool.Annotations(
            readOnlyHint = true,
            destructiveHint = false,
            idempotentHint = true,
            openWorldHint = false
        )
    )
    public KafkaClusterDiagnosticReport diagnoseKafkaCluster(
        @ToolArg(
            description = StrimziToolsPrompts.CLUSTER_DESC
        ) final String clusterName,
        @ToolArg(
            description = StrimziToolsPrompts.NS_DESC,
            required = false
        ) final String namespace,
        @ToolArg(
            description = StrimziToolsPrompts.SYMPTOM_DESC,
            required = false
        ) final String symptom,
        @ToolArg(
            description = StrimziToolsPrompts.SINCE_MINUTES_DESC,
            required = false
        ) final Integer sinceMinutes,
        final Sampling sampling,
        final Elicitation elicitation,
        final McpLog mcpLog,
        final Progress progress,
        final Cancellation cancellation
    ) {
        return clusterDiagnosticService.diagnose(
            namespace, clusterName, symptom, sinceMinutes,
            sampling, elicitation, mcpLog, progress, cancellation);
    }

    /**
     * Run a composite connectivity diagnostic for a Kafka cluster.
     *
     * @param clusterName  the Kafka cluster name
     * @param namespace    optional namespace
     * @param listenerName optional listener to focus on
     * @param sampling     MCP Sampling for LLM analysis
     * @param elicitation  MCP Elicitation for user input
     * @param mcpLog       MCP log for progress notifications
     * @param progress     MCP progress tracking
     * @param cancellation MCP cancellation checking
     * @return a consolidated connectivity diagnostic report
     */
    @Tool(
        name = "diagnose_kafka_connectivity",
        description = "Runs a multi-step connectivity diagnostic for a Kafka cluster."
            + " Checks listeners, bootstrap addresses, TLS certificates,"
            + " authentication, pod health, and connection-related logs."
            + " Uses Sampling for analysis and Elicitation for disambiguation."
            + " Falls back to gathering all data when Sampling is not supported.",
        annotations = @Tool.Annotations(
            readOnlyHint = true,
            destructiveHint = false,
            idempotentHint = true,
            openWorldHint = false
        )
    )
    public KafkaConnectivityDiagnosticReport diagnoseKafkaConnectivity(
        @ToolArg(
            description = StrimziToolsPrompts.CLUSTER_DESC
        ) final String clusterName,
        @ToolArg(
            description = StrimziToolsPrompts.NS_DESC,
            required = false
        ) final String namespace,
        @ToolArg(
            description = StrimziToolsPrompts.LISTENER_DESC,
            required = false
        ) final String listenerName,
        final Sampling sampling,
        final Elicitation elicitation,
        final McpLog mcpLog,
        final Progress progress,
        final Cancellation cancellation
    ) {
        return connectivityDiagnosticService.diagnose(
            namespace, clusterName, listenerName,
            sampling, elicitation, mcpLog, progress, cancellation);
    }

    /**
     * Run a composite metrics diagnostic workflow for a Kafka cluster.
     *
     * @param clusterName  the Kafka cluster name
     * @param namespace    optional namespace
     * @param concern      optional concern description
     * @param rangeMinutes optional relative time range in minutes
     * @param startTime    optional absolute start time (ISO 8601)
     * @param endTime      optional absolute end time (ISO 8601)
     * @param stepSeconds  optional range query step interval in seconds
     * @param sampling     MCP Sampling for LLM analysis
     * @param elicitation  MCP Elicitation for user input
     * @param mcpLog       MCP log for progress notifications
     * @param progress     MCP progress tracking
     * @param cancellation MCP cancellation checking
     * @return a consolidated metrics diagnostic report
     */
    @Tool(
        name = "diagnose_kafka_metrics",
        description = "Runs a multi-step metrics diagnostic for a Kafka cluster."
            + " Gathers cluster status and pod health, then selectively queries"
            + " replication, performance, resource, and throughput metrics."
            + " Uses Sampling for analysis and Elicitation for disambiguation."
            + " Falls back to gathering all metric categories when Sampling is not supported.",
        annotations = @Tool.Annotations(
            readOnlyHint = true,
            destructiveHint = false,
            idempotentHint = true,
            openWorldHint = false
        )
    )
    public KafkaMetricsDiagnosticReport diagnoseKafkaMetrics(
        @ToolArg(
            description = StrimziToolsPrompts.CLUSTER_DESC
        ) final String clusterName,
        @ToolArg(
            description = StrimziToolsPrompts.NS_DESC,
            required = false
        ) final String namespace,
        @ToolArg(
            description = StrimziToolsPrompts.CONCERN_DESC,
            required = false
        ) final String concern,
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
        ) final Integer stepSeconds,
        final Sampling sampling,
        final Elicitation elicitation,
        final McpLog mcpLog,
        final Progress progress,
        final Cancellation cancellation
    ) {
        return metricsDiagnosticService.diagnose(
            namespace, clusterName, concern,
            rangeMinutes, startTime, endTime, stepSeconds,
            sampling, elicitation, mcpLog, progress, cancellation);
    }

    /**
     * Run a composite metrics diagnostic workflow for a Strimzi operator.
     *
     * @param namespace    optional namespace
     * @param operatorName optional operator deployment name
     * @param clusterName  optional Kafka cluster name for entity operator metrics
     * @param concern      optional concern description
     * @param rangeMinutes optional relative time range in minutes
     * @param startTime    optional absolute start time (ISO 8601)
     * @param endTime      optional absolute end time (ISO 8601)
     * @param stepSeconds  optional range query step interval in seconds
     * @param sampling     MCP Sampling for LLM analysis
     * @param mcpLog       MCP log for progress notifications
     * @param progress     MCP progress tracking
     * @param cancellation MCP cancellation checking
     * @return a consolidated operator metrics diagnostic report
     */
    @Tool(
        name = "diagnose_operator_metrics",
        description = "Runs a multi-step metrics diagnostic for a Strimzi operator."
            + " Gathers operator status, then selectively queries reconciliation,"
            + " resource, and JVM metrics along with operator logs."
            + " Uses Sampling for analysis."
            + " Falls back to gathering all data when Sampling is not supported.",
        annotations = @Tool.Annotations(
            readOnlyHint = true,
            destructiveHint = false,
            idempotentHint = true,
            openWorldHint = false
        )
    )
    public OperatorMetricsDiagnosticReport diagnoseOperatorMetrics(
        @ToolArg(
            description = StrimziToolsPrompts.NS_DESC,
            required = false
        ) final String namespace,
        @ToolArg(
            description = StrimziToolsPrompts.OPERATOR_NAME_DESC,
            required = false
        ) final String operatorName,
        @ToolArg(
            description = StrimziToolsPrompts.OPERATOR_CLUSTER_DESC,
            required = false
        ) final String clusterName,
        @ToolArg(
            description = StrimziToolsPrompts.CONCERN_DESC,
            required = false
        ) final String concern,
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
        ) final Integer stepSeconds,
        final Sampling sampling,
        final McpLog mcpLog,
        final Progress progress,
        final Cancellation cancellation
    ) {
        return operatorMetricsDiagnosticService.diagnose(
            namespace, operatorName, clusterName, concern,
            rangeMinutes, startTime, endTime, stepSeconds,
            sampling, mcpLog, progress, cancellation);
    }
}
