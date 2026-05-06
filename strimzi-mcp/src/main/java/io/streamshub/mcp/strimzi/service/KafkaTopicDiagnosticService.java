/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import io.quarkiverse.mcp.server.Cancellation;
import io.quarkiverse.mcp.server.Elicitation;
import io.quarkiverse.mcp.server.McpLog;
import io.quarkiverse.mcp.server.Progress;
import io.quarkiverse.mcp.server.Sampling;
import io.quarkiverse.mcp.server.SamplingMessage;
import io.quarkiverse.mcp.server.SamplingResponse;
import io.quarkiverse.mcp.server.ToolCallException;
import io.streamshub.mcp.common.dto.LogCollectionParams;
import io.streamshub.mcp.common.service.DiagnosticHelper;
import io.streamshub.mcp.common.util.InputUtils;
import io.streamshub.mcp.common.util.NamespaceElicitationHelper;
import io.streamshub.mcp.strimzi.dto.KafkaClusterResponse;
import io.streamshub.mcp.strimzi.dto.KafkaTopicDiagnosticReport;
import io.streamshub.mcp.strimzi.dto.KafkaTopicListResponse;
import io.streamshub.mcp.strimzi.dto.KafkaTopicResponse;
import io.streamshub.mcp.strimzi.dto.StrimziEventsResponse;
import io.streamshub.mcp.strimzi.dto.StrimziOperatorLogsResponse;
import io.streamshub.mcp.strimzi.dto.metrics.KafkaExporterMetricsResponse;
import io.streamshub.mcp.strimzi.service.metrics.KafkaExporterMetricsService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Orchestrates a multistep diagnostic workflow for KafkaTopics.
 *
 * <p>Gathers data from the topic, related topics, parent Kafka cluster,
 * operator logs, events, and exporter metrics in a single operation.
 * Optionally uses MCP Sampling for LLM triage and analysis.</p>
 *
 * <p>Individual step failures do not abort the workflow. Failed steps are
 * recorded and the report includes all data that was successfully gathered.</p>
 */
@ApplicationScoped
public class KafkaTopicDiagnosticService {

    private static final Logger LOG = Logger.getLogger(KafkaTopicDiagnosticService.class);
    private static final int PHASE1_STEPS = 3;
    private static final int MAX_PHASE2_STEPS = 3;
    private static final String DIAGNOSTIC_LABEL = "KafkaTopic diagnostic";

    private static final String STEP_TOPIC_STATUS = "topic_status";
    private static final String STEP_RELATED_TOPICS = "related_topics";
    private static final String STEP_CLUSTER_STATUS = "cluster_status";
    private static final String STEP_OPERATOR_LOGS = "operator_logs";
    private static final String STEP_EVENTS = "events";
    private static final String STEP_EXPORTER_METRICS = "exporter_metrics";

    @Inject
    KafkaTopicService topicService;

    @Inject
    KafkaService kafkaService;

    @Inject
    StrimziEventsService eventsService;

    @Inject
    StrimziOperatorService operatorService;

    @Inject
    KafkaExporterMetricsService exporterMetricsService;

    @Inject
    ObjectMapper objectMapper;

    @ConfigProperty(name = "mcp.log.tail-lines", defaultValue = "200")
    int defaultTailLines;

    @ConfigProperty(name = "mcp.sampling.triage-max-tokens", defaultValue = "200")
    int triageMaxTokens;

    @ConfigProperty(name = "mcp.sampling.analysis-max-tokens", defaultValue = "1500")
    int analysisMaxTokens;

    KafkaTopicDiagnosticService() {
    }

