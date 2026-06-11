/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.service.kafkaconnect;

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
import io.streamshub.mcp.strimzi.config.StrimziConstants;
import io.streamshub.mcp.strimzi.dto.kafkaconnect.KafkaConnectDiagnosticReport;
import io.streamshub.mcp.strimzi.dto.kafkaconnect.KafkaConnectLogsResponse;
import io.streamshub.mcp.strimzi.dto.kafkaconnect.KafkaConnectPodsResponse;
import io.streamshub.mcp.strimzi.dto.kafkaconnect.KafkaConnectResponse;
import io.streamshub.mcp.strimzi.dto.kafkaconnect.KafkaConnectorResponse;
import io.streamshub.mcp.strimzi.dto.metrics.KafkaConnectMetricsResponse;
import io.streamshub.mcp.strimzi.dto.operator.StrimziEventsResponse;
import io.streamshub.mcp.strimzi.service.metrics.KafkaConnectMetricsService;
import io.streamshub.mcp.strimzi.service.operator.StrimziEventsService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
/**
 * Orchestrates a multi-step diagnostic workflow for KafkaConnect clusters.
 *
 * <p>Phase 1 gathers cluster status and connector inventory. Phase 2 gathers
 * pod health, logs, and events. Phase 3 uses Sampling for root cause analysis.</p>
 *
 * <p>Distinct from {@link KafkaConnectorDiagnosticService} which diagnoses
 * individual connectors. This service diagnoses the Connect cluster platform.</p>
 */
@ApplicationScoped
public class KafkaConnectDiagnosticService {

    private static final Logger LOG = Logger.getLogger(KafkaConnectDiagnosticService.class);
    private static final int PHASE1_STEPS = 2;
    private static final int PHASE2_STEPS = 4;
    private static final int SECONDS_PER_MINUTE = 60;
    private static final String DIAGNOSTIC_LABEL = "KafkaConnect cluster diagnostic";

    private static final String STEP_CONNECT_STATUS = "connect_status";
    private static final String STEP_CONNECTORS = "connectors";
    private static final String STEP_CONNECT_PODS = "connect_pods";
    private static final String STEP_CONNECT_LOGS = "connect_logs";
    private static final String STEP_CONNECT_METRICS = "connect_metrics";
    private static final String STEP_EVENTS = "events";

    @Inject
    KafkaConnectService connectService;

    @Inject
    KafkaConnectorService connectorService;

    @Inject
    KafkaConnectMetricsService connectMetricsService;

    @Inject
    StrimziEventsService eventsService;

    @Inject
    ObjectMapper objectMapper;

    @ConfigProperty(name = "mcp.log.tail-lines", defaultValue = "200")
    int defaultTailLines;

    @ConfigProperty(name = "mcp.sampling.analysis-max-tokens", defaultValue = "1500")
    int analysisMaxTokens;

    KafkaConnectDiagnosticService() {
    }

