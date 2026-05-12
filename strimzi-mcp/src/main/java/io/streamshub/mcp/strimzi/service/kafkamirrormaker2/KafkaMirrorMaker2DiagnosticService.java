/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.service.kafkamirrormaker2;

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
import io.streamshub.mcp.strimzi.dto.StrimziEventsResponse;
import io.streamshub.mcp.strimzi.dto.kafkamirrormaker2.KafkaMirrorMaker2DiagnosticReport;
import io.streamshub.mcp.strimzi.dto.kafkamirrormaker2.KafkaMirrorMaker2LogsResponse;
import io.streamshub.mcp.strimzi.dto.kafkamirrormaker2.KafkaMirrorMaker2PodsResponse;
import io.streamshub.mcp.strimzi.dto.kafkamirrormaker2.KafkaMirrorMaker2Response;
import io.streamshub.mcp.strimzi.service.StrimziEventsService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Orchestrates a multi-step diagnostic workflow for KafkaMirrorMaker2 instances.
 *
 * <p>Phase 1 gathers MM2 status and pod health. Phase 2 uses Sampling
 * to decide which areas need investigation, then gathers logs and events.
 * Phase 3 uses Sampling for root cause analysis.</p>
 */
@ApplicationScoped
public class KafkaMirrorMaker2DiagnosticService {

    private static final Logger LOG = Logger.getLogger(KafkaMirrorMaker2DiagnosticService.class);
    private static final int PHASE1_STEPS = 2;
    private static final int MAX_PHASE2_STEPS = 2;
    private static final String DIAGNOSTIC_LABEL = "MirrorMaker2 diagnostic";

    private static final String STEP_MM2_STATUS = "mm2_status";
    private static final String STEP_MM2_PODS = "mm2_pods";
    private static final String STEP_MM2_LOGS = "mm2_logs";
    private static final String STEP_EVENTS = "events";

    @Inject
    KafkaMirrorMaker2Service mirrorMakerService;

    @Inject
    StrimziEventsService eventsService;

    @Inject
    ObjectMapper objectMapper;

    @ConfigProperty(name = "mcp.log.tail-lines", defaultValue = "200")
    int defaultTailLines;

    @ConfigProperty(name = "mcp.sampling.triage-max-tokens", defaultValue = "200")
    int triageMaxTokens;

    @ConfigProperty(name = "mcp.sampling.analysis-max-tokens", defaultValue = "1500")
    int analysisMaxTokens;

    KafkaMirrorMaker2DiagnosticService() {
    }

