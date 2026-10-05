/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.service.kafkabridge;

import io.opentelemetry.instrumentation.annotations.WithSpan;
import io.quarkiverse.mcp.server.Cancellation;
import io.quarkiverse.mcp.server.Elicitation;
import io.quarkiverse.mcp.server.McpException;
import io.quarkiverse.mcp.server.Progress;
import io.quarkiverse.mcp.server.Sampling;
import io.streamshub.mcp.common.dto.LogCollectionParams;
import io.streamshub.mcp.common.service.BaseDiagnosticService;
import io.streamshub.mcp.common.service.DiagnosticHelper;
import io.streamshub.mcp.common.util.InputUtils;
import io.streamshub.mcp.common.util.McpErrors;
import io.streamshub.mcp.common.util.NamespaceElicitationHelper;
import io.streamshub.mcp.strimzi.config.StrimziConstants;
import io.streamshub.mcp.strimzi.config.metrics.KafkaBridgeMetricCategories;
import io.streamshub.mcp.strimzi.dto.kafkabridge.KafkaBridgeDiagnosticReport;
import io.streamshub.mcp.strimzi.dto.kafkabridge.KafkaBridgeLogsResponse;
import io.streamshub.mcp.strimzi.dto.kafkabridge.KafkaBridgePodsResponse;
import io.streamshub.mcp.strimzi.dto.kafkabridge.KafkaBridgeResponse;
import io.streamshub.mcp.strimzi.dto.metrics.KafkaBridgeMetricsResponse;
import io.streamshub.mcp.strimzi.dto.operator.StrimziEventsResponse;
import io.streamshub.mcp.strimzi.service.metrics.KafkaBridgeMetricsService;
import io.streamshub.mcp.strimzi.service.operator.StrimziEventsService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
/**
 * Orchestrates a multi-step diagnostic workflow for KafkaBridge instances.
 *
 * <p>Phase 1 gathers bridge status (HTTP listener, client configuration) and pod health.
 * Phase 2 uses Sampling to decide which of logs, events, and HTTP metrics are worth
 * gathering. Phase 3 uses Sampling for root cause analysis.</p>
 */
@ApplicationScoped
public class KafkaBridgeDiagnosticService extends BaseDiagnosticService {

    private static final Logger LOG = Logger.getLogger(KafkaBridgeDiagnosticService.class);
    private static final int PHASE1_STEPS = 2;
    private static final String STEP_BRIDGE_STATUS = "bridge_status";
    private static final String STEP_BRIDGE_PODS = "bridge_pods";
    private static final String STEP_BRIDGE_LOGS = "bridge_logs";
    private static final String STEP_EVENTS = "events";
    private static final String STEP_METRICS = "metrics";

    @Inject
    KafkaBridgeService bridgeService;

    @Inject
    StrimziEventsService eventsService;

    @Inject
    KafkaBridgeMetricsService metricsService;

    @Override
    protected Logger getLogger() {
        return LOG;
    }

    KafkaBridgeDiagnosticService() {
    }

