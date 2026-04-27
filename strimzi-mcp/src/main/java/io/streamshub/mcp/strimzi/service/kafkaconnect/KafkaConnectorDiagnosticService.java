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
import io.streamshub.mcp.strimzi.dto.StrimziEventsResponse;
import io.streamshub.mcp.strimzi.dto.kafkaconnect.KafkaConnectLogsResponse;
import io.streamshub.mcp.strimzi.dto.kafkaconnect.KafkaConnectPodsResponse;
import io.streamshub.mcp.strimzi.dto.kafkaconnect.KafkaConnectResponse;
import io.streamshub.mcp.strimzi.dto.kafkaconnect.KafkaConnectorDiagnosticReport;
import io.streamshub.mcp.strimzi.dto.kafkaconnect.KafkaConnectorResponse;
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
 * Orchestrates a multistep diagnostic workflow for KafkaConnectors.
 *
 * <p>Gathers data from the connector, its parent KafkaConnect cluster,
 * Connect pods, logs, and events in a single operation. Optionally uses
 * MCP Sampling for LLM triage and analysis.</p>
 *
 * <p>Individual step failures do not abort the workflow. Failed steps are
 * recorded and the report includes all data that was successfully gathered.</p>
 */
@ApplicationScoped
public class KafkaConnectorDiagnosticService {

    private static final Logger LOG = Logger.getLogger(KafkaConnectorDiagnosticService.class);
    private static final int SECONDS_PER_MINUTE = 60;
    private static final int PHASE1_STEPS = 2;
    private static final int MAX_PHASE2_STEPS = 3;
    private static final String DIAGNOSTIC_LABEL = "KafkaConnector diagnostic";

    private static final String STEP_CONNECTOR_STATUS = "connector_status";
    private static final String STEP_CONNECT_CLUSTER = "connect_cluster";
    private static final String STEP_CONNECT_PODS = "connect_pods";
    private static final String STEP_CONNECT_LOGS = "connect_logs";
    private static final String STEP_EVENTS = "events";

    @Inject
    KafkaConnectorService connectorService;

    @Inject
    KafkaConnectService connectService;

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

    KafkaConnectorDiagnosticService() {
    }

    /**
     * Run a multistep diagnostic workflow for a KafkaConnector.
     *
     * <p>Phase 1 gathers connector status and parent Connect cluster status.
     * Phase 2 uses Sampling (if supported) to decide which areas need deeper
     * investigation, then gathers Connect pods, logs, and events. Phase 3
     * uses Sampling to produce a root cause analysis.</p>
     *
     * @param namespace     optional namespace (elicited if ambiguous)
     * @param connectorName the KafkaConnector name
     * @param symptom       optional symptom description for context
     * @param sinceMinutes  optional time window for logs and events
     * @param sampling      MCP Sampling for LLM analysis (may be unsupported)
     * @param elicitation   MCP Elicitation for user input (may be unsupported)
     * @param mcpLog        MCP log for progress notifications
     * @param progress      MCP progress tracking
     * @param cancellation  MCP cancellation checking
     * @return a consolidated diagnostic report
     */
    @SuppressWarnings("checkstyle:ParameterNumber")
    public KafkaConnectorDiagnosticReport diagnose(final String namespace,
                                                    final String connectorName,
                                                    final String symptom,
                                                    final Integer sinceMinutes,
                                                    final Sampling sampling,
                                                    final Elicitation elicitation,
                                                    final McpLog mcpLog,
                                                    final Progress progress,
                                                    final Cancellation cancellation) {
        String ns = InputUtils.normalizeInput(namespace);
        String name = InputUtils.normalizeInput(connectorName);

        if (name == null) {
            throw new ToolCallException("Connector name is required");
        }

        LOG.infof("Starting diagnostic for KafkaConnector=%s (namespace=%s, symptom=%s)",
            name, ns != null ? ns : "auto", symptom);

        List<String> completed = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        int stepIndex = 0;

        // === Phase 1: Initial data gathering ===
        int maxSteps = PHASE1_STEPS + MAX_PHASE2_STEPS;
        KafkaConnectorResponse connector = gatherConnectorStatus(
            ns, name, elicitation, completed, mcpLog);
        DiagnosticHelper.sendProgress(progress, ++stepIndex, maxSteps, DIAGNOSTIC_LABEL);
        DiagnosticHelper.checkCancellation(cancellation);

        String resolvedNs = connector.namespace();
        String connectClusterName = connector.connectCluster();

        KafkaConnectResponse connectCluster = gatherConnectCluster(
            resolvedNs, connectClusterName, completed, failed, mcpLog);
        DiagnosticHelper.sendProgress(progress, ++stepIndex, maxSteps, DIAGNOSTIC_LABEL);
        DiagnosticHelper.checkCancellation(cancellation);

        // === Phase 2: Deep investigation ===
        InvestigationAreas areas = investigateAreas(
            sampling, connector, connectCluster, symptom);

        int totalSteps = PHASE1_STEPS + areas.enabledCount();

        KafkaConnectPodsResponse connectPods = null;
        if (areas.connectPods) {
            connectPods = gatherConnectPods(
                resolvedNs, connectClusterName, completed, failed, mcpLog);
            DiagnosticHelper.sendProgress(progress, ++stepIndex, totalSteps, DIAGNOSTIC_LABEL);
            DiagnosticHelper.checkCancellation(cancellation);
        }

        KafkaConnectLogsResponse connectLogs = null;
        if (areas.connectLogs) {
            connectLogs = gatherConnectLogs(
                resolvedNs, connectClusterName, connector.className(),
                sinceMinutes, completed, failed, mcpLog);
            DiagnosticHelper.sendProgress(progress, ++stepIndex, totalSteps, DIAGNOSTIC_LABEL);
            DiagnosticHelper.checkCancellation(cancellation);
        }

        StrimziEventsResponse events = null;
        if (areas.events) {
            events = gatherEvents(
                resolvedNs, connectClusterName, sinceMinutes, completed, failed, mcpLog);
            DiagnosticHelper.sendProgress(progress, ++stepIndex, totalSteps, DIAGNOSTIC_LABEL);
            DiagnosticHelper.checkCancellation(cancellation);
        }

        // === Phase 3: Final analysis ===
        String analysis = produceAnalysis(sampling, connector, connectCluster,
            connectPods, connectLogs, events, symptom);

        return KafkaConnectorDiagnosticReport.of(connector, connectCluster, connectPods, connectLogs,
            events, analysis, completed, failed.isEmpty() ? null : failed);
    }