    /**
     * Run a multi-step diagnostic for a KafkaMirrorMaker2 instance.
     *
     * @param namespace       optional namespace
     * @param mirrorMakerName the MM2 name
     * @param symptom         optional symptom description
     * @param sinceMinutes    optional time window for logs/events
     * @param sampling        MCP Sampling for LLM analysis
     * @param elicitation     MCP Elicitation for user input
     * @param mcpLog          MCP log notifications
     * @param progress        MCP progress tracking
     * @param cancellation    MCP cancellation checking
     * @return the diagnostic report
     */
    @SuppressWarnings("checkstyle:ParameterNumber")
    public KafkaMirrorMaker2DiagnosticReport diagnose(final String namespace,
                                                       final String mirrorMakerName,
                                                       final String symptom,
                                                       final Integer sinceMinutes,
                                                       final Sampling sampling,
                                                       final Elicitation elicitation,
                                                       final McpLog mcpLog,
                                                       final Progress progress,
                                                       final Cancellation cancellation) {
        String ns = InputUtils.normalizeInput(namespace);
        String name = InputUtils.normalizeInput(mirrorMakerName);

        if (name == null) {
            throw new ToolCallException("MirrorMaker2 name is required");
        }

        LOG.infof("Starting diagnostic for MirrorMaker2=%s (namespace=%s, symptom=%s)",
            name, ns != null ? ns : "auto", symptom);

        List<String> completed = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        int stepIndex = 0;

        // === Phase 1: Initial data gathering ===
        int maxSteps = PHASE1_STEPS + MAX_PHASE2_STEPS;

        KafkaMirrorMaker2Response mm2Status = gatherMm2Status(
            ns, name, elicitation, completed, mcpLog);
        DiagnosticHelper.sendProgress(progress, ++stepIndex, maxSteps, DIAGNOSTIC_LABEL);
        DiagnosticHelper.checkCancellation(cancellation);

        String resolvedNs = mm2Status != null ? mm2Status.namespace() : ns;

        KafkaMirrorMaker2PodsResponse pods = gatherPods(
            resolvedNs, name, completed, failed, mcpLog);
        DiagnosticHelper.sendProgress(progress, ++stepIndex, maxSteps, DIAGNOSTIC_LABEL);
        DiagnosticHelper.checkCancellation(cancellation);

        // === Phase 2: Deep investigation (always gather all for MM2) ===
        Integer sinceSeconds = sinceMinutes != null ? sinceMinutes * 60 : null;

        KafkaMirrorMaker2LogsResponse logs = gatherLogs(
            resolvedNs, name, sinceSeconds, completed, failed, mcpLog);
        DiagnosticHelper.sendProgress(progress, ++stepIndex, maxSteps, DIAGNOSTIC_LABEL);
        DiagnosticHelper.checkCancellation(cancellation);

        StrimziEventsResponse events = gatherEvents(
            resolvedNs, name, sinceMinutes, completed, failed, mcpLog);
        DiagnosticHelper.sendProgress(progress, ++stepIndex, maxSteps, DIAGNOSTIC_LABEL);

        // === Phase 3: Analysis ===
        String analysis = produceAnalysis(sampling, mm2Status, pods, logs, events, symptom);

        return KafkaMirrorMaker2DiagnosticReport.of(mm2Status, pods, logs, events,
            analysis, completed, failed.isEmpty() ? null : failed);
    }

    // ---- Phase 1 ----

    @WithSpan("diagnose.mm2.status")
    KafkaMirrorMaker2Response gatherMm2Status(final String namespace,
                                                final String name,
                                                final Elicitation elicitation,
                                                final List<String> completed,
                                                final McpLog mcpLog) {
        try {
            KafkaMirrorMaker2Response result = mirrorMakerService.getMirrorMaker(namespace, name);
            completed.add(STEP_MM2_STATUS);
            DiagnosticHelper.sendClientNotification(mcpLog,
                "Checked MirrorMaker2 status: " + result.readiness());
            return result;
        } catch (ToolCallException e) {
            if (NamespaceElicitationHelper.isMultipleNamespacesError(e)
                    && elicitation != null && elicitation.isSupported()) {
                String resolved = NamespaceElicitationHelper.elicitNamespace(
                    e, elicitation, "diagnosed");
                return gatherMm2Status(resolved, name, null, completed, mcpLog);
            }
            throw e;
        }
    }

    @WithSpan("diagnose.mm2.pods")
    KafkaMirrorMaker2PodsResponse gatherPods(final String namespace,
                                               final String name,
                                               final List<String> completed,
                                               final List<String> failed,
                                               final McpLog mcpLog) {
        try {
            KafkaMirrorMaker2PodsResponse result = mirrorMakerService.getMirrorMakerPods(
                namespace, name);
            completed.add(STEP_MM2_PODS);
            DiagnosticHelper.sendClientNotification(mcpLog, "Checked MirrorMaker2 pod health");
            return result;
        } catch (Exception e) {
            LOG.warnf("Failed to gather MM2 pods: %s", e.getMessage());
            failed.add(STEP_MM2_PODS + ": " + e.getMessage());
            return null;
        }
    }

    // ---- Phase 2 ----