    /**
     * Run a multi-step diagnostic for a KafkaBridge instance.
     *
     * @param namespace    optional namespace
     * @param bridgeName   the KafkaBridge name
     * @param symptom      optional symptom description
     * @param sinceMinutes optional time window for logs/events
     * @param sampling     MCP Sampling for LLM analysis
     * @param elicitation  MCP Elicitation for user input
     * @param progress     MCP progress tracking
     * @param cancellation MCP cancellation checking
     * @return the diagnostic report
     */
    @SuppressWarnings("checkstyle:ParameterNumber")
    public KafkaBridgeDiagnosticReport diagnose(final String namespace,
                                                 final String bridgeName,
                                                 final String symptom,
                                                 final Integer sinceMinutes,
                                                 final Sampling sampling,
                                                 final Elicitation elicitation,
                                                 final Progress progress,
                                                 final Cancellation cancellation) {
        String ns = InputUtils.normalizeInput(namespace);
        ns = DiagnosticHelper.effectiveNamespace(sampling, ns);
        String name = InputUtils.normalizeInput(bridgeName);

        if (name == null) {
            throw McpErrors.invalidParams("KafkaBridge name is required");
        }

        LOG.infof("Starting diagnostic for KafkaBridge=%s (namespace=%s, symptom=%s)",
            name, ns != null ? ns : "auto", symptom);

        // Register push-based cancellation callback for async operations
        AtomicBoolean cancelled = new AtomicBoolean(false);
        DiagnosticHelper.registerCancellationCallback(cancellation, cancelled);

        List<String> completed = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        int stepIndex = 0;

        // === Phase 1: Initial data gathering ===
        int maxSteps = PHASE1_STEPS + InvestigationAreas.MAX_AREAS;

        KafkaBridgeResponse bridge = gatherBridgeStatus(ns, name, elicitation, completed);
        DiagnosticHelper.sendProgress(progress, ++stepIndex, maxSteps,
            "Checked KafkaBridge status: " + bridge.readiness());
        DiagnosticHelper.checkCancellation(cancellation);

        String resolvedNs = bridge.namespace() != null ? bridge.namespace() : ns;

        KafkaBridgePodsResponse pods = gatherPods(resolvedNs, name, completed, failed);
        DiagnosticHelper.sendProgress(progress, ++stepIndex, maxSteps,
            pods != null ? "Checked KafkaBridge pod health" : "Failed to check pod health");
        DiagnosticHelper.checkCancellation(cancellation);

        // === Phase 2: Deep investigation ===
        InvestigationAreas areas = investigateAreas(sampling, bridge, pods, symptom, cancelled);
        DiagnosticHelper.checkAsyncCancellation(cancelled);

        int totalSteps = PHASE1_STEPS + areas.enabledCount();

        KafkaBridgeLogsResponse logs = null;
        if (areas.logs) {
            logs = gatherLogs(resolvedNs, name,
                sinceMinutes != null ? sinceMinutes * 60 : null, completed, failed);
            DiagnosticHelper.sendProgress(progress, ++stepIndex, totalSteps,
                logs != null ? "Collected KafkaBridge logs" : "Failed to collect logs");
            DiagnosticHelper.checkCancellation(cancellation);
        }

        StrimziEventsResponse events = null;
        if (areas.events) {
            events = gatherEvents(resolvedNs, name, sinceMinutes, completed, failed);
            DiagnosticHelper.sendProgress(progress, ++stepIndex, totalSteps,
                events != null ? String.format("Found %d related events", events.totalEvents())
                    : "Failed to gather events");
            DiagnosticHelper.checkCancellation(cancellation);
        }

        KafkaBridgeMetricsResponse metrics = null;
        if (areas.metrics) {
            metrics = gatherMetrics(resolvedNs, name, completed, failed);
            DiagnosticHelper.sendProgress(progress, ++stepIndex, totalSteps,
                metrics != null ? "Collected KafkaBridge HTTP metrics" : "Failed to gather metrics");
            DiagnosticHelper.checkCancellation(cancellation);
        }

        // === Phase 3: Analysis ===
        String analysis = produceAnalysis(sampling, bridge, pods, logs, events, metrics, symptom, cancelled);
        DiagnosticHelper.checkAsyncCancellation(cancelled);

        return KafkaBridgeDiagnosticReport.of(bridge, pods, logs, events, metrics,
            analysis, completed, failed.isEmpty() ? null : failed);
    }

    // ---- Phase 1 ----

    @WithSpan("diagnose.bridge.status")
    KafkaBridgeResponse gatherBridgeStatus(final String namespace,
                                            final String name,
                                            final Elicitation elicitation,
                                            final List<String> completed) {
        try {
            KafkaBridgeResponse result = bridgeService.getBridge(namespace, name);
            completed.add(STEP_BRIDGE_STATUS);
            return result;
        } catch (McpException e) {
            if (NamespaceElicitationHelper.isMultipleNamespacesError(e)
                    && elicitation != null && elicitation.isFormModeSupported()) {
                String resolved = NamespaceElicitationHelper.elicitNamespaceMrtr(
                    e, elicitation, "diagnosed", "namespace");
                return gatherBridgeStatus(resolved, name, null, completed);
            }
            throw e;
        }
    }

    @WithSpan("diagnose.bridge.pods")
    KafkaBridgePodsResponse gatherPods(final String namespace,
                                        final String name,
                                        final List<String> completed,
                                        final List<String> failed) {
        try {
            KafkaBridgePodsResponse result = bridgeService.getBridgePods(namespace, name);
            completed.add(STEP_BRIDGE_PODS);
            return result;
        } catch (Exception e) {
            LOG.warnf("Failed to gather KafkaBridge pods: %s", e.getMessage());
            failed.add(STEP_BRIDGE_PODS + ": " + e.getMessage());
            return null;
        }
    }

