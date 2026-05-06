/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.streamshub.mcp.strimzi.dto.metrics.KafkaMetricsResponse;

import java.time.Instant;
import java.util.List;

/**
 * Consolidated report for Kafka cluster upgrade readiness assessment.
 * Composes pre-flight check results from multiple services in a single workflow.
 *
 * @param cluster             the cluster status and version
 * @param operator            the Strimzi operator status
 * @param nodePools           the node pool statuses
 * @param pods                the cluster pod health
 * @param replicationMetrics  replication health metrics
 * @param performanceMetrics  broker performance metrics (headroom check)
 * @param resourceMetrics     JVM and resource metrics
 * @param drainCleaner        Drain Cleaner readiness status
 * @param certificates        certificate expiry information
 * @param events              recent Kubernetes events
 * @param analysis            LLM-generated GO/NO-GO verdict (null if Sampling not supported)
 * @param stepsCompleted      the list of successfully completed check steps
 * @param stepsFailed         the list of failed check steps with error messages
 * @param timestamp           the time this report was generated
 * @param message             a human-readable summary
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record UpgradeReadinessReport(
    @JsonProperty("cluster") KafkaClusterResponse cluster,
    @JsonProperty("operator") StrimziOperatorResponse operator,
    @JsonProperty("node_pools") List<KafkaNodePoolResponse> nodePools,
    @JsonProperty("pods") KafkaClusterPodsResponse pods,
    @JsonProperty("replication_metrics") KafkaMetricsResponse replicationMetrics,
    @JsonProperty("performance_metrics") KafkaMetricsResponse performanceMetrics,
    @JsonProperty("resource_metrics") KafkaMetricsResponse resourceMetrics,
    @JsonProperty("drain_cleaner") DrainCleanerReadinessResponse drainCleaner,
    @JsonProperty("certificates") KafkaCertificateResponse certificates,
    @JsonProperty("events") StrimziEventsResponse events,
    @JsonProperty("analysis") String analysis,
    @JsonProperty("steps_completed") List<String> stepsCompleted,
    @JsonProperty("steps_failed") List<String> stepsFailed,
    @JsonProperty("timestamp") Instant timestamp,
    @JsonProperty("message") String message
) {

    /**
     * Creates an upgrade readiness report with all gathered data.
     *
     * @param cluster            the cluster status
     * @param operator           the operator status
     * @param nodePools          the node pool statuses
     * @param pods               the pod health
     * @param replicationMetrics the replication metrics
     * @param performanceMetrics the performance metrics
     * @param resourceMetrics    the resource metrics
     * @param drainCleaner       the drain cleaner status
     * @param certificates       the certificate info
     * @param events             the Kubernetes events
     * @param analysis           the LLM verdict
     * @param stepsCompleted     the completed steps
     * @param stepsFailed        the failed steps (null if none)
     * @return a new readiness report
     */
    @SuppressWarnings("checkstyle:ParameterNumber")
    public static UpgradeReadinessReport of(final KafkaClusterResponse cluster,
                                             final StrimziOperatorResponse operator,
                                             final List<KafkaNodePoolResponse> nodePools,
                                             final KafkaClusterPodsResponse pods,
                                             final KafkaMetricsResponse replicationMetrics,
                                             final KafkaMetricsResponse performanceMetrics,
                                             final KafkaMetricsResponse resourceMetrics,
                                             final DrainCleanerReadinessResponse drainCleaner,
                                             final KafkaCertificateResponse certificates,
                                             final StrimziEventsResponse events,
                                             final String analysis,
                                             final List<String> stepsCompleted,
                                             final List<String> stepsFailed) {
        String msg = String.format("Upgrade readiness check completed: %d steps succeeded, %d steps failed",
            stepsCompleted.size(), stepsFailed != null ? stepsFailed.size() : 0);
        return new UpgradeReadinessReport(cluster, operator, nodePools, pods,
            replicationMetrics, performanceMetrics, resourceMetrics,
            drainCleaner, certificates, events,
            analysis, stepsCompleted, stepsFailed, Instant.now(), msg);
    }
}
