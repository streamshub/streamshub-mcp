/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkiverse.mcp.server.Cancellation;
import io.quarkiverse.mcp.server.Elicitation;
import io.quarkiverse.mcp.server.McpLog;
import io.quarkiverse.mcp.server.Progress;
import io.quarkiverse.mcp.server.Sampling;
import io.quarkiverse.mcp.server.SamplingMessage;
import io.quarkiverse.mcp.server.SamplingResponse;
import io.quarkiverse.mcp.server.ToolCallException;
import io.streamshub.mcp.common.dto.metrics.MetricSample;
import io.streamshub.mcp.common.service.DiagnosticHelper;
import io.streamshub.mcp.common.util.InputUtils;
import io.streamshub.mcp.strimzi.config.metrics.KafkaMetricCategories;
import io.streamshub.mcp.strimzi.dto.KafkaClusterPodsResponse;
import io.streamshub.mcp.strimzi.dto.KafkaClusterResponse;
import io.streamshub.mcp.strimzi.dto.KafkaMetricsDiagnosticReport;
import io.streamshub.mcp.strimzi.dto.metrics.KafkaMetricsResponse;
import io.streamshub.mcp.strimzi.service.metrics.KafkaMetricsService;
import io.streamshub.mcp.strimzi.util.NamespaceElicitationHelper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Orchestrates a multistep metrics diagnostic workflow for Kafka clusters.
 *
 * <p>Gathers cluster status and pod health, then selectively queries
 * replication, performance, resource, and throughput metrics. Optionally uses
 * MCP Sampling to get LLM analysis of intermediate results and Elicitation
 * to resolve ambiguous inputs (e.g., multiple namespaces).</p>
 *
 * <p>Individual step failures do not abort the workflow. Failed steps are
 * recorded and the report includes all data that was successfully gathered.</p>
 */
@ApplicationScoped
public class KafkaMetricsDiagnosticService {

    private static final Logger LOG = Logger.getLogger(KafkaMetricsDiagnosticService.class);
    private static final int TOTAL_STEPS = 3;
    private static final String DIAGNOSTIC_LABEL = "Kafka metrics diagnostic";
    private static final String STEP_CLUSTER_STATUS = "cluster_status";
    private static final String STEP_POD_HEALTH = "pod_health";
    private static final String STEP_REPLICATION_METRICS = "replication_metrics";
    private static final String STEP_PERFORMANCE_METRICS = "performance_metrics";
    private static final String STEP_RESOURCE_METRICS = "resource_metrics";
    private static final String STEP_THROUGHPUT_METRICS = "throughput_metrics";

    @Inject
    KafkaService kafkaService;

    @Inject
    KafkaMetricsService kafkaMetricsService;

    @Inject
    ObjectMapper objectMapper;

    @ConfigProperty(name = "mcp.sampling.triage-max-tokens", defaultValue = "200")
    int triageMaxTokens;

    @ConfigProperty(name = "mcp.sampling.analysis-max-tokens", defaultValue = "1500")
    int analysisMaxTokens;

    KafkaMetricsDiagnosticService() {
    }