    // ---- Phase 2 ----

    @WithSpan("diagnose.bridge.logs")
    KafkaBridgeLogsResponse gatherLogs(final String namespace,
                                        final String name,
                                        final Integer sinceSeconds,
                                        final List<String> completed,
                                        final List<String> failed) {
        try {
            LogCollectionParams options = LogCollectionParams.builder(defaultTailLines)
                .filter("errors")
                .sinceSeconds(sinceSeconds)
                .build();
            KafkaBridgeLogsResponse result = bridgeService.getBridgeLogs(namespace, name, options);
            completed.add(STEP_BRIDGE_LOGS);
            return result;
        } catch (Exception e) {
            LOG.warnf("Failed to gather KafkaBridge logs: %s", e.getMessage());
            failed.add(STEP_BRIDGE_LOGS + ": " + e.getMessage());
            return null;
        }
    }

    @WithSpan("diagnose.bridge.events")
    StrimziEventsResponse gatherEvents(final String namespace,
                                        final String name,
                                        final Integer sinceMinutes,
                                        final List<String> completed,
                                        final List<String> failed) {
        try {
            StrimziEventsResponse result = eventsService.getEvents(
                namespace, name, StrimziConstants.KindValues.KAFKA_BRIDGE, sinceMinutes);
            completed.add(STEP_EVENTS);
            return result;
        } catch (Exception e) {
            LOG.warnf("Failed to gather events: %s", e.getMessage());
            failed.add(STEP_EVENTS + ": " + e.getMessage());
            return null;
        }
    }

    @WithSpan("diagnose.bridge.metrics")
    KafkaBridgeMetricsResponse gatherMetrics(final String namespace,
                                              final String name,
                                              final List<String> completed,
                                              final List<String> failed) {
        try {
            KafkaBridgeMetricsResponse result = metricsService.getKafkaBridgeMetrics(
                namespace, name, KafkaBridgeMetricCategories.HTTP, null, null, null, null, null, null);
            completed.add(STEP_METRICS);
            return result;
        } catch (Exception e) {
            LOG.warnf("Failed to gather KafkaBridge metrics: %s", e.getMessage());
            failed.add(STEP_METRICS + ": " + e.getMessage());
            return null;
        }
    }

    // ---- Sampling: triage and analysis ----

    @WithSpan("diagnose.bridge.investigation")
    InvestigationAreas investigateAreas(final Sampling sampling,
                                         final KafkaBridgeResponse bridge,
                                         final KafkaBridgePodsResponse pods,
                                         final String symptom,
                                         final AtomicBoolean cancelled) {
        Map<String, Object> parsed = performTriage(sampling, TRIAGE_SYSTEM_PROMPT,
            buildPhase1Summary(bridge, pods, symptom), cancelled);
        return parsed != null ? parseInvestigationAreas(parsed) : InvestigationAreas.all();
    }

    @WithSpan("diagnose.bridge.analysis")
    @SuppressWarnings("checkstyle:ParameterNumber")
    String produceAnalysis(final Sampling sampling,
                           final KafkaBridgeResponse bridge,
                           final KafkaBridgePodsResponse pods,
                           final KafkaBridgeLogsResponse logs,
                           final StrimziEventsResponse events,
                           final KafkaBridgeMetricsResponse metrics,
                           final String symptom,
                           final AtomicBoolean cancelled) {
        return performAnalysisMrtr(sampling, ANALYSIS_SYSTEM_PROMPT,
            buildFullSummary(bridge, pods, logs, events, metrics, symptom),
            "analysis", bridge.namespace(), cancelled);
    }

    // ---- Helpers ----

