/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.service.kafkarebalance;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.Pod;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import io.quarkiverse.mcp.server.Cancellation;
import io.quarkiverse.mcp.server.Elicitation;
import io.quarkiverse.mcp.server.McpException;
import io.quarkiverse.mcp.server.Progress;
import io.quarkiverse.mcp.server.Sampling;
import io.streamshub.mcp.common.dto.LogCollectionParams;
import io.streamshub.mcp.common.dto.PodLogsResult;
import io.streamshub.mcp.common.service.BaseDiagnosticService;
import io.streamshub.mcp.common.service.DiagnosticHelper;
import io.streamshub.mcp.common.service.KubernetesResourceService;
import io.streamshub.mcp.common.service.log.LogCollectionService;
import io.streamshub.mcp.common.util.InputUtils;
import io.streamshub.mcp.common.util.McpErrors;
import io.streamshub.mcp.common.util.NamespaceElicitationHelper;
import io.streamshub.mcp.strimzi.config.StrimziConstants;
import io.streamshub.mcp.strimzi.dto.kafkarebalance.KafkaRebalanceDiagnosticReport;
import io.streamshub.mcp.strimzi.dto.kafkarebalance.KafkaRebalanceResponse;
import io.streamshub.mcp.strimzi.dto.operator.StrimziEventsResponse;
import io.streamshub.mcp.strimzi.service.operator.StrimziEventsService;
import io.strimzi.api.ResourceLabels;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Orchestrates a multi-step diagnostic workflow for KafkaRebalance instances.
 *
 * <p>Phase 1 gathers KafkaRebalance status and spec details.
 * Phase 2 uses Sampling to decide which of progress ConfigMap, Cruise Control pod logs,
 * and Kubernetes events are worth gathering. Phase 3 uses Sampling for root cause analysis.</p>
 */
@ApplicationScoped
public class KafkaRebalanceDiagnosticService extends BaseDiagnosticService {

    private static final Logger LOG = Logger.getLogger(KafkaRebalanceDiagnosticService.class);
    private static final int PHASE1_STEPS = 1;
    private static final String STEP_REBALANCE_STATUS = "rebalance_status";
    private static final String STEP_PROGRESS_CONFIG_MAP = "progress_config_map";
    private static final String STEP_CRUISE_CONTROL_LOGS = "cruise_control_logs";
    private static final String STEP_EVENTS = "events";

    @Inject
    KubernetesResourceService k8sService;

    @Inject
    KafkaRebalanceService rebalanceService;

    @Inject
    LogCollectionService logCollectionService;

    @Inject
    StrimziEventsService eventsService;

    @Override
    protected Logger getLogger() {
        return LOG;
    }

    KafkaRebalanceDiagnosticService() {
    }