    /**
     * Run a multistep metrics diagnostic workflow for a Kafka cluster.
     *
     * <p>Phase 1 gathers cluster status and pod health. Phase 2 uses
     * Sampling (if supported) to decide which metric categories need
     * investigation, then gathers the selected categories. Phase 3 uses
     * Sampling to produce a metrics-based diagnosis.</p>
     *
     * @param namespace    optional namespace (elicited if ambiguous)
     * @param clusterName  the Kafka cluster name
     * @param concern      optional concern description for context
     * @param rangeMinutes optional relative time range in minutes
     * @param startTime    optional absolute start time (ISO 8601)
     * @param endTime      optional absolute end time (ISO 8601)
     * @param stepSeconds  optional range query step interval in seconds
     * @param sampling     MCP Sampling for LLM analysis (may be unsupported)
     * @param elicitation  MCP Elicitation for user input (may be unsupported)
     * @param mcpLog       MCP log for progress notifications
     * @param progress     MCP progress tracking
     * @param cancellation MCP cancellation checking
     * @return a consolidated metrics diagnostic report
     */
    @SuppressWarnings("checkstyle:ParameterNumber")
    public KafkaMetricsDiagnosticReport diagnose(final String namespace,
                                                 final String clusterName,
                                                 final String concern,
                                                 final Integer rangeMinutes,
                                                 final String startTime,
                                                 final String endTime,
                                                 final Integer stepSeconds,
                                                 final Sampling sampling,
                                                 final Elicitation elicitation,
                                                 final McpLog mcpLog,
                                                 final Progress progress,
                                                 final Cancellation cancellation) {
        String ns = InputUtils.normalizeInput(namespace);
        String name = InputUtils.normalizeInput(clusterName);

        if (name == null) {
            throw new ToolCallException("Cluster name is required");
        }

        LOG.infof("Starting metrics diagnostic for cluster=%s (namespace=%s, concern=%s)",
            name, ns != null ? ns : "auto", concern);

        List<String> completed = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        int stepIndex = 0;

        // === Phase 1: Initial data gathering ===
        KafkaClusterResponse cluster = gatherClusterStatus(
            ns, name, elicitation, completed, mcpLog);
        DiagnosticHelper.sendProgress(progress, ++stepIndex, TOTAL_STEPS, DIAGNOSTIC_LABEL);
        DiagnosticHelper.checkCancellation(cancellation);

        String resolvedNs = cluster.namespace();

        KafkaClusterPodsResponse pods = gatherClusterPods(
            resolvedNs, name, completed, failed, mcpLog);
        DiagnosticHelper.sendProgress(progress, ++stepIndex, TOTAL_STEPS, DIAGNOSTIC_LABEL);
        DiagnosticHelper.checkCancellation(cancellation);

        // === Phase 2: Metrics investigation (single scrape for all categories) ===
        InvestigationAreas areas = decideInvestigationAreas(
            sampling, cluster, pods, concern);

        KafkaMetricsResponse replicationMetrics = null;
        KafkaMetricsResponse performanceMetrics = null;
        KafkaMetricsResponse resourceMetrics = null;
        KafkaMetricsResponse throughputMetrics = null;

        Map<String, KafkaMetricsResponse> categoryResults = gatherAllMetrics(
            resolvedNs, name, areas, rangeMinutes, startTime, endTime, stepSeconds,
            completed, failed, mcpLog);
        DiagnosticHelper.sendProgress(progress, ++stepIndex, TOTAL_STEPS, DIAGNOSTIC_LABEL);
        DiagnosticHelper.checkCancellation(cancellation);

        if (categoryResults != null) {
            replicationMetrics = categoryResults.get(KafkaMetricCategories.REPLICATION);
            performanceMetrics = categoryResults.get(KafkaMetricCategories.PERFORMANCE);
            resourceMetrics = categoryResults.get(KafkaMetricCategories.RESOURCES);
            throughputMetrics = categoryResults.get(KafkaMetricCategories.THROUGHPUT);
        }

        // === Phase 3: Final analysis ===
        String analysis = produceAnalysis(sampling, cluster, pods,
            replicationMetrics, performanceMetrics, resourceMetrics,
            throughputMetrics, concern);

        return KafkaMetricsDiagnosticReport.of(cluster, pods,
            replicationMetrics, performanceMetrics, resourceMetrics,
            throughputMetrics, analysis,
            completed, failed.isEmpty() ? null : failed);
    }

    // ---- Phase 1: Initial data gathering ----

    private KafkaClusterResponse gatherClusterStatus(final String namespace,
                                                     final String clusterName,
                                                     final Elicitation elicitation,
                                                     final List<String> completed,
                                                     final McpLog mcpLog) {
        try {
            KafkaClusterResponse result = kafkaService.getCluster(namespace, clusterName);
            completed.add(STEP_CLUSTER_STATUS);
            DiagnosticHelper.sendClientNotification(mcpLog, "Checked Kafka cluster status: " + result.readiness());
            return result;
        } catch (ToolCallException e) {
            if (NamespaceElicitationHelper.isMultipleNamespacesError(e)
                    && elicitation != null && elicitation.isSupported()) {
                String resolved = NamespaceElicitationHelper.elicitNamespace(e, elicitation, "analyzed for metrics");
                return gatherClusterStatus(resolved, clusterName, null,
                    completed, mcpLog);
            }
            throw e;
        }
    }

