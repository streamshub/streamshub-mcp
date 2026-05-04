/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import io.quarkiverse.mcp.server.Cancellation;
import io.quarkiverse.mcp.server.McpLog;
import io.quarkiverse.mcp.server.Progress;
import io.quarkiverse.mcp.server.Sampling;
import io.quarkiverse.mcp.server.SamplingMessage;
import io.quarkiverse.mcp.server.SamplingResponse;
import io.quarkiverse.mcp.server.ToolCallException;
import io.streamshub.mcp.common.dto.LogCollectionParams;
import io.streamshub.mcp.common.service.DiagnosticHelper;
import io.streamshub.mcp.common.util.InputUtils;
import io.streamshub.mcp.strimzi.config.metrics.StrimziOperatorMetricCategories;
import io.streamshub.mcp.strimzi.dto.OperatorMetricsDiagnosticReport;
import io.streamshub.mcp.strimzi.dto.StrimziOperatorLogsResponse;
import io.streamshub.mcp.strimzi.dto.StrimziOperatorResponse;
import io.streamshub.mcp.strimzi.dto.metrics.StrimziOperatorMetricsResponse;
import io.streamshub.mcp.strimzi.service.metrics.StrimziOperatorMetricsService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Orchestrates a multistep metrics diagnostic workflow for Strimzi operators.
 *
 * <p>Gathers operator status, then selectively queries reconciliation, resource,
 * and JVM metrics along with operator logs. Optionally uses MCP Sampling to get
 * LLM analysis of intermediate results.</p>
 *
 * <p>Individual step failures do not abort the workflow. Failed steps are
 * recorded and the report includes all data that was successfully gathered.</p>
 */
@ApplicationScoped
public class OperatorMetricsDiagnosticService {

    private static final Logger LOG = Logger.getLogger(OperatorMetricsDiagnosticService.class);
    private static final int PHASE1_STEPS = 1;
    private static final int MAX_PHASE2_STEPS = 4;
    private static final String DIAGNOSTIC_LABEL = "Strimzi operator metrics diagnostic";
    private static final String STEP_OPERATOR_STATUS = "operator_status";
    private static final String STEP_RECONCILIATION_METRICS = "reconciliation_metrics";
    private static final String STEP_RESOURCE_METRICS = "resource_metrics";
    private static final String STEP_JVM_METRICS = "jvm_metrics";
    private static final String STEP_OPERATOR_LOGS = "operator_logs";

    @Inject
    StrimziOperatorService operatorService;

    @Inject
    StrimziOperatorMetricsService operatorMetricsService;

    @Inject
    ObjectMapper objectMapper;

    @ConfigProperty(name = "mcp.log.tail-lines", defaultValue = "200")
    int defaultTailLines;

    @ConfigProperty(name = "mcp.sampling.triage-max-tokens", defaultValue = "200")
    int triageMaxTokens;

    @ConfigProperty(name = "mcp.sampling.analysis-max-tokens", defaultValue = "1500")
    int analysisMaxTokens;

    OperatorMetricsDiagnosticService() {
    }

