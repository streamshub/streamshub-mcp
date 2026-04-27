/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.tool;

import io.opentelemetry.instrumentation.annotations.WithSpan;
import io.quarkiverse.mcp.server.Cancellation;
import io.quarkiverse.mcp.server.McpLog;
import io.quarkiverse.mcp.server.Progress;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.quarkiverse.mcp.server.WrapBusinessError;
import io.streamshub.mcp.common.dto.LogCollectionParams;
import io.streamshub.mcp.common.guardrail.Guarded;
import io.streamshub.mcp.common.guardrail.RateCategory;
import io.streamshub.mcp.common.util.TimeRangeValidator;
import io.streamshub.mcp.strimzi.config.StrimziToolsPrompts;
import io.streamshub.mcp.strimzi.dto.KafkaBootstrapResponse;
import io.streamshub.mcp.strimzi.dto.KafkaCertificateResponse;
import io.streamshub.mcp.strimzi.dto.KafkaClusterLogsResponse;
import io.streamshub.mcp.strimzi.dto.KafkaClusterPodsResponse;
import io.streamshub.mcp.strimzi.dto.KafkaClusterResponse;
import io.streamshub.mcp.strimzi.service.KafkaCertificateService;
import io.streamshub.mcp.strimzi.service.KafkaService;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.List;

/**
 * MCP tools for Kafka cluster operations.
 */
@Singleton
@Guarded
@WrapBusinessError(Exception.class)
public class KafkaTools {

    private static final int SECONDS_PER_MINUTE = 60;

    @Inject
    KafkaService kafkaService;

    @Inject
    KafkaCertificateService kafkaCertificateService;

    @ConfigProperty(name = "mcp.log.tail-lines", defaultValue = "200")
    int defaultTailLines;

    KafkaTools() {
    }

    /**
     * List Kafka clusters.
     *
     * @param namespace optional namespace filter
     * @return list of cluster responses
     */
    @WithSpan("tool.list_kafka_clusters")
    @Tool(
        name = "list_kafka_clusters",
        description = "List Kafka clusters with status"
            + " and configuration."
            + " Optionally filter by namespace.",
        annotations = @Tool.Annotations(
            readOnlyHint = true,
            destructiveHint = false,
            idempotentHint = true,
            openWorldHint = false
        )
    )
    public List<KafkaClusterResponse> listKafkaClusters(
        @ToolArg(
            description = StrimziToolsPrompts.NS_DESC,
            required = false
        ) final String namespace
    ) {
        return kafkaService.listClusters(namespace);
    }

    /**
     * Get a specific Kafka cluster.
     *
     * @param clusterName the cluster name
     * @param namespace   optional namespace
     * @return the cluster response
     */
    @WithSpan("tool.get_kafka_cluster")
    @Tool(
        name = "get_kafka_cluster",
        description = "Get detailed information about"
            + " a specific Kafka cluster including"
            + " status, version, and configuration.",
        annotations = @Tool.Annotations(
            readOnlyHint = true,
            destructiveHint = false,
            idempotentHint = true,
            openWorldHint = false
        )
    )
    public KafkaClusterResponse getKafkaCluster(
        @ToolArg(
            description = StrimziToolsPrompts.CLUSTER_DESC
        ) final String clusterName,
        @ToolArg(
            description = StrimziToolsPrompts.NS_DESC,
            required = false
        ) final String namespace
    ) {
        return kafkaService.getCluster(namespace, clusterName);
    }

    /**
     * Get pods for a Kafka cluster.
     *
     * @param clusterName the cluster name
     * @param namespace   optional namespace
     * @return the cluster pods response
     */
    @WithSpan("tool.get_kafka_cluster_pods")
    @Tool(
        name = "get_kafka_cluster_pods",
        description = "Get pod summaries for a Kafka"
            + " cluster with component type, phase,"
            + " readiness, restarts, and age.",
        annotations = @Tool.Annotations(
            readOnlyHint = true,
            destructiveHint = false,
            idempotentHint = true,
            openWorldHint = false
        )
    )
    public KafkaClusterPodsResponse getKafkaClusterPods(
        @ToolArg(
            description = StrimziToolsPrompts.CLUSTER_DESC
        ) final String clusterName,
        @ToolArg(
            description = StrimziToolsPrompts.NS_DESC,
            required = false
        ) final String namespace
    ) {
        return kafkaService.getClusterPods(
            namespace, clusterName
        );
    }

    /**
     * Get bootstrap server addresses for a Kafka cluster.
     *
     * @param clusterName the cluster name
     * @param namespace   optional namespace
     * @return the bootstrap response with listener addresses
     */
    @WithSpan("tool.get_kafka_bootstrap_servers")
    @Tool(
        name = "get_kafka_bootstrap_servers",
        description = "Get bootstrap server addresses for a Kafka cluster"
            + " with listener names, types, hosts, and ports.",
        annotations = @Tool.Annotations(
            readOnlyHint = true,
            destructiveHint = false,
            idempotentHint = true,
            openWorldHint = false
        )
    )
    public KafkaBootstrapResponse getKafkaBootstrapServers(
        @ToolArg(
            description = StrimziToolsPrompts.CLUSTER_DESC
        ) final String clusterName,
        @ToolArg(
            description = StrimziToolsPrompts.NS_DESC,
            required = false
        ) final String namespace
    ) {
        return kafkaService.getBootstrapServers(namespace, clusterName);
    }