    // ---- Phase 1: Initial data gathering ----

    @WithSpan("diagnose.connector.status")
    KafkaConnectorResponse gatherConnectorStatus(final String namespace,
                                                  final String connectorName,
                                                  final Elicitation elicitation,
                                                  final List<String> completed,
                                                  final McpLog mcpLog) {
        try {
            KafkaConnectorResponse result = connectorService.getConnector(namespace, connectorName);
            completed.add(STEP_CONNECTOR_STATUS);
            DiagnosticHelper.sendClientNotification(mcpLog,
                "Checked KafkaConnector status: " + result.readiness() + " (state: " + result.state() + ")");
            return result;
        } catch (ToolCallException e) {
            if (NamespaceElicitationHelper.isMultipleNamespacesError(e)
                    && elicitation != null && elicitation.isSupported()) {
                String resolved = NamespaceElicitationHelper.elicitNamespace(e, elicitation, "diagnosed");
                return gatherConnectorStatus(resolved, connectorName, null, completed, mcpLog);
            }
            throw e;
        }
    }

    @WithSpan("diagnose.connector.connect_cluster")
    KafkaConnectResponse gatherConnectCluster(final String namespace,
                                               final String connectClusterName,
                                               final List<String> completed,
                                               final List<String> failed,
                                               final McpLog mcpLog) {
        if (connectClusterName == null) {
            failed.add(STEP_CONNECT_CLUSTER + ": no strimzi.io/cluster label on connector");
            return null;
        }
        try {
            KafkaConnectResponse result = connectService.getConnect(namespace, connectClusterName);
            completed.add(STEP_CONNECT_CLUSTER);
            DiagnosticHelper.sendClientNotification(mcpLog,
                "Checked KafkaConnect cluster '" + connectClusterName + "' status: " + result.readiness());
            return result;
        } catch (Exception e) {
            LOG.warnf("Failed to gather KafkaConnect cluster status: %s", e.getMessage());
            failed.add(STEP_CONNECT_CLUSTER + ": " + e.getMessage());
            return null;
        }
    }

    // ---- Phase 2: Deep investigation ----