    /**
     * Run a multi-step metrics diagnostic workflow for a Strimzi operator.
     *
     * <p>Phase 1 gathers operator deployment status. Phase 2 uses Sampling
     * (if supported) to decide which metric categories and logs need
     * investigation, then gathers the selected areas. Phase 3 uses Sampling
     * to produce an operator health diagnosis.</p>
     *
     * @param namespace    optional namespace
     * @param operatorName optional operator deployment name
     * @param clusterName  optional Kafka cluster name to include entity operator metrics
     * @param concern      optional concern description for context
     * @param rangeMinutes optional relative time range in minutes
     * @param startTime    optional absolute start time (ISO 8601)
     * @param endTime      optional absolute end time (ISO 8601)
     * @param stepSeconds  optional range query step interval in seconds
     * @param sampling     MCP Sampling for LLM analysis (may be unsupported)
     * @param mcpLog       MCP log for progress notifications
     * @param progress     MCP progress tracking
     * @param cancellation MCP cancellation checking
     * @return a consolidated operator metrics diagnostic report
     */
    @SuppressWarnings("checkstyle:ParameterNumber")
    public OperatorMetricsDiagnosticReport diagnose(final String namespace,
                                                    final String operatorName,
                                                    final String clusterName,
                                                    final String concern,
                                                    final Integer rangeMinutes,
                                                    final String startTime,
                                                    final String endTime,
                                                    final Integer stepSeconds,
                                                    final Sampling sampling,
                                                    final McpLog mcpLog,
                                                    final Progress progress,
                                                    final Cancellation cancellation) {
        String ns = InputUtils.normalizeInput(namespace);
        String name = InputUtils.normalizeInput(operatorName);
        String cluster = InputUtils.normalizeInput(clusterName);

        LOG.infof("Starting operator metrics diagnostic (namespace=%s, operator=%s, cluster=%s, concern=%s)",
            ns != null ? ns : "auto", name != null ? name : "auto",
            cluster != null ? cluster : "none", concern);

        List<String> completed = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        int stepIndex = 0;

        // === Phase 1: Initial data gathering ===
        int maxSteps = PHASE1_STEPS + MAX_PHASE2_STEPS;
        StrimziOperatorResponse operator = gatherOperatorStatus(
            ns, name, completed, mcpLog);
        DiagnosticHelper.sendProgress(progress, ++stepIndex, maxSteps, DIAGNOSTIC_LABEL);
        DiagnosticHelper.checkCancellation(cancellation);

        String resolvedNs = operator.namespace();
        String resolvedName = operator.name();

        // === Phase 2: Metrics investigation ===
        InvestigationAreas areas = decideInvestigationAreas(
            sampling, operator, concern);

        int totalSteps = PHASE1_STEPS + areas.enabledCount();

        StrimziOperatorMetricsResponse reconciliationMetrics = null;
        if (areas.reconciliation) {
            reconciliationMetrics = gatherMetrics(
                resolvedNs, resolvedName, cluster, StrimziOperatorMetricCategories.RECONCILIATION,
                rangeMinutes, startTime, endTime, stepSeconds,
                STEP_RECONCILIATION_METRICS, completed, failed, mcpLog);
            DiagnosticHelper.sendProgress(progress, ++stepIndex, totalSteps, DIAGNOSTIC_LABEL);
            DiagnosticHelper.checkCancellation(cancellation);
        }

        StrimziOperatorMetricsResponse resourceMetrics = null;
        if (areas.resources) {
            resourceMetrics = gatherMetrics(
                resolvedNs, resolvedName, cluster, StrimziOperatorMetricCategories.RESOURCES,
                rangeMinutes, startTime, endTime, stepSeconds,
                STEP_RESOURCE_METRICS, completed, failed, mcpLog);
            DiagnosticHelper.sendProgress(progress, ++stepIndex, totalSteps, DIAGNOSTIC_LABEL);
            DiagnosticHelper.checkCancellation(cancellation);
        }

        StrimziOperatorMetricsResponse jvmMetrics = null;
        if (areas.jvm) {
            jvmMetrics = gatherMetrics(
                resolvedNs, resolvedName, cluster, StrimziOperatorMetricCategories.JVM,
                rangeMinutes, startTime, endTime, stepSeconds,
                STEP_JVM_METRICS, completed, failed, mcpLog);
            DiagnosticHelper.sendProgress(progress, ++stepIndex, totalSteps, DIAGNOSTIC_LABEL);
            DiagnosticHelper.checkCancellation(cancellation);
        }

        StrimziOperatorLogsResponse operatorLogs = null;
        if (areas.operatorLogs) {
            operatorLogs = gatherOperatorLogs(
                resolvedNs, resolvedName, completed, failed, mcpLog);
            DiagnosticHelper.sendProgress(progress, ++stepIndex, totalSteps, DIAGNOSTIC_LABEL);
            DiagnosticHelper.checkCancellation(cancellation);
        }

        // === Phase 3: Final analysis ===
        String analysis = produceAnalysis(sampling, operator,
            reconciliationMetrics, resourceMetrics, jvmMetrics,
            operatorLogs, concern);

        return OperatorMetricsDiagnosticReport.of(operator,
            reconciliationMetrics, resourceMetrics, jvmMetrics,
            operatorLogs, analysis,
            completed, failed.isEmpty() ? null : failed);
    }

    // ---- Phase 1: Initial data gathering ----

    @WithSpan("diagnose.operator.status")
    StrimziOperatorResponse gatherOperatorStatus(final String namespace,
                                                 final String operatorName,
                                                 final List<String> completed,
                                                 final McpLog mcpLog) {
        if (operatorName != null) {
            StrimziOperatorResponse result = operatorService.getOperator(namespace, operatorName);
            completed.add(STEP_OPERATOR_STATUS);
            DiagnosticHelper.sendClientNotification(mcpLog,
                String.format("Checked Strimzi operator '%s' status: %s", result.name(), result.status()));
            return result;
        }

        List<StrimziOperatorResponse> operators = operatorService.listOperators(namespace);
        if (operators.isEmpty()) {
            String location = namespace != null
                ? "in namespace '" + namespace + "'"
                : "in any namespace";
            throw new ToolCallException("No Strimzi operator found " + location);
        }

        StrimziOperatorResponse result = operators.getFirst();
        completed.add(STEP_OPERATOR_STATUS);
        DiagnosticHelper.sendClientNotification(mcpLog, "Checked operator status: " + result.status());
        return result;
    }

