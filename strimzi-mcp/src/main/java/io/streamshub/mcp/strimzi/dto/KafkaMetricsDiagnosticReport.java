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
 * Consolidated diagnostic report from a multi-step Kafka metrics analysis.
 * Composes cluster status, pod health, and metrics from each category
 * into a single result for LLM-driven diagnosis.
 *
 * <p>Each field corresponds to one diagnostic step. Fields are null when the
 * step was skipped (Sampling-guided selective investigation) or failed
 * (recorded in {@code stepsFailed}).</p>
 *
 * @param cluster            Kafka cluster status and conditions
 * @param pods               pod health summaries (phase, restarts, termination reasons)
 * @param replicationMetrics replication metrics (under-replicated partitions, offline partitions, ISR)
 * @param performanceMetrics performance metrics (request handler idle, network processor idle, queue times)
 * @param resourceMetrics    resource metrics (JVM heap, GC, CPU, open file descriptors)
 * @param throughputMetrics  throughput metrics (bytes in/out, messages in per broker)
 * @param analysis           LLM-generated metrics analysis via Sampling, or null
 * @param stepsCompleted     diagnostic steps that completed successfully
 * @param stepsFailed        diagnostic steps that failed with error descriptions
 * @param timestamp          when this report was generated
 * @param message            human-readable summary of the diagnostic run
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record KafkaMetricsDiagnosticReport(
    @JsonProperty("cluster") KafkaClusterResponse cluster,
    @JsonProperty("pods") KafkaClusterPodsResponse pods,
    @JsonProperty("replication_metrics") KafkaMetricsResponse replicationMetrics,
    @JsonProperty("performance_metrics") KafkaMetricsResponse performanceMetrics,
    @JsonProperty("resource_metrics") KafkaMetricsResponse resourceMetrics,
    @JsonProperty("throughput_metrics") KafkaMetricsResponse throughputMetrics,
    @JsonProperty("analysis") String analysis,
    @JsonProperty("steps_completed") List<String> stepsCompleted,
    @JsonProperty("steps_failed") List<String> stepsFailed,
    @JsonProperty("timestamp") Instant timestamp,
    @JsonProperty("message") String message
) {

    /**
     * Creates a metrics diagnostic report with gathered data and step tracking.
     *
     * @param cluster            cluster status response
     * @param pods               pod health response
     * @param replicationMetrics replication metrics response
     * @param performanceMetrics performance metrics response
     * @param resourceMetrics    resource metrics response
     * @param throughputMetrics  throughput metrics response
     * @param analysis           LLM analysis text or null
     * @param stepsCompleted     steps that succeeded
     * @param stepsFailed        steps that failed with reasons
     * @return a new metrics diagnostic report
     */
    @SuppressWarnings("checkstyle:ParameterNumber")
    public static KafkaMetricsDiagnosticReport of(KafkaClusterResponse cluster,
                                                  KafkaClusterPodsResponse pods,
                                                  KafkaMetricsResponse replicationMetrics,
                                                  KafkaMetricsResponse performanceMetrics,
                                                  KafkaMetricsResponse resourceMetrics,
                                                  KafkaMetricsResponse throughputMetrics,
                                                  String analysis,
                                                  List<String> stepsCompleted,
                                                  List<String> stepsFailed) {
        int completed = stepsCompleted != null ? stepsCompleted.size() : 0;
        int failed = stepsFailed != null ? stepsFailed.size() : 0;
        String clusterName = cluster != null ? cluster.name() : "unknown";

        String msg;
        if (failed == 0) {
            msg = String.format("Metrics diagnostic completed for cluster '%s': %d steps succeeded",
                clusterName, completed);
        } else {
            msg = String.format(
                "Metrics diagnostic completed for cluster '%s': %d steps succeeded, %d steps failed",
                clusterName, completed, failed);
        }

        return new KafkaMetricsDiagnosticReport(cluster, pods,
            replicationMetrics, performanceMetrics, resourceMetrics, throughputMetrics,
            analysis, stepsCompleted, stepsFailed, Instant.now(), msg);
    }
}