    /**
     * Run a multi-step diagnostic for a KafkaRebalance instance.
     *
     * @param namespace     optional namespace
     * @param rebalanceName the KafkaRebalance name
     * @param symptom       optional symptom description
     * @param sinceMinutes  optional time window for logs/events
     * @param sampling      MCP Sampling for LLM analysis
     * @param elicitation   MCP Elicitation for user input
     * @param progress      MCP progress tracking
     * @param cancellation  MCP cancellation checking
     * @return the diagnostic report
     */
    @SuppressWarnings("checkstyle:ParameterNumber")
    public KafkaRebalanceDiagnosticReport diagnose(final String namespace,
                                                   final String rebalanceName,
                                                   final String symptom,
                                                   final Integer sinceMinutes,
                                                   final Sampling sampling,
                                                   final Elicitation elicitation,
                                                   final Progress progress,
                                                   final Cancellation cancellation) {
        String ns = InputUtils.normalizeInput(namespace);
        ns = DiagnosticHelper.effectiveNamespace(sampling, ns);
        String name = InputUtils.normalizeInput(rebalanceName);

        if (name == null) {
            throw McpErrors.invalidParams("KafkaRebalance name is required");
        }

        LOG.infof("Starting diagnostic for KafkaRebalance=%s (namespace=%s, symptom=%s)",
            name, ns != null ? ns : "auto", symptom);

        AtomicBoolean cancelled = new AtomicBoolean(false);
        DiagnosticHelper.registerCancellationCallback(cancellation, cancelled);

        List<String> completed = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        int stepIndex = 0;

        // === Phase 1: Initial data gathering ===
        int maxSteps = PHASE1_STEPS + InvestigationAreas.MAX_AREAS;

        KafkaRebalanceResponse rebalance = gatherRebalanceStatus(ns, name, elicitation, completed);
        DiagnosticHelper.sendProgress(progress, ++stepIndex, maxSteps,
            "Checked KafkaRebalance status: " + rebalance.state());
        DiagnosticHelper.checkCancellation(cancellation);

        String resolvedNs = rebalance.namespace() != null ? rebalance.namespace() : ns;

        // === Phase 2: Deep investigation ===
        InvestigationAreas areas = investigateAreas(sampling, rebalance, symptom, cancelled);
        DiagnosticHelper.checkAsyncCancellation(cancelled);

        int totalSteps = PHASE1_STEPS + areas.enabledCount();

        Map<String, Object> progressCm = null;
        if (areas.progressConfigMap && rebalance.progressConfigMap() != null) {
            progressCm = gatherProgressConfigMap(resolvedNs, rebalance.progressConfigMap(), completed, failed);
            DiagnosticHelper.sendProgress(progress, ++stepIndex, totalSteps,
                progressCm != null ? "Gathered progress ConfigMap" : "Failed to gather progress ConfigMap");
            DiagnosticHelper.checkCancellation(cancellation);
        }

        PodLogsResult ccLogs = null;
        if (areas.cruiseControlLogs && rebalance.cluster() != null) {
            ccLogs = gatherCruiseControlLogs(resolvedNs, rebalance.cluster(),
                sinceMinutes != null ? sinceMinutes * 60 : null, completed, failed);
            DiagnosticHelper.sendProgress(progress, ++stepIndex, totalSteps,
                ccLogs != null ? "Collected Cruise Control logs" : "Failed to collect Cruise Control logs");
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

        // === Phase 3: Analysis ===
        String analysis = produceAnalysis(sampling, rebalance, progressCm, ccLogs, events, symptom, cancelled);
        DiagnosticHelper.checkAsyncCancellation(cancelled);

        return KafkaRebalanceDiagnosticReport.of(rebalance, progressCm, ccLogs, events,
            analysis, completed, failed.isEmpty() ? null : failed);
    }

    // ---- Phase 1 ----

    @WithSpan("diagnose.rebalance.status")
    KafkaRebalanceResponse gatherRebalanceStatus(final String namespace,
                                                 final String name,
                                                 final Elicitation elicitation,
                                                 final List<String> completed) {
        try {
            KafkaRebalanceResponse result = rebalanceService.getRebalance(namespace, name);
            completed.add(STEP_REBALANCE_STATUS);
            return result;
        } catch (McpException e) {
            if (NamespaceElicitationHelper.isMultipleNamespacesError(e)
                    && elicitation != null && elicitation.isFormModeSupported()) {
                String resolved = NamespaceElicitationHelper.elicitNamespaceMrtr(
                    e, elicitation, "diagnosed", "namespace");
                return gatherRebalanceStatus(resolved, name, null, completed);
            }
            throw e;
        }
    }

    // ---- Phase 2 ----

    @WithSpan("diagnose.rebalance.progress_config_map")
    Map<String, Object> gatherProgressConfigMap(final String namespace,
                                                final String configMapName,
                                                final List<String> completed,
                                                final List<String> failed) {
        try {
            ConfigMap cm = k8sService.getResource(ConfigMap.class, namespace, configMapName);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("name", configMapName);
            result.put("namespace", namespace);
            if (cm != null && cm.getData() != null) {
                result.put("data", cm.getData());
            }
            completed.add(STEP_PROGRESS_CONFIG_MAP);
            return result;
        } catch (Exception e) {
            LOG.warnf("Failed to gather progress ConfigMap %s: %s", configMapName, e.getMessage());
            failed.add(STEP_PROGRESS_CONFIG_MAP + ": " + e.getMessage());
            return null;
        }
    }

    @WithSpan("diagnose.rebalance.cruise_control_logs")
    PodLogsResult gatherCruiseControlLogs(final String namespace,
                                          final String clusterName,
                                          final Integer sinceSeconds,
                                          final List<String> completed,
                                          final List<String> failed) {
        try {
            Map<String, String> labels = Map.of(
                ResourceLabels.STRIMZI_CLUSTER_LABEL, clusterName,
                ResourceLabels.STRIMZI_COMPONENT_TYPE_LABEL, StrimziConstants.ComponentTypes.KAFKA_CRUISE_CONTROL);
            List<Pod> pods = k8sService.queryResourcesByLabels(Pod.class, namespace, labels);
            if (pods.isEmpty()) {
                LOG.debugf("No Cruise Control pods found for cluster %s in namespace %s", clusterName, namespace);
                completed.add(STEP_CRUISE_CONTROL_LOGS);
                return null;
            }
            LogCollectionParams options = LogCollectionParams.builder(defaultTailLines)
                .filter("errors")
                .sinceSeconds(sinceSeconds)
                .build();
            PodLogsResult result = logCollectionService.collectLogs(namespace, pods, options);
            completed.add(STEP_CRUISE_CONTROL_LOGS);
            return result;
        } catch (Exception e) {
            LOG.warnf("Failed to gather Cruise Control logs: %s", e.getMessage());
            failed.add(STEP_CRUISE_CONTROL_LOGS + ": " + e.getMessage());
            return null;
        }
    }

    @WithSpan("diagnose.rebalance.events")
    StrimziEventsResponse gatherEvents(final String namespace,
                                        final String name,
                                        final Integer sinceMinutes,
                                        final List<String> completed,
                                        final List<String> failed) {
        try {
            StrimziEventsResponse result = eventsService.getEvents(
                namespace, name, StrimziConstants.KindValues.KAFKA_REBALANCE, sinceMinutes);
            completed.add(STEP_EVENTS);
            return result;
        } catch (Exception e) {
            LOG.warnf("Failed to gather events: %s", e.getMessage());
            failed.add(STEP_EVENTS + ": " + e.getMessage());
            return null;
        }
    }

    // ---- Sampling: triage and analysis ----

    @WithSpan("diagnose.rebalance.investigation")
    InvestigationAreas investigateAreas(final Sampling sampling,
                                         final KafkaRebalanceResponse rebalance,
                                         final String symptom,
                                         final AtomicBoolean cancelled) {
        Map<String, Object> parsed = performTriage(sampling, TRIAGE_SYSTEM_PROMPT,
            buildPhase1Summary(rebalance, symptom), cancelled);
        return parsed != null ? parseInvestigationAreas(parsed) : InvestigationAreas.all();
    }

    @WithSpan("diagnose.rebalance.analysis")
    @SuppressWarnings("checkstyle:ParameterNumber")
    String produceAnalysis(final Sampling sampling,
                           final KafkaRebalanceResponse rebalance,
                           final Map<String, Object> progressConfigMap,
                           final PodLogsResult ccLogs,
                           final StrimziEventsResponse events,
                           final String symptom,
                           final AtomicBoolean cancelled) {
        return performAnalysisMrtr(sampling, ANALYSIS_SYSTEM_PROMPT,
            buildFullSummary(rebalance, progressConfigMap, ccLogs, events, symptom),
            "analysis", rebalance.namespace(), cancelled);
    }

    // ---- Helpers ----

    private Map<String, Object> buildPhase1Summary(final KafkaRebalanceResponse rebalance,
                                                   final String symptom) {
        Map<String, Object> summary = new LinkedHashMap<>();
        if (symptom != null) {
            summary.put("symptom", symptom);
        }
        summary.put("rebalance_name", rebalance.name());
        summary.put("rebalance_state", rebalance.state());
        summary.put("rebalance_cluster", rebalance.cluster());
        summary.put("rebalance_mode", rebalance.mode());
        summary.put("rebalance_session_id", rebalance.sessionId());
        summary.put("rebalance_auto_approval", rebalance.autoApproval());
        DiagnosticHelper.putIfNotNull(summary, "optimization_result", rebalance.optimizationResult());
        DiagnosticHelper.putIfNotNull(summary, "conditions", rebalance.conditions());
        return summary;
    }

    private Map<String, Object> buildFullSummary(final KafkaRebalanceResponse rebalance,
                                                  final Map<String, Object> progressConfigMap,
                                                  final PodLogsResult ccLogs,
                                                  final StrimziEventsResponse events,
                                                  final String symptom) {
        Map<String, Object> data = new LinkedHashMap<>();
        if (symptom != null) {
            data.put("symptom", symptom);
        }
        DiagnosticHelper.putIfNotNull(data, STEP_REBALANCE_STATUS, rebalance);
        DiagnosticHelper.putIfNotNull(data, STEP_PROGRESS_CONFIG_MAP, progressConfigMap);
        DiagnosticHelper.putIfNotNull(data, STEP_CRUISE_CONTROL_LOGS, ccLogs);
        DiagnosticHelper.putIfNotNull(data, STEP_EVENTS, events);
        return data;
    }

    private InvestigationAreas parseInvestigationAreas(final Map<String, Object> parsed) {
        return new InvestigationAreas(
            Boolean.TRUE.equals(parsed.get(STEP_PROGRESS_CONFIG_MAP)),
            Boolean.TRUE.equals(parsed.get(STEP_CRUISE_CONTROL_LOGS)),
            Boolean.TRUE.equals(parsed.get(STEP_EVENTS))
        );
    }

    /**
     * Flags indicating which investigation areas the LLM recommended.
     *
     * @param progressConfigMap whether to gather the progress ConfigMap
     * @param cruiseControlLogs whether to gather Cruise Control pod logs
     * @param events            whether to gather Kubernetes events
     */
    record InvestigationAreas(boolean progressConfigMap, boolean cruiseControlLogs, boolean events) {

        static final int MAX_AREAS = 3;

        static InvestigationAreas all() {
            return new InvestigationAreas(true, true, true);
        }

        private int enabledCount() {
            int c = 0;
            if (progressConfigMap) c++;
            if (cruiseControlLogs) c++;
            if (events) c++;
            return c;
        }
    }

    // ---- Sampling system prompts ----

    static final String TRIAGE_SYSTEM_PROMPT = """
        You are a Cruise Control rebalance diagnostics assistant. \
        Analyze the initial KafkaRebalance state, conditions, and optimization results and decide \
        which areas need deeper investigation. \
        Return ONLY a JSON object with these boolean fields: \
        progress_config_map, cruise_control_logs, events. \
        Set true only for areas likely to reveal the root cause. \
        For example: \
        - If state is Rebalancing, set progress_config_map and cruise_control_logs to true. \
        - If state is PendingProposal or NotReady, set cruise_control_logs and events to true. \
        - If state is ProposalReady, set events to true. \
        - If state is Ready or Stopped and no error symptom, all can be false.\
        """;

    static final String ANALYSIS_SYSTEM_PROMPT = """
        You are diagnosing a KafkaRebalance issue for Strimzi Cruise Control. \
        Analyze all gathered data and produce a structured diagnosis.

        Structure your response as:
        - Root cause (one sentence)
        - Severity: CRITICAL / HIGH / MEDIUM / LOW
        - State machine status: current state and blockers (e.g., stuck in PendingProposal, hard goal violation)
        - Evidence: key findings from status conditions, optimization results, progress ConfigMap, CC logs, events
        - Recommendations: specific, actionable remediation steps (e.g., annotate to approve, adjust goals, restart CC)

        Common KafkaRebalance issue categories:
        1. Stuck in PendingProposal (Cruise Control not ready, not enough metric samples, calculation timed out)
        2. Stuck in ProposalReady (waiting for user approval annotation 'strimzi.io/rebalance=approve')
        3. Hard goal violations (optimization cannot satisfy required hard goals, need skipHardGoalCheck)
        4. Rebalance execution stuck / slow (partition movements throttled, broker unresponsive)
        5. NotReady / failure (Cruise Control crash, invalid broker IDs, excluded topics conflicts)\
        """;
}