    private KafkaClusterPodsResponse gatherClusterPods(final String namespace,
                                                       final String clusterName,
                                                       final List<String> completed,
                                                       final List<String> failed,
                                                       final McpLog mcpLog) {
        try {
            KafkaClusterPodsResponse result = kafkaService.getClusterPods(namespace, clusterName);
            completed.add(STEP_POD_HEALTH);
            DiagnosticHelper.sendClientNotification(mcpLog, "Checked Kafka pod health");
            return result;
        } catch (Exception e) {
            LOG.warnf("Failed to gather Kafka pod health: %s", e.getMessage());
            failed.add(STEP_POD_HEALTH + ": " + e.getMessage());
            return null;
        }
    }

    // ---- Phase 2: Metrics investigation (single scrape) ----

    @SuppressWarnings("checkstyle:ParameterNumber")
    private Map<String, KafkaMetricsResponse> gatherAllMetrics(final String namespace,
                                                                final String clusterName,
                                                                final InvestigationAreas areas,
                                                                final Integer rangeMinutes,
                                                                final String startTime,
                                                                final String endTime,
                                                                final Integer stepSeconds,
                                                                final List<String> completed,
                                                                final List<String> failed,
                                                                final McpLog mcpLog) {
        // Collect all metric names from selected categories
        Map<String, List<String>> categoryMetricNames = new LinkedHashMap<>();
        if (areas.replication) {
            categoryMetricNames.put(KafkaMetricCategories.REPLICATION, KafkaMetricCategories.resolve(KafkaMetricCategories.REPLICATION));
        }
        if (areas.performance) {
            categoryMetricNames.put(KafkaMetricCategories.PERFORMANCE, KafkaMetricCategories.resolve(KafkaMetricCategories.PERFORMANCE));
        }
        if (areas.resources) {
            categoryMetricNames.put(KafkaMetricCategories.RESOURCES, KafkaMetricCategories.resolve(KafkaMetricCategories.RESOURCES));
        }
        if (areas.throughput) {
            categoryMetricNames.put(KafkaMetricCategories.THROUGHPUT, KafkaMetricCategories.resolve(KafkaMetricCategories.THROUGHPUT));
        }

        if (categoryMetricNames.isEmpty()) {
            return Map.of();
        }

        // Merge all metric names and scrape once
        String allNames = categoryMetricNames.values().stream()
            .flatMap(List::stream)
            .distinct()
            .collect(Collectors.joining(","));

        KafkaMetricsResponse allMetrics;
        try {
            allMetrics = kafkaMetricsService.getKafkaMetrics(
                namespace, clusterName, null, allNames, rangeMinutes, startTime, endTime, stepSeconds);
        } catch (Exception e) {
            LOG.warnf("Failed to gather Kafka metrics: %s", e.getMessage());
            for (String cat : categoryMetricNames.keySet()) {
                failed.add(categoryStepName(cat) + ": " + e.getMessage());
            }
            return null;
        }

        // Split results by category
        Map<String, KafkaMetricsResponse> results = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : categoryMetricNames.entrySet()) {
            String category = entry.getKey();
            Set<String> names = new HashSet<>(entry.getValue());
            List<MetricSample> categorySamples = allMetrics.metrics() != null
                ? allMetrics.metrics().stream()
                    .filter(s -> names.contains(s.name()))
                    .toList()
                : List.of();

            String interpretation = KafkaMetricCategories.interpretation(List.of(category));
            KafkaMetricsResponse categoryResponse = KafkaMetricsResponse.of(
                allMetrics.clusterName(), allMetrics.namespace(),
                allMetrics.provider(), List.of(category),
                categorySamples, interpretation);
            results.put(category, categoryResponse);
            completed.add(categoryStepName(category));
        }