    /**
     * Run a multi-step diagnostic for a KafkaConnect cluster.
     *
     * @param namespace       optional namespace
     * @param connectName     the KafkaConnect cluster name
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
    public KafkaConnectDiagnosticReport diagnose(final String namespace,
                                                  final String connectName,
                                                  final String symptom,
                                                  final Integer sinceMinutes,
                                                  final Sampling sampling,
                                                  final Elicitation elicitation,
                                                  final McpLog mcpLog,
                                                  final Progress progress,
                                                  final Cancellation cancellation) {
        String ns = InputUtils.normalizeInput(namespace);
        String name = InputUtils.normalizeInput(connectName);

        if (name == null) {
            throw new ToolCallException("KafkaConnect cluster name is required");
        }

        LOG.infof("Starting diagnostic for KafkaConnect cluster=%s (namespace=%s, symptom=%s)",
            name, ns != null ? ns : "auto", symptom);

        List<String> completed = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        int stepIndex = 0;
        int maxSteps = PHASE1_STEPS + PHASE2_STEPS;

        // === Phase 1: Initial data gathering ===
        KafkaConnectResponse connectCluster = gatherConnectStatus(
            ns, name, elicitation, completed, mcpLog);
        DiagnosticHelper.sendProgress(progress, ++stepIndex, maxSteps, DIAGNOSTIC_LABEL);
        DiagnosticHelper.checkCancellation(cancellation);

        String resolvedNs = connectCluster != null ? connectCluster.namespace() : ns;

        List<KafkaConnectorResponse> connectors = gatherConnectors(
            resolvedNs, name, completed, failed, mcpLog);
        DiagnosticHelper.sendProgress(progress, ++stepIndex, maxSteps, DIAGNOSTIC_LABEL);
        DiagnosticHelper.checkCancellation(cancellation);

        // === Phase 2: Deep investigation ===
        KafkaConnectPodsResponse pods = gatherPods(
            resolvedNs, name, completed, failed, mcpLog);
        DiagnosticHelper.sendProgress(progress, ++stepIndex, maxSteps, DIAGNOSTIC_LABEL);
        DiagnosticHelper.checkCancellation(cancellation);

        KafkaConnectLogsResponse logs = gatherLogs(
            resolvedNs, name, sinceMinutes, completed, failed, mcpLog);
        DiagnosticHelper.sendProgress(progress, ++stepIndex, maxSteps, DIAGNOSTIC_LABEL);
        DiagnosticHelper.checkCancellation(cancellation);

        KafkaConnectMetricsResponse connectMetrics = gatherMetrics(
            resolvedNs, name, completed, failed, mcpLog);
        DiagnosticHelper.sendProgress(progress, ++stepIndex, maxSteps, DIAGNOSTIC_LABEL);
        DiagnosticHelper.checkCancellation(cancellation);

        StrimziEventsResponse events = gatherEvents(
            resolvedNs, name, sinceMinutes, completed, failed, mcpLog);
        DiagnosticHelper.sendProgress(progress, ++stepIndex, maxSteps, DIAGNOSTIC_LABEL);

        // === Phase 3: Analysis ===
        String analysis = produceAnalysis(
            sampling, connectCluster, connectors, pods, logs, connectMetrics, events, symptom);

        return KafkaConnectDiagnosticReport.of(connectCluster, connectors, pods, logs,
            connectMetrics, events, analysis, completed, failed.isEmpty() ? null : failed);
    }

    // ---- Phase 1 ----

    @WithSpan("diagnose.connect.status")
    KafkaConnectResponse gatherConnectStatus(final String namespace,
                                              final String name,
                                              final Elicitation elicitation,
                                              final List<String> completed,
                                              final McpLog mcpLog) {
        try {
            KafkaConnectResponse result = connectService.getConnect(namespace, name);
            completed.add(STEP_CONNECT_STATUS);
            DiagnosticHelper.sendClientNotification(mcpLog,
                "Checked KafkaConnect cluster status: " + result.readiness());
            return result;
        } catch (ToolCallException e) {
            if (NamespaceElicitationHelper.isMultipleNamespacesError(e)
                    && elicitation != null && elicitation.isFormModeSupported()) {
                String resolved = NamespaceElicitationHelper.elicitNamespace(
                    e, elicitation, "diagnosed");
                return gatherConnectStatus(resolved, name, null, completed, mcpLog);
            }
            throw e;
        }
    }

    @WithSpan("diagnose.connect.connectors")
    List<KafkaConnectorResponse> gatherConnectors(final String namespace,
                                                    final String connectName,
                                                    final List<String> completed,
                                                    final List<String> failed,
                                                    final McpLog mcpLog) {
        try {
            List<KafkaConnectorResponse> result = connectorService.listConnectors(
                namespace, connectName);
            completed.add(STEP_CONNECTORS);
            long failedCount = result.stream()
                .filter(c -> !"Ready".equals(c.readiness()))
                .count();
            DiagnosticHelper.sendClientNotification(mcpLog,
                String.format("Found %d connectors (%d not ready)", result.size(), failedCount));
            return result;
        } catch (Exception e) {
            LOG.warnf("Failed to list connectors: %s", e.getMessage());
            failed.add(STEP_CONNECTORS + ": " + e.getMessage());
            return null;
        }
    }

    // ---- Phase 2 ----

    @WithSpan("diagnose.connect.pods")
    KafkaConnectPodsResponse gatherPods(final String namespace,
                                         final String connectName,
                                         final List<String> completed,
                                         final List<String> failed,
                                         final McpLog mcpLog) {
        try {
            KafkaConnectPodsResponse result = connectService.getConnectPods(
                namespace, connectName);
            completed.add(STEP_CONNECT_PODS);
            DiagnosticHelper.sendClientNotification(mcpLog, "Checked KafkaConnect pod health");
            return result;
        } catch (Exception e) {
            LOG.warnf("Failed to gather KafkaConnect pods: %s", e.getMessage());
            failed.add(STEP_CONNECT_PODS + ": " + e.getMessage());
            return null;
        }
    }

    @WithSpan("diagnose.connect.logs")
    KafkaConnectLogsResponse gatherLogs(final String namespace,
                                         final String connectName,
                                         final Integer sinceMinutes,
                                         final List<String> completed,
                                         final List<String> failed,
                                         final McpLog mcpLog) {
        try {
            LogCollectionParams options = LogCollectionParams.builder(defaultTailLines)
                .filter("errors")
                .sinceSeconds(sinceMinutes != null ? sinceMinutes * SECONDS_PER_MINUTE : null)
                .build();
            KafkaConnectLogsResponse result = connectService.getConnectLogs(
                namespace, connectName, options);
            completed.add(STEP_CONNECT_LOGS);
            DiagnosticHelper.sendClientNotification(mcpLog, "Collected KafkaConnect logs");
            return result;
        } catch (Exception e) {
            LOG.warnf("Failed to gather KafkaConnect logs: %s", e.getMessage());
            failed.add(STEP_CONNECT_LOGS + ": " + e.getMessage());
            return null;
        }
    }

    @WithSpan("diagnose.connect.metrics")
    KafkaConnectMetricsResponse gatherMetrics(final String namespace,
                                               final String connectName,
                                               final List<String> completed,
                                               final List<String> failed,
                                               final McpLog mcpLog) {
        try {
            KafkaConnectMetricsResponse result = connectMetricsService.getKafkaConnectMetrics(
                namespace, connectName, "worker", null, null, null, null, null, null);
            completed.add(STEP_CONNECT_METRICS);
            DiagnosticHelper.sendClientNotification(mcpLog, "Gathered KafkaConnect worker metrics");
            return result;
        } catch (Exception e) {
            LOG.warnf("Failed to gather KafkaConnect metrics: %s", e.getMessage());
            failed.add(STEP_CONNECT_METRICS + ": " + e.getMessage());
            return null;
        }
    }

    @WithSpan("diagnose.connect.events")
    StrimziEventsResponse gatherEvents(final String namespace,
                                        final String connectName,
                                        final Integer sinceMinutes,
                                        final List<String> completed,
                                        final List<String> failed,
                                        final McpLog mcpLog) {
        try {
            StrimziEventsResponse result = eventsService.getResourceEvents(
                namespace, connectName, StrimziConstants.KindValues.KAFKA_CONNECT, sinceMinutes);
            completed.add(STEP_EVENTS);
            DiagnosticHelper.sendClientNotification(mcpLog,
                String.format("Found %d KafkaConnect related events", result.totalEvents()));
            return result;
        } catch (Exception e) {
            LOG.warnf("Failed to gather KafkaConnect events: %s", e.getMessage());
            failed.add(STEP_EVENTS + ": " + e.getMessage());
            return null;
        }
    }

    // ---- Phase 3: Analysis ----

    @WithSpan("diagnose.connect.analysis")
    @SuppressWarnings("checkstyle:ParameterNumber")
    String produceAnalysis(final Sampling sampling,
                           final KafkaConnectResponse connectCluster,
                           final List<KafkaConnectorResponse> connectors,
                           final KafkaConnectPodsResponse pods,
                           final KafkaConnectLogsResponse logs,
                           final KafkaConnectMetricsResponse connectMetrics,
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
            DiagnosticHelper.putIfNotNull(data, STEP_CONNECT_STATUS, connectCluster);
            DiagnosticHelper.putIfNotNull(data, STEP_CONNECTORS, connectors);
            DiagnosticHelper.putIfNotNull(data, STEP_CONNECT_PODS, pods);
            DiagnosticHelper.putIfNotNull(data, STEP_CONNECT_LOGS, logs);
            DiagnosticHelper.putIfNotNull(data, STEP_CONNECT_METRICS, connectMetrics);
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
        You are diagnosing a KafkaConnect cluster platform issue. \
        Analyze all gathered data and produce a structured diagnosis.

        Structure your response as:
        - Root cause (one sentence)
        - Severity: CRITICAL / HIGH / MEDIUM / LOW
        - Impact: what is affected (all connectors, specific connectors, cluster stability)
        - Evidence: key findings from status, connectors, pods, logs, metrics, events
        - Recommendations: specific, actionable remediation steps

        Common KafkaConnect cluster issue categories:
        1. Deployment/operator issues (reconciliation failures, image pull errors, invalid config)
        2. Resource exhaustion (OOM kills, CPU throttling, insufficient replicas for load)
        3. Plugin issues (missing connector classes, version incompatibility, classpath conflicts)
        4. Worker rebalancing storms (frequent task reassignment, group coordinator issues)
        5. Kafka connectivity (bootstrap unreachable, authentication/TLS failures)
        6. Widespread connector failures (cluster-wide vs isolated — check connector inventory)\
        """;
}