    /**
     * Get TLS certificate metadata and listener authentication for a Kafka cluster.
     *
     * @param clusterName  the cluster name
     * @param namespace    optional namespace
     * @param listenerName optional listener name filter
     * @return the certificate and authentication response
     */
    @WithSpan("tool.get_kafka_cluster_certificates")
    @Tool(
        name = "get_kafka_cluster_certificates",
        description = "Returns TLS certificate metadata and listener authentication"
            + " configuration for a Kafka cluster."
            + " Includes certificate expiry dates, issuers, and SANs from"
            + " Strimzi-managed secrets. Requires opt-in sensitive RBAC Role.",
        annotations = @Tool.Annotations(
            readOnlyHint = true,
            destructiveHint = false,
            idempotentHint = true,
            openWorldHint = false
        )
    )
    public KafkaCertificateResponse getKafkaClusterCertificates(
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
        ) final String listenerName
    ) {
        return kafkaCertificateService.getCertificates(namespace, clusterName, listenerName);
    }

    /**
     * Get logs from Kafka cluster pods.
     *
     * @param clusterName  the cluster name
     * @param namespace    optional namespace
     * @param filter       optional log filter
     * @param keywords     optional keyword list for filtering
     * @param sinceMinutes optional time range in minutes
     * @param startTime    optional absolute start time (ISO 8601)
     * @param endTime      optional absolute end time (ISO 8601)
     * @param tailLines    optional number of lines to tail
     * @param previous     optional flag for previous container logs
     * @param mcpLog       MCP log for client notifications
     * @param progress     MCP progress tracking
     * @param cancellation MCP cancellation checking
     * @return the cluster logs response with error analysis
     */
    @WithSpan("tool.get_kafka_cluster_logs")
    @Tool(
        name = "get_kafka_cluster_logs",
        description = "Get logs from Kafka cluster pods with error analysis."
            + " Returns logs from all pods belonging to the cluster.",
        annotations = @Tool.Annotations(
            readOnlyHint = true,
            destructiveHint = false,
            idempotentHint = true,
            openWorldHint = false
        )
    )
    @RateCategory("log")
    public KafkaClusterLogsResponse getKafkaClusterLogs(
        @ToolArg(
            description = StrimziToolsPrompts.CLUSTER_DESC
        ) final String clusterName,
        @ToolArg(
            description = StrimziToolsPrompts.NS_DESC,
            required = false
        ) final String namespace,
        @ToolArg(
            description = StrimziToolsPrompts.LOG_FILTER_DESC,
            required = false
        ) final String filter,
        @ToolArg(
            description = StrimziToolsPrompts.KEYWORDS_DESC,
            required = false
        ) final List<String> keywords,
        @ToolArg(
            description = StrimziToolsPrompts.SINCE_MINUTES_DESC,
            required = false
        ) final Integer sinceMinutes,
        @ToolArg(
            description = StrimziToolsPrompts.START_TIME_DESC,
            required = false
        ) final String startTime,
        @ToolArg(
            description = StrimziToolsPrompts.END_TIME_DESC,
            required = false
        ) final String endTime,
        @ToolArg(
            description = StrimziToolsPrompts.TAIL_LINES_DESC,
            required = false
        ) final Integer tailLines,
        @ToolArg(
            description = StrimziToolsPrompts.PREVIOUS_DESC,
            required = false
        ) final Boolean previous,
        final McpLog mcpLog,
        final Progress progress,
        final Cancellation cancellation
    ) {
        TimeRangeValidator.validateTimeRangeParameters(sinceMinutes, startTime, endTime);
        LogCollectionParams options = LogCollectionParams.builder(
                tailLines != null ? tailLines : defaultTailLines)
            .filter(filter)
            .keywords(keywords)
            .sinceSeconds(sinceMinutes != null ? sinceMinutes * SECONDS_PER_MINUTE : null)
            .startTime(startTime)
            .endTime(endTime)
            .previous(previous)
            .notifier(mcpLog::info)
            .cancelCheck(cancellation::skipProcessingIfCancelled)
            // Only send progress if the client provided a progress token
            .progressCallback(progress.token().isPresent()
                ? (completed, total) -> progress.notificationBuilder()
                    .setProgress(completed).setTotal(total)
                    .setMessage(String.format("Collected logs from %d/%d pods", completed, total))
                    .build().sendAndForget()
                : null)
            .build();
        return kafkaService.getClusterLogs(namespace, clusterName, options);
    }
}