        DiagnosticHelper.sendClientNotification(mcpLog, String.format("Retrieved metrics for %d categories (single scrape)",
            categoryMetricNames.size()));
        return results;
    }

    private String categoryStepName(final String category) {
        return switch (category) {
            case KafkaMetricCategories.REPLICATION -> STEP_REPLICATION_METRICS;
            case KafkaMetricCategories.PERFORMANCE -> STEP_PERFORMANCE_METRICS;
            case KafkaMetricCategories.RESOURCES -> STEP_RESOURCE_METRICS;
            case KafkaMetricCategories.THROUGHPUT -> STEP_THROUGHPUT_METRICS;
            default -> category + "_metrics";
        };
    }

    // ---- Sampling: triage and analysis ----

    private InvestigationAreas decideInvestigationAreas(final Sampling sampling,
                                                        final KafkaClusterResponse cluster,
                                                        final KafkaClusterPodsResponse pods,
                                                        final String concern) {
        if (sampling == null || !sampling.isSupported()) {
            return InvestigationAreas.all();
        }

        try {
            Map<String, Object> summary = buildPhase1Summary(cluster, pods, concern);
            String summaryJson = objectMapper.writeValueAsString(summary);

            SamplingResponse response = sampling.requestBuilder()
                .setSystemPrompt(TRIAGE_SYSTEM_PROMPT)
                .addMessage(SamplingMessage.withUserRole(summaryJson))
                .setMaxTokens(triageMaxTokens)
                .build()
                .sendAndAwait();

            return parseInvestigationAreas(response);
        } catch (Exception e) {
            LOG.warnf("Sampling triage failed, investigating all areas: %s", e.getMessage());
            return InvestigationAreas.all();
        }
    }

    @SuppressWarnings("checkstyle:ParameterNumber")
    private String produceAnalysis(final Sampling sampling,
                                   final KafkaClusterResponse cluster,
                                   final KafkaClusterPodsResponse pods,
                                   final KafkaMetricsResponse replicationMetrics,
                                   final KafkaMetricsResponse performanceMetrics,
                                   final KafkaMetricsResponse resourceMetrics,
                                   final KafkaMetricsResponse throughputMetrics,
                                   final String concern) {
        if (sampling == null || !sampling.isSupported()) {
            return null;
        }

        try {
            Map<String, Object> fullData = buildFullSummary(
                cluster, pods, replicationMetrics, performanceMetrics,
                resourceMetrics, throughputMetrics, concern);
            String dataJson = objectMapper.writeValueAsString(fullData);

            SamplingResponse response = sampling.requestBuilder()
                .setSystemPrompt(ANALYSIS_SYSTEM_PROMPT)
                .addMessage(SamplingMessage.withUserRole(dataJson))
                .setMaxTokens(analysisMaxTokens)
                .build()
                .sendAndAwait();

            return DiagnosticHelper.extractSamplingText(response);
        } catch (Exception e) {
            LOG.warnf("Sampling analysis failed: %s", e.getMessage());
            return null;
        }
    }

    // ---- Helpers ----

    private Map<String, Object> buildPhase1Summary(final KafkaClusterResponse cluster,
                                                   final KafkaClusterPodsResponse pods,
                                                   final String concern) {
        Map<String, Object> summary = new LinkedHashMap<>();
        if (concern != null) {
            summary.put("concern", concern);
        }
        summary.put("cluster_readiness", cluster.readiness());
        if (cluster.replicas() != null) {
            summary.put("expected_replicas", cluster.replicas().expected());
            summary.put("ready_replicas", cluster.replicas().ready());
        }
        if (pods != null) {
            summary.put("pods", pods);
        }
        return summary;
    }

    @SuppressWarnings("checkstyle:ParameterNumber")
    private Map<String, Object> buildFullSummary(final KafkaClusterResponse cluster,
                                                 final KafkaClusterPodsResponse pods,
                                                 final KafkaMetricsResponse replicationMetrics,
                                                 final KafkaMetricsResponse performanceMetrics,
                                                 final KafkaMetricsResponse resourceMetrics,
                                                 final KafkaMetricsResponse throughputMetrics,
                                                 final String concern) {
        Map<String, Object> data = new LinkedHashMap<>();
        if (concern != null) {
            data.put("concern", concern);
        }
        DiagnosticHelper.putIfNotNull(data, STEP_CLUSTER_STATUS, cluster);
        DiagnosticHelper.putIfNotNull(data, STEP_POD_HEALTH, pods);
        DiagnosticHelper.putIfNotNull(data, STEP_REPLICATION_METRICS, replicationMetrics);
        DiagnosticHelper.putIfNotNull(data, STEP_PERFORMANCE_METRICS, performanceMetrics);
        DiagnosticHelper.putIfNotNull(data, STEP_RESOURCE_METRICS, resourceMetrics);
        DiagnosticHelper.putIfNotNull(data, STEP_THROUGHPUT_METRICS, throughputMetrics);
        return data;
    }

    private InvestigationAreas parseInvestigationAreas(final SamplingResponse response) {
        try {
            String text = DiagnosticHelper.extractSamplingText(response);
            Map<String, Object> parsed = objectMapper.readValue(text, DiagnosticHelper.MAP_TYPE_REF);
            return new InvestigationAreas(
                Boolean.TRUE.equals(parsed.get(STEP_REPLICATION_METRICS)),
                Boolean.TRUE.equals(parsed.get(STEP_PERFORMANCE_METRICS)),
                Boolean.TRUE.equals(parsed.get(STEP_RESOURCE_METRICS)),
                Boolean.TRUE.equals(parsed.get(STEP_THROUGHPUT_METRICS))
            );
        } catch (Exception e) {
            LOG.debugf("Could not parse triage response, investigating all: %s", e.getMessage());
            return InvestigationAreas.all();
        }
    }

    /**
     * Flags indicating which metric categories the LLM recommended investigating.
     *
     * @param replication whether to gather replication metrics
     * @param performance whether to gather performance metrics
     * @param resources   whether to gather resource metrics
     * @param throughput  whether to gather throughput metrics
     */
    record InvestigationAreas(boolean replication, boolean performance,
                              boolean resources, boolean throughput) {

        /**
         * Returns areas with all flags set to true (fallback when Sampling is unavailable).
         *
         * @return investigation areas with all flags enabled
         */
        static InvestigationAreas all() {
            return new InvestigationAreas(true, true, true, true);
        }
    }

    // ---- Sampling system prompts ----

    static final String TRIAGE_SYSTEM_PROMPT = """
        You are a Kafka metrics diagnostics assistant. \
        Analyze the cluster health and pod status to decide which metric categories \
        need investigation. \
        Return ONLY a JSON object with these boolean fields: \
        replication_metrics, performance_metrics, resource_metrics, throughput_metrics. \
        Set true only for categories likely to reveal the root cause. \
        For example, if pods are healthy and cluster is Ready with no concern, set all to false. \
        If pods show restarts or OOM, set resource_metrics and performance_metrics to true. \
        If the concern mentions latency, set performance_metrics and throughput_metrics to true. \
        If the concern mentions replication or lag, set replication_metrics to true.\
        """;

    static final String ANALYSIS_SYSTEM_PROMPT = """
        You are analyzing Kafka cluster metrics to diagnose health issues. \
        Analyze all gathered metrics data and produce a concise metrics-based diagnosis.
        
        Distinguish between:
        1. Replication failures (CRITICAL): offline partitions, under-replicated partitions, growing lag
        2. Performance degradation (HIGH): low handler idle, high queue times, network saturation
        3. Resource exhaustion (MEDIUM): GC thrashing, high heap usage, CPU saturation
        4. Throughput anomalies (LOW): rate drops, cross-broker imbalance
        
        Look for cascading failure patterns:
        - Resource exhaustion -> performance degradation -> replication lag
        - GC thrashing -> CPU spikes -> handler idle drops -> queue time increases
        - Network saturation -> replication lag -> under-replicated partitions
        
        Structure your response as:
        - Root cause (single most likely cause based on metrics)
        - Severity (CRITICAL/HIGH/MEDIUM/LOW)
        - Key metrics (specific values that support the diagnosis)
        - Cascading effects (how the root cause propagates)
        - Recommendations (prioritized, actionable steps)
        
        Correlate pod health (restarts, OOM) with metric patterns. \
        A pod with recent restarts + high GC metrics suggests memory pressure.\
        """;
}
