/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.streamshub.mcp.strimzi.dto.metrics.KafkaExporterMetricsResponse;

import java.time.Instant;
import java.util.List;

/**
 * Consolidated diagnostic report for a KafkaTopic issue.
 * Composes data from multiple services gathered during a single diagnostic workflow.
 *
 * @param topic           the topic status and configuration
 * @param relatedTopics   summary of all topics for scope detection
 * @param cluster         the parent Kafka cluster status
 * @param operatorLogs    the Strimzi operator logs filtered for topic
 * @param events          related Kubernetes events
 * @param exporterMetrics Kafka Exporter partition metrics
 * @param analysis        LLM-generated root cause analysis (null if Sampling not supported)
 * @param stepsCompleted  the list of successfully completed diagnostic steps
 * @param stepsFailed     the list of failed diagnostic steps with error messages
 * @param timestamp       the time this report was generated
 * @param message         a human-readable summary
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record KafkaTopicDiagnosticReport(
    @JsonProperty("topic") KafkaTopicResponse topic,
    @JsonProperty("related_topics") KafkaTopicListResponse relatedTopics,
    @JsonProperty("cluster") KafkaClusterResponse cluster,
    @JsonProperty("operator_logs") StrimziOperatorLogsResponse operatorLogs,
    @JsonProperty("events") StrimziEventsResponse events,
    @JsonProperty("exporter_metrics") KafkaExporterMetricsResponse exporterMetrics,
    @JsonProperty("analysis") String analysis,
    @JsonProperty("steps_completed") List<String> stepsCompleted,
    @JsonProperty("steps_failed") List<String> stepsFailed,
    @JsonProperty("timestamp") Instant timestamp,
    @JsonProperty("message") String message
) {

    /**
     * Creates a diagnostic report with all gathered data.
     *
     * @param topic           the topic status
     * @param relatedTopics   the related topics summary
     * @param cluster         the parent cluster status
     * @param operatorLogs    the operator logs
     * @param events          the related events
     * @param exporterMetrics the Kafka Exporter metrics
     * @param analysis        the LLM analysis
     * @param stepsCompleted  the completed steps
     * @param stepsFailed     the failed steps (null if none)
     * @return a new diagnostic report
     */
    @SuppressWarnings("checkstyle:ParameterNumber")
    public static KafkaTopicDiagnosticReport of(final KafkaTopicResponse topic,
                                                 final KafkaTopicListResponse relatedTopics,
                                                 final KafkaClusterResponse cluster,
                                                 final StrimziOperatorLogsResponse operatorLogs,
                                                 final StrimziEventsResponse events,
                                                 final KafkaExporterMetricsResponse exporterMetrics,
                                                 final String analysis,
                                                 final List<String> stepsCompleted,
                                                 final List<String> stepsFailed) {
        String msg = String.format("Diagnostic completed: %d steps succeeded, %d steps failed",
            stepsCompleted.size(), stepsFailed != null ? stepsFailed.size() : 0);
        return new KafkaTopicDiagnosticReport(topic, relatedTopics, cluster, operatorLogs,
            events, exporterMetrics, analysis, stepsCompleted, stepsFailed, Instant.now(), msg);
    }
}