    /**
     * Run a multistep diagnostic workflow for a KafkaTopic.
     *
     * <p>Phase 1 gathers topic status, related topics, and parent cluster status.
     * Phase 2 uses Sampling (if supported) to decide which areas need deeper
     * investigation, then gathers operator logs, events, and exporter metrics.
     * Phase 3 uses Sampling to produce a root cause analysis.</p>
     *
     * @param namespace    optional namespace (elicited if ambiguous)
     * @param topicName    the KafkaTopic name
     * @param clusterName  optional Kafka cluster name
     * @param symptom      optional symptom description for context
     * @param sampling     MCP Sampling for LLM analysis (may be unsupported)
     * @param elicitation  MCP Elicitation for user input (may be unsupported)
     * @param mcpLog       MCP log for progress notifications
     * @param progress     MCP progress tracking
     * @param cancellation MCP cancellation checking
     * @return a consolidated diagnostic report
     */
    @SuppressWarnings("checkstyle:ParameterNumber")
    public KafkaTopicDiagnosticReport diagnose(final String namespace,
                                                final String topicName,
                                                final String clusterName,
                                                final String symptom,
                                                final Sampling sampling,
                                                final Elicitation elicitation,
                                                final McpLog mcpLog,
                                                final Progress progress,
                                                final Cancellation cancellation) {
        String ns = InputUtils.normalizeInput(namespace);
        String name = InputUtils.normalizeInput(topicName);
        String cluster = InputUtils.normalizeInput(clusterName);

        if (name == null) {
            throw new ToolCallException("Topic name is required");
        }

        LOG.infof("Starting diagnostic for KafkaTopic=%s (namespace=%s, cluster=%s, symptom=%s)",
            name, ns != null ? ns : "auto", cluster != null ? cluster : "auto", symptom);

        List<String> completed = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        int stepIndex = 0;

        // === Phase 1: Initial data gathering ===
        int maxSteps = PHASE1_STEPS + MAX_PHASE2_STEPS;
        KafkaTopicResponse topic = gatherTopicStatus(
            ns, cluster, name, elicitation, completed, mcpLog);
        DiagnosticHelper.sendProgress(progress, ++stepIndex, maxSteps, DIAGNOSTIC_LABEL);
        DiagnosticHelper.checkCancellation(cancellation);

        String resolvedCluster = cluster != null ? cluster : topic.cluster();

        KafkaTopicListResponse relatedTopics = gatherRelatedTopics(
            ns, resolvedCluster, completed, failed, mcpLog);
        DiagnosticHelper.sendProgress(progress, ++stepIndex, maxSteps, DIAGNOSTIC_LABEL);
        DiagnosticHelper.checkCancellation(cancellation);

        KafkaClusterResponse clusterStatus = gatherClusterStatus(
            ns, resolvedCluster, completed, failed, mcpLog);
        DiagnosticHelper.sendProgress(progress, ++stepIndex, maxSteps, DIAGNOSTIC_LABEL);
        DiagnosticHelper.checkCancellation(cancellation);

        String resolvedNs = clusterStatus != null ? clusterStatus.namespace() : ns;

        // === Phase 2: Deep investigation ===
        InvestigationAreas areas = investigateAreas(
            sampling, topic, relatedTopics, clusterStatus, symptom);

        int totalSteps = PHASE1_STEPS + areas.enabledCount();

        StrimziOperatorLogsResponse operatorLogs = null;
        if (areas.operatorLogs) {
            operatorLogs = gatherOperatorLogs(
                resolvedNs, name, completed, failed, mcpLog);
            DiagnosticHelper.sendProgress(progress, ++stepIndex, totalSteps, DIAGNOSTIC_LABEL);
            DiagnosticHelper.checkCancellation(cancellation);
        }

        StrimziEventsResponse events = null;
        if (areas.events) {
            events = gatherEvents(
                resolvedNs, resolvedCluster, completed, failed, mcpLog);
            DiagnosticHelper.sendProgress(progress, ++stepIndex, totalSteps, DIAGNOSTIC_LABEL);
            DiagnosticHelper.checkCancellation(cancellation);
        }

        KafkaExporterMetricsResponse exporterMetrics = null;
        if (areas.exporterMetrics) {
            exporterMetrics = gatherExporterMetrics(
                resolvedNs, resolvedCluster, completed, failed, mcpLog);
            DiagnosticHelper.sendProgress(progress, ++stepIndex, totalSteps, DIAGNOSTIC_LABEL);
            DiagnosticHelper.checkCancellation(cancellation);
        }

        // === Phase 3: Final analysis ===
        String analysis = produceAnalysis(sampling, topic, relatedTopics, clusterStatus,
            operatorLogs, events, exporterMetrics, symptom);

        return KafkaTopicDiagnosticReport.of(topic, relatedTopics, clusterStatus, operatorLogs,
            events, exporterMetrics, analysis, completed, failed.isEmpty() ? null : failed);
    }

    // ---- Phase 1: Initial data gathering ----