    // ---- Phase 2: Metrics investigation ----

    @WithSpan("diagnose.operator.metrics")
    @SuppressWarnings("checkstyle:ParameterNumber")
    StrimziOperatorMetricsResponse gatherMetrics(final String namespace,
                                                         final String operatorName,
                                                         final String clusterName,
                                                         final String category,
                                                         final Integer rangeMinutes,
                                                         final String startTime,
                                                         final String endTime,
                                                         final Integer stepSeconds,
                                                         final String stepName,
                                                         final List<String> completed,
                                                         final List<String> failed,
                                                         final McpLog mcpLog) {
        try {
            StrimziOperatorMetricsResponse result = operatorMetricsService.getOperatorMetrics(
                namespace, operatorName, clusterName, category,
                null, rangeMinutes, startTime, endTime, stepSeconds, null);
            completed.add(stepName);
            DiagnosticHelper.sendClientNotification(mcpLog,
                String.format("Retrieved Strimzi operator %s metrics", category));
            return result;
        } catch (Exception e) {
            LOG.warnf("Failed to gather Strimzi operator %s metrics: %s", category, e.getMessage());
            failed.add(stepName + ": " + e.getMessage());
            return null;
        }
    }

    @WithSpan("diagnose.operator.logs")
    StrimziOperatorLogsResponse gatherOperatorLogs(final String namespace,
                                                   final String operatorName,
                                                   final List<String> completed,
                                                   final List<String> failed,
                                                   final McpLog mcpLog) {
        try {
            LogCollectionParams params = LogCollectionParams.builder(defaultTailLines)
                .filter("errors")
                .build();
            StrimziOperatorLogsResponse result = operatorService.getOperatorLogs(
                namespace, operatorName, params);
            completed.add(STEP_OPERATOR_LOGS);
            DiagnosticHelper.sendClientNotification(mcpLog, "Collected Strimzi operator logs");
            return result;
        } catch (Exception e) {
            LOG.warnf("Failed to gather Strimzi operator logs: %s", e.getMessage());
            failed.add(STEP_OPERATOR_LOGS + ": " + e.getMessage());
            return null;
        }
    }

    // ---- Sampling: triage and analysis ----