    private Map<String, Object> buildPhase1Summary(final KafkaBridgeResponse bridge,
                                                    final KafkaBridgePodsResponse pods,
                                                    final String symptom) {
        Map<String, Object> summary = new LinkedHashMap<>();
        if (symptom != null) {
            summary.put("symptom", symptom);
        }
        summary.put("bridge_name", bridge.name());
        summary.put("bridge_readiness", bridge.readiness());
        summary.put("bridge_http_url", bridge.httpUrl());
        summary.put("bridge_bootstrap_servers", bridge.bootstrapServers());
        summary.put("bridge_authentication_type", bridge.authenticationType());
        if (bridge.replicas() != null) {
            summary.put("bridge_expected_replicas", bridge.replicas().expected());
            summary.put("bridge_ready_replicas", bridge.replicas().ready());
        }
        DiagnosticHelper.putIfNotNull(summary, STEP_BRIDGE_PODS, pods);
        return summary;
    }

    private Map<String, Object> buildFullSummary(final KafkaBridgeResponse bridge,
                                                  final KafkaBridgePodsResponse pods,
                                                  final KafkaBridgeLogsResponse logs,
                                                  final StrimziEventsResponse events,
                                                  final KafkaBridgeMetricsResponse metrics,
                                                  final String symptom) {
        Map<String, Object> data = new LinkedHashMap<>();
        if (symptom != null) {
            data.put("symptom", symptom);
        }
        DiagnosticHelper.putIfNotNull(data, STEP_BRIDGE_STATUS, bridge);
        DiagnosticHelper.putIfNotNull(data, STEP_BRIDGE_PODS, pods);
        DiagnosticHelper.putIfNotNull(data, STEP_BRIDGE_LOGS, logs);
        DiagnosticHelper.putIfNotNull(data, STEP_EVENTS, events);
        DiagnosticHelper.putIfNotNull(data, STEP_METRICS, metrics);
        return data;
    }

    private InvestigationAreas parseInvestigationAreas(final Map<String, Object> parsed) {
        return new InvestigationAreas(
            Boolean.TRUE.equals(parsed.get(STEP_BRIDGE_LOGS)),
            Boolean.TRUE.equals(parsed.get(STEP_EVENTS)),
            Boolean.TRUE.equals(parsed.get(STEP_METRICS))
        );
    }

    /**
     * Flags indicating which investigation areas the LLM recommended.
     *
     * @param logs    whether to gather KafkaBridge pod logs
     * @param events  whether to gather Kubernetes events
     * @param metrics whether to gather the bridge HTTP metrics
     */
    record InvestigationAreas(boolean logs, boolean events, boolean metrics) {

        /** The number of investigation areas, used to size the progress bar before triage. */
        static final int MAX_AREAS = 3;

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
            if (logs) c++;
            if (events) c++;
            if (metrics) c++;
            return c;
        }
    }

    // ---- Sampling system prompts ----

    static final String TRIAGE_SYSTEM_PROMPT = """
        You are a Kafka Bridge diagnostics assistant. \
        Analyze the initial KafkaBridge status and pod findings and decide which areas \
        need deeper investigation. \
        Return ONLY a JSON object with these boolean fields: \
        bridge_logs, events, metrics. \
        Set true only for areas likely to reveal the root cause. \
        For example, if the bridge is Ready and all pods are running, set all to false. \
        If pods are crash-looping, restarting, or not ready, set bridge_logs and events to true. \
        If the bridge is Ready but the symptom describes HTTP errors, slow responses, or \
        clients not receiving data, set metrics and bridge_logs to true. \
        If the bridge is NotReady, set bridge_logs and events to true.\
        """;

    static final String ANALYSIS_SYSTEM_PROMPT = """
        You are diagnosing a KafkaBridge (HTTP-to-Kafka gateway) issue. \
        Analyze all gathered data and produce a structured diagnosis.

        Structure your response as:
        - Root cause (one sentence)
        - Severity: CRITICAL / HIGH / MEDIUM / LOW
        - Impact: what is affected (HTTP producers, HTTP consumers, monitoring)
        - Evidence: key findings from status, pods, logs, events, metrics
        - Recommendations: specific, actionable remediation steps

        Common KafkaBridge issue categories:
        1. Kafka connectivity (bad bootstrap servers, TLS or SASL misconfiguration, network policy)
        2. HTTP listener problems (port not exposed, missing Ingress or Route, CORS rejecting origins)
        3. Consumer lifecycle errors (consumer instance not created, expired, or wrong base URI)
        4. Authorization failures (bridge principal lacks ACLs for the requested topic or group)
        5. Resource exhaustion (OOM, CPU throttling, HTTP request queue saturation)
        6. Operator reconciliation failures (invalid CR, image pull errors)\
        """;
}