    @WithSpan("diagnose.topic.status")
    KafkaTopicResponse gatherTopicStatus(final String namespace,
                                          final String clusterName,
                                          final String topicName,
                                          final Elicitation elicitation,
                                          final List<String> completed,
                                          final McpLog mcpLog) {
        try {
            KafkaTopicResponse result = topicService.getTopic(namespace, clusterName, topicName);
            completed.add(STEP_TOPIC_STATUS);
            DiagnosticHelper.sendClientNotification(mcpLog,
                "Checked KafkaTopic status: " + result.status()
                    + " (partitions: " + result.partitions() + ", replicas: " + result.replicas() + ")");
            return result;
        } catch (ToolCallException e) {
            if (NamespaceElicitationHelper.isMultipleNamespacesError(e)
                    && elicitation != null && elicitation.isSupported()) {
                String resolved = NamespaceElicitationHelper.elicitNamespace(e, elicitation, "diagnosed");
                return gatherTopicStatus(resolved, clusterName, topicName, null, completed, mcpLog);
            }
            throw e;
        }
    }

    @WithSpan("diagnose.topic.related_topics")
    KafkaTopicListResponse gatherRelatedTopics(final String namespace,
                                                final String clusterName,
                                                final List<String> completed,
                                                final List<String> failed,
                                                final McpLog mcpLog) {
        if (clusterName == null) {
            failed.add(STEP_RELATED_TOPICS + ": no cluster name available");
            return null;
        }
        try {
            KafkaTopicListResponse result = topicService.listTopics(namespace, clusterName, null, null);
            completed.add(STEP_RELATED_TOPICS);
            DiagnosticHelper.sendClientNotification(mcpLog,
                String.format("Found %d related topics for cluster '%s'", result.total(), clusterName));
            return result;
        } catch (Exception e) {
            LOG.warnf("Failed to gather related topics: %s", e.getMessage());
            failed.add(STEP_RELATED_TOPICS + ": " + e.getMessage());
            return null;
        }
    }

    @WithSpan("diagnose.topic.cluster_status")
    KafkaClusterResponse gatherClusterStatus(final String namespace,
                                              final String clusterName,
                                              final List<String> completed,
                                              final List<String> failed,
                                              final McpLog mcpLog) {
        if (clusterName == null) {
            failed.add(STEP_CLUSTER_STATUS + ": no strimzi.io/cluster label on topic");
            return null;
        }
        try {
            KafkaClusterResponse result = kafkaService.getCluster(namespace, clusterName);
            completed.add(STEP_CLUSTER_STATUS);
            DiagnosticHelper.sendClientNotification(mcpLog,
                "Checked Kafka cluster '" + clusterName + "' status: " + result.readiness());
            return result;
        } catch (Exception e) {
            LOG.warnf("Failed to gather Kafka cluster status: %s", e.getMessage());
            failed.add(STEP_CLUSTER_STATUS + ": " + e.getMessage());
            return null;
        }
    }

    // ---- Phase 2: Deep investigation ----

    @WithSpan("diagnose.topic.operator_logs")
    StrimziOperatorLogsResponse gatherOperatorLogs(final String namespace,
                                                    final String topicName,
                                                    final List<String> completed,
                                                    final List<String> failed,
                                                    final McpLog mcpLog) {
        try {
            LogCollectionParams params = LogCollectionParams.builder(defaultTailLines)
                .filter("errors")
                .keywords(List.of(topicName))
                .build();

            StrimziOperatorLogsResponse result = operatorService.getOperatorLogs(
                namespace, null, params);
            completed.add(STEP_OPERATOR_LOGS);
            DiagnosticHelper.sendClientNotification(mcpLog, "Collected Strimzi operator logs");
            return result;
        } catch (Exception e) {
            LOG.warnf("Failed to gather Strimzi operator logs: %s", e.getMessage());
            failed.add(STEP_OPERATOR_LOGS + ": " + e.getMessage());
            return null;
        }
    }

    @WithSpan("diagnose.topic.events")
    StrimziEventsResponse gatherEvents(final String namespace,
                                        final String clusterName,
                                        final List<String> completed,
                                        final List<String> failed,
                                        final McpLog mcpLog) {
        try {
            StrimziEventsResponse result = eventsService.getClusterEvents(
                namespace, clusterName, null);
            completed.add(STEP_EVENTS);
            DiagnosticHelper.sendClientNotification(mcpLog,
                String.format("Found %d KafkaTopic related events", result.totalEvents()));
            return result;
        } catch (Exception e) {
            LOG.warnf("Failed to gather KafkaTopic related events: %s", e.getMessage());
            failed.add(STEP_EVENTS + ": " + e.getMessage());
            return null;
        }
    }