    @WithSpan("diagnose.operator.triage")
    InvestigationAreas decideInvestigationAreas(final Sampling sampling,
                                                final StrimziOperatorResponse operator,
                                                final String concern) {
        if (sampling == null || !sampling.isSupported()) {
            return InvestigationAreas.all();
        }

        try {
            Map<String, Object> summary = buildPhase1Summary(operator, concern);
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

    @WithSpan("diagnose.operator.analysis")
    @SuppressWarnings("checkstyle:ParameterNumber")
    String produceAnalysis(final Sampling sampling,
                           final StrimziOperatorResponse operator,
                           final StrimziOperatorMetricsResponse reconciliationMetrics,
                           final StrimziOperatorMetricsResponse resourceMetrics,
                           final StrimziOperatorMetricsResponse jvmMetrics,
                           final StrimziOperatorLogsResponse operatorLogs,
                           final String concern) {
        if (sampling == null || !sampling.isSupported()) {
            return null;
        }

        try {
            Map<String, Object> fullData = buildFullSummary(
                operator, reconciliationMetrics, resourceMetrics,
                jvmMetrics, operatorLogs, concern);
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

    private Map<String, Object> buildPhase1Summary(final StrimziOperatorResponse operator,
                                                   final String concern) {
        Map<String, Object> summary = new LinkedHashMap<>();
        if (concern != null) {
            summary.put("concern", concern);
        }
        summary.put(STEP_OPERATOR_STATUS, operator.status());
        summary.put("operator_ready", operator.ready());
        if (operator.uptimeHours() != null) {
            summary.put("uptime_hours", operator.uptimeHours());
        }
        if (operator.replicas() != null) {
            summary.put("replicas", operator.replicas());
            summary.put("ready_replicas", operator.readyReplicas());
        }
        return summary;
    }

    @SuppressWarnings("checkstyle:ParameterNumber")
    private Map<String, Object> buildFullSummary(final StrimziOperatorResponse operator,
                                                 final StrimziOperatorMetricsResponse reconciliationMetrics,
                                                 final StrimziOperatorMetricsResponse resourceMetrics,
                                                 final StrimziOperatorMetricsResponse jvmMetrics,
                                                 final StrimziOperatorLogsResponse operatorLogs,
                                                 final String concern) {
        Map<String, Object> data = new LinkedHashMap<>();
        if (concern != null) {
            data.put("concern", concern);
        }
        DiagnosticHelper.putIfNotNull(data, STEP_OPERATOR_STATUS, operator);
        DiagnosticHelper.putIfNotNull(data, STEP_RECONCILIATION_METRICS, reconciliationMetrics);
        DiagnosticHelper.putIfNotNull(data, STEP_RESOURCE_METRICS, resourceMetrics);
        DiagnosticHelper.putIfNotNull(data, STEP_JVM_METRICS, jvmMetrics);
        DiagnosticHelper.putIfNotNull(data, STEP_OPERATOR_LOGS, operatorLogs);
        return data;
    }

    private InvestigationAreas parseInvestigationAreas(final SamplingResponse response) {
        try {
            String text = DiagnosticHelper.extractSamplingText(response);
            Map<String, Object> parsed = objectMapper.readValue(text, DiagnosticHelper.MAP_TYPE_REF);
            return new InvestigationAreas(
                Boolean.TRUE.equals(parsed.get(STEP_RECONCILIATION_METRICS)),
                Boolean.TRUE.equals(parsed.get(STEP_RESOURCE_METRICS)),
                Boolean.TRUE.equals(parsed.get(STEP_JVM_METRICS)),
                Boolean.TRUE.equals(parsed.get(STEP_OPERATOR_LOGS))
            );
        } catch (Exception e) {
            LOG.debugf("Could not parse triage response, investigating all: %s", e.getMessage());
            return InvestigationAreas.all();
        }
    }

    /**
     * Flags indicating which investigation areas the LLM recommended.
     *
     * @param reconciliation whether to gather reconciliation metrics
     * @param resources      whether to gather resource metrics
     * @param jvm            whether to gather JVM metrics
     * @param operatorLogs   whether to gather operator logs
     */
    record InvestigationAreas(boolean reconciliation, boolean resources,
                              boolean jvm, boolean operatorLogs) {

        /**
         * Returns areas with all flags set to true (fallback when Sampling is unavailable).
         *
         * @return investigation areas with all flags enabled
         */
        static InvestigationAreas all() {
            return new InvestigationAreas(true, true, true, true);
        }

        /**
         * Count how many investigation areas are enabled.
         * Used to calculate the total number of diagnostic steps for progress reporting.
         *
         * @return the number of enabled investigation areas
         */
        private int enabledCount() {
            int c = 0;
            if (reconciliation) c++;
            if (resources) c++;
            if (jvm) c++;
            if (operatorLogs) c++;
            return c;
        }
    }

    // ---- Sampling system prompts ----

    static final String TRIAGE_SYSTEM_PROMPT = """
        You are a Strimzi operator diagnostics assistant. \
        Analyze the operator health to decide which areas need deeper investigation. \
        Return ONLY a JSON object with these boolean fields: \
        reconciliation_metrics, resource_metrics, jvm_metrics, operator_logs. \
        Set true only for areas likely to reveal the root cause. \
        For example, if the operator is healthy with no concern, set all to false. \
        If the operator shows degraded status, set reconciliation_metrics and operator_logs to true. \
        If the concern mentions slow reconciliation, set reconciliation_metrics and jvm_metrics to true. \
        If the concern mentions restarts or OOM, set resource_metrics, jvm_metrics, and operator_logs to true.\
        """;

    static final String ANALYSIS_SYSTEM_PROMPT = """
        You are analyzing Strimzi operator metrics to diagnose operational issues. \
        Analyze all gathered data and produce a concise operator health diagnosis.
        
        Distinguish between:
        1. Reconciliation failures (CRITICAL): stuck reconciliations, high failure rates, timeout patterns
        2. Resource exhaustion (HIGH): OOM kills, CPU throttling, pod restarts
        3. JVM issues (MEDIUM): GC pressure, thread contention, heap exhaustion
        4. Performance degradation (LOW): slow reconciliation times, growing queue depth
        
        Look for correlating patterns:
        - High GC pause time -> reconciliation slowdown -> timeout failures
        - Memory pressure -> OOM restarts -> reconciliation interruption
        - Thread contention -> slow reconciliations -> backed-up queue
        
        Structure your response as:
        - Root cause (single most likely cause based on metrics)
        - Severity (CRITICAL/HIGH/MEDIUM/LOW)
        - Key metrics (specific values that support the diagnosis)
        - Impact on managed resources (how this affects Kafka clusters)
        - Recommendations (prioritized, actionable steps)
        
        Correlate operator status (restarts, uptime) with metric patterns.\
        """;
}