    @WithSpan("diagnose.connector.connect_pods")
    KafkaConnectPodsResponse gatherConnectPods(final String namespace,
                                                final String connectClusterName,
                                                final List<String> completed,
                                                final List<String> failed,
                                                final McpLog mcpLog) {
        try {
            KafkaConnectPodsResponse result = connectService.getConnectPods(namespace, connectClusterName);
            completed.add(STEP_CONNECT_PODS);
            DiagnosticHelper.sendClientNotification(mcpLog, "Checked KafkaConnect pod health");
            return result;
        } catch (Exception e) {
            LOG.warnf("Failed to gather KafkaConnect pod health: %s", e.getMessage());
            failed.add(STEP_CONNECT_PODS + ": " + e.getMessage());
            return null;
        }
    }

    @WithSpan("diagnose.connector.connect_logs")
    KafkaConnectLogsResponse gatherConnectLogs(final String namespace,
                                                final String connectClusterName,
                                                final String connectorClassName,
                                                final Integer sinceMinutes,
                                                final List<String> completed,
                                                final List<String> failed,
                                                final McpLog mcpLog) {
        try {
            LogCollectionParams.Builder builder = LogCollectionParams.builder(defaultTailLines)
                .filter("errors")
                .sinceSeconds(sinceMinutes != null ? sinceMinutes * SECONDS_PER_MINUTE : null);

            if (connectorClassName != null) {
                String shortName = connectorClassName.contains(".")
                    ? connectorClassName.substring(connectorClassName.lastIndexOf('.') + 1)
                    : connectorClassName;
                builder.keywords(List.of(shortName));
            }

            KafkaConnectLogsResponse result = connectService.getConnectLogs(
                namespace, connectClusterName, builder.build());
            completed.add(STEP_CONNECT_LOGS);
            DiagnosticHelper.sendClientNotification(mcpLog, "Collected KafkaConnect logs");
            return result;
        } catch (Exception e) {
            LOG.warnf("Failed to gather KafkaConnect logs: %s", e.getMessage());
            failed.add(STEP_CONNECT_LOGS + ": " + e.getMessage());
            return null;
        }
    }

    @WithSpan("diagnose.connector.events")
    StrimziEventsResponse gatherEvents(final String namespace,
                                        final String connectClusterName,
                                        final Integer sinceMinutes,
                                        final List<String> completed,
                                        final List<String> failed,
                                        final McpLog mcpLog) {
        try {
            StrimziEventsResponse result = eventsService.getClusterEvents(
                namespace, connectClusterName, sinceMinutes);
            completed.add(STEP_EVENTS);
            DiagnosticHelper.sendClientNotification(mcpLog,
                String.format("Found %d KafkaConnect related events", result.totalEvents()));
            return result;
        } catch (Exception e) {
            LOG.warnf("Failed to gather KafkaConnect related events: %s", e.getMessage());
            failed.add(STEP_EVENTS + ": " + e.getMessage());
            return null;
        }
    }

    // ---- Sampling: triage and analysis ----