    @WithSpan("diagnose.topic.exporter_metrics")
    KafkaExporterMetricsResponse gatherExporterMetrics(final String namespace,
                                                        final String clusterName,
                                                        final List<String> completed,
                                                        final List<String> failed,
                                                        final McpLog mcpLog) {
        try {
            KafkaExporterMetricsResponse result = exporterMetricsService.getKafkaExporterMetrics(
                namespace, clusterName, "partitions", null, null, null, null, null, null);
            completed.add(STEP_EXPORTER_METRICS);
            DiagnosticHelper.sendClientNotification(mcpLog, "Collected Kafka Exporter partition metrics");
            return result;
        } catch (Exception e) {
            LOG.warnf("Failed to gather Kafka Exporter metrics: %s", e.getMessage());
            failed.add(STEP_EXPORTER_METRICS + ": " + e.getMessage());
            return null;
        }
    }

    // ---- Sampling: triage and analysis ----

    @WithSpan("diagnose.topic.investigation")
    InvestigationAreas investigateAreas(final Sampling sampling,
                                         final KafkaTopicResponse topic,
                                         final KafkaTopicListResponse relatedTopics,
                                         final KafkaClusterResponse cluster,
                                         final String symptom) {
        if (sampling == null || !sampling.isSupported()) {
            return InvestigationAreas.all();
        }

        try {
            Map<String, Object> summary = buildPhase1Summary(topic, relatedTopics, cluster, symptom);
            String summaryJson = objectMapper.writeValueAsString(summary);

            SamplingResponse response = sampling.requestBuilder()
                .setSystemPrompt(TRIAGE_SYSTEM_PROMPT)
                .addMessage(SamplingMessage.withUserRole(summaryJson))
                .setMaxTokens(triageMaxTokens)
                .build()
                .sendAndAwait();

            return parseInvestigationAreas(response);
        } catch (Exception e) {
            LOG.warnf("Sampling triage failed (investigating all areas): %s: %s",
                e.getClass().getSimpleName(), e.getMessage());
            return InvestigationAreas.all();
        }
    }

    @WithSpan("diagnose.topic.analysis")
    @SuppressWarnings("checkstyle:ParameterNumber")
    String produceAnalysis(final Sampling sampling,
                           final KafkaTopicResponse topic,
                           final KafkaTopicListResponse relatedTopics,
                           final KafkaClusterResponse cluster,
                           final StrimziOperatorLogsResponse operatorLogs,
                           final StrimziEventsResponse events,
                           final KafkaExporterMetricsResponse exporterMetrics,
                           final String symptom) {
        if (sampling == null || !sampling.isSupported()) {
            return null;
        }

        try {
            Map<String, Object> fullData = buildFullSummary(
                topic, relatedTopics, cluster, operatorLogs, events, exporterMetrics, symptom);
            String dataJson = objectMapper.writeValueAsString(fullData);

            SamplingResponse response = sampling.requestBuilder()
                .setSystemPrompt(ANALYSIS_SYSTEM_PROMPT)
                .addMessage(SamplingMessage.withUserRole(dataJson))
                .setMaxTokens(analysisMaxTokens)
                .build()
                .sendAndAwait();

            return DiagnosticHelper.extractSamplingText(response);
        } catch (Exception e) {
            LOG.warnf("Sampling analysis failed: %s: %s", e.getClass().getSimpleName(), e.getMessage());
            return null;
        }
    }

    // ---- Helpers ----

    private Map<String, Object> buildPhase1Summary(final KafkaTopicResponse topic,
                                                    final KafkaTopicListResponse relatedTopics,
                                                    final KafkaClusterResponse cluster,
                                                    final String symptom) {
        Map<String, Object> summary = new LinkedHashMap<>();
        if (symptom != null) {
            summary.put("symptom", symptom);
        }
        summary.put("topic_name", topic.name());
        summary.put("topic_status", topic.status());
        summary.put("topic_cluster", topic.cluster());
        summary.put("topic_partitions", topic.partitions());
        summary.put("topic_replicas", topic.replicas());
        if (relatedTopics != null) {
            summary.put("related_topics_count", relatedTopics.total());
        }
        if (cluster != null) {
            summary.put("cluster_readiness", cluster.readiness());
            if (cluster.replicas() != null) {
                summary.put("cluster_expected_replicas", cluster.replicas().expected());
                summary.put("cluster_ready_replicas", cluster.replicas().ready());
            }
        }
        return summary;
    }