    @WithSpan("diagnose.mm2.logs")
    KafkaMirrorMaker2LogsResponse gatherLogs(final String namespace,
                                               final String name,
                                               final Integer sinceSeconds,
                                               final List<String> completed,
                                               final List<String> failed,
                                               final McpLog mcpLog) {
        try {
            LogCollectionParams options = LogCollectionParams.builder(defaultTailLines)
                .filter("errors")
                .sinceSeconds(sinceSeconds)
                .build();
            KafkaMirrorMaker2LogsResponse result = mirrorMakerService.getMirrorMakerLogs(
                namespace, name, options);
            completed.add(STEP_MM2_LOGS);
            DiagnosticHelper.sendClientNotification(mcpLog, "Collected MirrorMaker2 logs");
            return result;
        } catch (Exception e) {
            LOG.warnf("Failed to gather MM2 logs: %s", e.getMessage());
            failed.add(STEP_MM2_LOGS + ": " + e.getMessage());
            return null;
        }
    }

    @WithSpan("diagnose.mm2.events")
    StrimziEventsResponse gatherEvents(final String namespace,
                                        final String name,
                                        final Integer sinceMinutes,
                                        final List<String> completed,
                                        final List<String> failed,
                                        final McpLog mcpLog) {
        try {
            StrimziEventsResponse result = eventsService.getClusterEvents(
                namespace, name, sinceMinutes);
            completed.add(STEP_EVENTS);
            DiagnosticHelper.sendClientNotification(mcpLog,
                String.format("Found %d related events", result.totalEvents()));
            return result;
        } catch (Exception e) {
            LOG.warnf("Failed to gather events: %s", e.getMessage());
            failed.add(STEP_EVENTS + ": " + e.getMessage());
            return null;
        }
    }

    // ---- Phase 3: Analysis ----

    @WithSpan("diagnose.mm2.analysis")
    @SuppressWarnings("checkstyle:ParameterNumber")
    String produceAnalysis(final Sampling sampling,
                           final KafkaMirrorMaker2Response mm2Status,
                           final KafkaMirrorMaker2PodsResponse pods,
                           final KafkaMirrorMaker2LogsResponse logs,
                           final StrimziEventsResponse events,
                           final String symptom) {
        if (sampling == null || !sampling.isSupported()) {
            return null;
        }

        try {
            Map<String, Object> data = new LinkedHashMap<>();
            if (symptom != null) {
                data.put("symptom", symptom);
            }
            DiagnosticHelper.putIfNotNull(data, STEP_MM2_STATUS, mm2Status);
            DiagnosticHelper.putIfNotNull(data, STEP_MM2_PODS, pods);
            DiagnosticHelper.putIfNotNull(data, STEP_MM2_LOGS, logs);
            DiagnosticHelper.putIfNotNull(data, STEP_EVENTS, events);

            String dataJson = objectMapper.writeValueAsString(data);
            SamplingResponse response = sampling.requestBuilder()
                .setSystemPrompt(ANALYSIS_SYSTEM_PROMPT)
                .addMessage(SamplingMessage.withUserRole(dataJson))
                .setMaxTokens(analysisMaxTokens)
                .build()
                .sendAndAwait();

            return DiagnosticHelper.extractSamplingText(response);
        } catch (Exception e) {
            LOG.warnf("Sampling analysis failed: %s: %s",
                e.getClass().getSimpleName(), e.getMessage());
            return null;
        }
    }

    static final String ANALYSIS_SYSTEM_PROMPT = """
        You are diagnosing a KafkaMirrorMaker2 cross-cluster replication issue. \
        Analyze all gathered data and produce a structured diagnosis.

        Structure your response as:
        - Root cause (one sentence)
        - Severity: CRITICAL / HIGH / MEDIUM / LOW
        - Impact: what is affected (data replication, offset sync, monitoring)
        - Evidence: key findings from status, pods, logs, events
        - Recommendations: specific, actionable remediation steps

        Common MM2 issue categories:
        1. Source cluster connectivity (authentication, TLS, network)
        2. Target cluster write failures (authorization, disk full)
        3. Topic pattern misconfiguration (wrong patterns, internal topics)
        4. Consumer group offset sync failures (checkpoint connector)
        5. Resource exhaustion (OOM, CPU throttling on workers)
        6. Operator reconciliation failures (invalid CR, image issues)\
        """;
}