    @WithSpan("diagnose.connector.investigation")
    InvestigationAreas investigateAreas(final Sampling sampling,
                                                 final KafkaConnectorResponse connector,
                                                 final KafkaConnectResponse connectCluster,
                                                 final String symptom) {
        if (sampling == null || !sampling.isSupported()) {
            return InvestigationAreas.all();
        }

        try {
            Map<String, Object> summary = buildPhase1Summary(connector, connectCluster, symptom);
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

    @WithSpan("diagnose.connector.analysis")
    @SuppressWarnings("checkstyle:ParameterNumber")
    String produceAnalysis(final Sampling sampling,
                           final KafkaConnectorResponse connector,
                           final KafkaConnectResponse connectCluster,
                           final KafkaConnectPodsResponse connectPods,
                           final KafkaConnectLogsResponse connectLogs,
                           final StrimziEventsResponse events,
                           final String symptom) {
        if (sampling == null || !sampling.isSupported()) {
            return null;
        }

        try {
            Map<String, Object> fullData = buildFullSummary(
                connector, connectCluster, connectPods, connectLogs, events, symptom);
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

    private Map<String, Object> buildPhase1Summary(final KafkaConnectorResponse connector,
                                                    final KafkaConnectResponse connectCluster,
                                                    final String symptom) {
        Map<String, Object> summary = new LinkedHashMap<>();
        if (symptom != null) {
            summary.put("symptom", symptom);
        }
        summary.put("connector_state", connector.state());
        summary.put("connector_readiness", connector.readiness());
        summary.put("connector_class", connector.className());
        if (connectCluster != null) {
            summary.put("connect_cluster_readiness", connectCluster.readiness());
            if (connectCluster.replicas() != null) {
                summary.put("connect_expected_replicas", connectCluster.replicas().expected());
                summary.put("connect_ready_replicas", connectCluster.replicas().ready());
            }
        }
        return summary;
    }

    @SuppressWarnings("checkstyle:ParameterNumber")
    private Map<String, Object> buildFullSummary(final KafkaConnectorResponse connector,
                                                  final KafkaConnectResponse connectCluster,
                                                  final KafkaConnectPodsResponse connectPods,
                                                  final KafkaConnectLogsResponse connectLogs,
                                                  final StrimziEventsResponse events,
                                                  final String symptom) {
        Map<String, Object> data = new LinkedHashMap<>();
        if (symptom != null) {
            data.put("symptom", symptom);
        }
        DiagnosticHelper.putIfNotNull(data, STEP_CONNECTOR_STATUS, connector);
        DiagnosticHelper.putIfNotNull(data, STEP_CONNECT_CLUSTER, connectCluster);
        DiagnosticHelper.putIfNotNull(data, STEP_CONNECT_PODS, connectPods);
        DiagnosticHelper.putIfNotNull(data, STEP_CONNECT_LOGS, connectLogs);
        DiagnosticHelper.putIfNotNull(data, STEP_EVENTS, events);
        return data;
    }

    private InvestigationAreas parseInvestigationAreas(final SamplingResponse response) {
        try {
            String text = DiagnosticHelper.extractSamplingText(response);
            Map<String, Object> parsed = objectMapper.readValue(text, DiagnosticHelper.MAP_TYPE_REF);
            return new InvestigationAreas(
                Boolean.TRUE.equals(parsed.get(STEP_CONNECT_PODS)),
                Boolean.TRUE.equals(parsed.get(STEP_CONNECT_LOGS)),
                Boolean.TRUE.equals(parsed.get(STEP_EVENTS))
            );
        } catch (Exception e) {
            LOG.debugf("Could not parse triage response, investigating all: %s", e.getMessage());
            return InvestigationAreas.all();
        }
    }

    /**
     * Flags indicating which investigation areas the LLM recommended.
     *
     * @param connectPods whether to gather Connect pod health
     * @param connectLogs whether to gather Connect logs
     * @param events      whether to gather Kubernetes events
     */
    record InvestigationAreas(boolean connectPods, boolean connectLogs, boolean events) {

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
            if (connectPods) c++;
            if (connectLogs) c++;
            if (events) c++;
            return c;
        }
    }

    // ---- Sampling system prompts ----

    static final String TRIAGE_SYSTEM_PROMPT = """
        You are a Kafka Connect diagnostics assistant. \
        Analyze the initial connector and Connect cluster findings and decide which areas \
        need deeper investigation. \
        Return ONLY a JSON object with these boolean fields: \
        connect_pods, connect_logs, events. \
        Set true only for areas likely to reveal the root cause. \
        For example, if the connector is RUNNING and the Connect cluster is Ready, set all to false. \
        If the connector is FAILED, set connect_logs and events to true. \
        If the Connect cluster is NotReady, set connect_pods and connect_logs to true.\
        """;

    static final String ANALYSIS_SYSTEM_PROMPT = """
        You are diagnosing a KafkaConnector issue. Analyze all the gathered diagnostic data \
        and produce a concise root cause analysis.

        Distinguish between:
        1. Connector configuration errors (HIGH): wrong class, missing required config, invalid topics
        2. Connect platform issues (CRITICAL): OOM, pod crashes, insufficient resources, network
        3. Task failures (HIGH): serialization errors, schema issues, target system unavailable
        4. Rebalancing issues (MEDIUM): frequent task reassignment, uneven distribution
        5. Authentication/authorization (HIGH): missing credentials, expired certificates

        Structure your response as:
        - Root cause (single most likely cause)
        - Severity (CRITICAL/HIGH/MEDIUM/LOW)
        - Impact (what is affected)
        - Evidence (specific findings that support the diagnosis)
        - Recommendations (prioritized, actionable steps)

        Remember: the connector status from the REST API (connector_status field) contains the \
        actual runtime state and any error messages from tasks. Check task states carefully.\
        """;
}