    @SuppressWarnings("checkstyle:ParameterNumber")
    private Map<String, Object> buildFullSummary(final KafkaTopicResponse topic,
                                                  final KafkaTopicListResponse relatedTopics,
                                                  final KafkaClusterResponse cluster,
                                                  final StrimziOperatorLogsResponse operatorLogs,
                                                  final StrimziEventsResponse events,
                                                  final KafkaExporterMetricsResponse exporterMetrics,
                                                  final String symptom) {
        Map<String, Object> data = new LinkedHashMap<>();
        if (symptom != null) {
            data.put("symptom", symptom);
        }
        DiagnosticHelper.putIfNotNull(data, STEP_TOPIC_STATUS, topic);
        DiagnosticHelper.putIfNotNull(data, STEP_RELATED_TOPICS, relatedTopics);
        DiagnosticHelper.putIfNotNull(data, STEP_CLUSTER_STATUS, cluster);
        DiagnosticHelper.putIfNotNull(data, STEP_OPERATOR_LOGS, operatorLogs);
        DiagnosticHelper.putIfNotNull(data, STEP_EVENTS, events);
        DiagnosticHelper.putIfNotNull(data, STEP_EXPORTER_METRICS, exporterMetrics);
        return data;
    }

    private InvestigationAreas parseInvestigationAreas(final SamplingResponse response) {
        try {
            String text = DiagnosticHelper.extractSamplingText(response);
            Map<String, Object> parsed = objectMapper.readValue(text, DiagnosticHelper.MAP_TYPE_REF);
            return new InvestigationAreas(
                Boolean.TRUE.equals(parsed.get(STEP_OPERATOR_LOGS)),
                Boolean.TRUE.equals(parsed.get(STEP_EVENTS)),
                Boolean.TRUE.equals(parsed.get(STEP_EXPORTER_METRICS))
            );
        } catch (Exception e) {
            LOG.debugf("Could not parse triage response, investigating all: %s", e.getMessage());
            return InvestigationAreas.all();
        }
    }

    /**
     * Flags indicating which investigation areas the LLM recommended.
     *
     * @param operatorLogs    whether to gather Strimzi operator logs
     * @param events          whether to gather Kubernetes events
     * @param exporterMetrics whether to gather Kafka Exporter partition metrics
     */
    record InvestigationAreas(boolean operatorLogs, boolean events, boolean exporterMetrics) {

        /**
         * Returns areas with all flags set to true (fallback when Sampling is unavailable).
         *
         * @return investigation areas with all flags enabled
         */
        static InvestigationAreas all() {
            return new InvestigationAreas(true, true, true);
        }

        /**
         * Count how many investigation areas are enabled.
         *
         * @return the number of enabled investigation areas
         */
        private int enabledCount() {
            int c = 0;
            if (operatorLogs) c++;
            if (events) c++;
            if (exporterMetrics) c++;
            return c;
        }
    }

    // ---- Sampling system prompts ----

    static final String TRIAGE_SYSTEM_PROMPT = """
        You are a Kafka topic diagnostics assistant. \
        Analyze the initial topic, related topics, and cluster findings and decide which areas \
        need deeper investigation. \
        Return ONLY a JSON object with these boolean fields: \
        operator_logs, events, exporter_metrics. \
        Set true only for areas likely to reveal the root cause. \
        For example, if the topic is Ready and the cluster is Ready, set all to false. \
        If the topic is in Error state, set operator_logs and events to true. \
        If there may be replication or partition issues, set exporter_metrics to true. \
        If the cluster is NotReady, set operator_logs and events to true.\
        """;

    static final String ANALYSIS_SYSTEM_PROMPT = """
        You are diagnosing a KafkaTopic issue. Analyze all the gathered diagnostic data \
        and produce a concise root cause analysis.

        Distinguish between:
        1. Topic configuration issues (HIGH): wrong partitions, invalid replicas, bad config overrides
        2. Topic Operator issues (CRITICAL): operator crash-looping, reconciliation stuck, RBAC errors
        3. Cluster-wide issues (CRITICAL): cluster NotReady, insufficient brokers, storage exhausted
        4. Replication issues (HIGH): under-replicated partitions, offline replicas, ISR shrinkage

        Structure your response as:
        - Root cause (single most likely cause)
        - Severity (CRITICAL/HIGH/MEDIUM/LOW)
        - Impact (what is affected)
        - Evidence (specific findings that support the diagnosis)
        - Recommendations (prioritized, actionable steps)

        Remember: check the topic status and partition/replica configuration carefully. \
        Cross-reference with cluster readiness and operator logs for the full picture.\
        """;
}
