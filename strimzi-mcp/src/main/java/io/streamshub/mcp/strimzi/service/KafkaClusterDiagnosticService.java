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
import io.streamshub.mcp.common.dto.LogCollectionParams;
import io.streamshub.mcp.common.service.DiagnosticHelper;
import io.streamshub.mcp.common.util.InputUtils;
import io.streamshub.mcp.common.util.NamespaceElicitationHelper;
import io.streamshub.mcp.strimzi.dto.DrainCleanerLogsResponse;
import io.streamshub.mcp.strimzi.dto.DrainCleanerReadinessResponse;
import io.streamshub.mcp.strimzi.dto.KafkaClusterDiagnosticReport;
import io.streamshub.mcp.strimzi.dto.KafkaClusterLogsResponse;
import io.streamshub.mcp.strimzi.dto.KafkaClusterPodsResponse;
import io.streamshub.mcp.strimzi.dto.KafkaClusterResponse;
import io.streamshub.mcp.strimzi.dto.KafkaNodePoolResponse;
import io.streamshub.mcp.strimzi.dto.KafkaUserResponse;
import io.streamshub.mcp.strimzi.dto.StrimziEventsResponse;
import io.streamshub.mcp.strimzi.dto.StrimziOperatorLogsResponse;
import io.streamshub.mcp.strimzi.dto.StrimziOperatorResponse;
import io.streamshub.mcp.strimzi.dto.metrics.KafkaMetricsResponse;
import io.streamshub.mcp.strimzi.service.metrics.KafkaMetricsService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Orchestrates a multistep diagnostic workflow for Kafka clusters.
 *
 * <p>Gathers data from multiple services in a single operation, optionally
 * using MCP Sampling to get LLM analysis of intermediate results and
 * Elicitation to resolve ambiguous inputs (e.g., multiple namespaces).</p>
 *
 * <p>Individual step failures do not abort the workflow. Failed steps are
 * recorded and the report includes all data that was successfully gathered.</p>
 */
@ApplicationScoped
public class KafkaClusterDiagnosticService {

    private static final Logger LOG = Logger.getLogger(KafkaClusterDiagnosticService.class);
    private static final int SECONDS_PER_MINUTE = 60;
    private static final int PHASE1_STEPS = 4;
    private static final int MAX_PHASE2_STEPS = 6;
    private static final String DIAGNOSTIC_LABEL = "Kafka cluster diagnostic";

    private static final String STEP_CLUSTER_STATUS = "cluster_status";
    private static final String STEP_NODE_POOLS = "node_pools";
    private static final String STEP_POD_HEALTH = "pod_health";
    private static final String STEP_OPERATOR_STATUS = "operator_status";
    private static final String STEP_OPERATOR_LOGS = "operator_logs";
    private static final String STEP_CLUSTER_LOGS = "cluster_logs";
    private static final String STEP_EVENTS = "events";
    private static final String STEP_USERS = "users";
    private static final String STEP_METRICS = "metrics";
    private static final String STEP_DRAIN_CLEANER = "drain_cleaner";
    private static final String STEP_DRAIN_CLEANER_LOGS = "drain_cleaner_logs";

    @Inject
    KafkaService kafkaService;

    @Inject
    KafkaNodePoolService nodePoolService;

    @Inject
    StrimziOperatorService operatorService;

    @Inject
    StrimziEventsService eventsService;

    @Inject
    KafkaUserService kafkaUserService;

    @Inject
    KafkaMetricsService kafkaMetricsService;

    @Inject
    DrainCleanerService drainCleanerService;

    @Inject
    ObjectMapper objectMapper;

    @ConfigProperty(name = "mcp.log.tail-lines", defaultValue = "200")
    int defaultTailLines;

    @ConfigProperty(name = "mcp.sampling.triage-max-tokens", defaultValue = "200")
    int triageMaxTokens;

    @ConfigProperty(name = "mcp.sampling.analysis-max-tokens", defaultValue = "1500")
    int analysisMaxTokens;

    KafkaClusterDiagnosticService() {
    }

    /**
     * Run a multistep diagnostic workflow for a Kafka cluster.
     *
     * <p>Phase 1 gathers cluster status, node pools, and pod health.
     * Phase 2 uses Sampling (if supported) to decide which areas need
     * deeper investigation, then gathers operator logs, cluster logs,
     * events, and/or metrics. Phase 3 uses Sampling to produce a
     * root cause analysis.</p>
     *
     * @param namespace    optional namespace (elicited if ambiguous)
     * @param clusterName  the Kafka cluster name
     * @param symptom      optional symptom description for context
     * @param sinceMinutes optional time window for logs and events
     * @param sampling     MCP Sampling for LLM analysis (may be unsupported)
     * @param elicitation  MCP Elicitation for user input (may be unsupported)
     * @param mcpLog       MCP log for progress notifications
     * @param progress     MCP progress tracking
     * @param cancellation MCP cancellation checking
     * @return a consolidated diagnostic report
     */
    @SuppressWarnings("checkstyle:ParameterNumber")
    public KafkaClusterDiagnosticReport diagnose(final String namespace,
                                                 final String clusterName,
                                                 final String symptom,
                                                 final Integer sinceMinutes,
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

        LOG.infof("Starting diagnostic for cluster=%s (namespace=%s, symptom=%s)",
            name, ns != null ? ns : "auto", symptom);

        List<String> completed = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        int stepIndex = 0;

        // === Phase 1: Initial data gathering ===
        int maxSteps = PHASE1_STEPS + MAX_PHASE2_STEPS;
        KafkaClusterResponse cluster = gatherClusterStatus(
            ns, name, elicitation, completed, mcpLog);
        DiagnosticHelper.sendProgress(progress, ++stepIndex, maxSteps, DIAGNOSTIC_LABEL);
        DiagnosticHelper.checkCancellation(cancellation);

        // Resolve namespace from cluster response for subsequent calls
        String resolvedNs = cluster.namespace();

        List<KafkaNodePoolResponse> nodePools = gatherNodePools(
            resolvedNs, name, completed, failed, mcpLog);
        DiagnosticHelper.sendProgress(progress, ++stepIndex, maxSteps, DIAGNOSTIC_LABEL);
        DiagnosticHelper.checkCancellation(cancellation);

        KafkaClusterPodsResponse pods = gatherClusterPods(
            resolvedNs, name, completed, failed, mcpLog);
        DiagnosticHelper.sendProgress(progress, ++stepIndex, maxSteps, DIAGNOSTIC_LABEL);
        DiagnosticHelper.checkCancellation(cancellation);

        DrainCleanerReadinessResponse drainCleaner = gatherDrainCleanerStatus(
            completed, failed, mcpLog);
        DiagnosticHelper.sendProgress(progress, ++stepIndex, maxSteps, DIAGNOSTIC_LABEL);
        DiagnosticHelper.checkCancellation(cancellation);

        // === Phase 2: Deep investigation ===
        InvestigationAreas areas = decideInvestigationAreas(
            sampling, cluster, nodePools, pods, drainCleaner, symptom);

        int totalSteps = PHASE1_STEPS + areas.enabledCount();

        StrimziOperatorResponse operator = null;
        StrimziOperatorLogsResponse operatorLogs = null;
        if (areas.operatorLogs) {
            operator = gatherOperatorStatus(resolvedNs, completed, failed, mcpLog);
            operatorLogs = gatherOperatorLogs(
                resolvedNs, sinceMinutes, completed, failed, mcpLog);
            DiagnosticHelper.sendProgress(progress, ++stepIndex, totalSteps, DIAGNOSTIC_LABEL);
            DiagnosticHelper.checkCancellation(cancellation);
        }

        KafkaClusterLogsResponse clusterLogs = null;
        if (areas.clusterLogs) {
            clusterLogs = gatherClusterLogs(
                resolvedNs, name, sinceMinutes, completed, failed, mcpLog);
            DiagnosticHelper.sendProgress(progress, ++stepIndex, totalSteps, DIAGNOSTIC_LABEL);
            DiagnosticHelper.checkCancellation(cancellation);
        }

        StrimziEventsResponse events = null;
        if (areas.events) {
            events = gatherEvents(
                resolvedNs, name, sinceMinutes, completed, failed, mcpLog);
            DiagnosticHelper.sendProgress(progress, ++stepIndex, totalSteps, DIAGNOSTIC_LABEL);
            DiagnosticHelper.checkCancellation(cancellation);
        }

        List<KafkaUserResponse> users = null;
        if (areas.users) {
            users = gatherUsers(resolvedNs, name, completed, failed, mcpLog);
            DiagnosticHelper.sendProgress(progress, ++stepIndex, totalSteps, DIAGNOSTIC_LABEL);
            DiagnosticHelper.checkCancellation(cancellation);
        }

        KafkaMetricsResponse metrics = null;
        if (areas.metrics) {
            metrics = gatherMetrics(
                resolvedNs, name, completed, failed, mcpLog);
            DiagnosticHelper.sendProgress(progress, ++stepIndex, totalSteps, DIAGNOSTIC_LABEL);
            DiagnosticHelper.checkCancellation(cancellation);
        }

        DrainCleanerLogsResponse drainCleanerLogs = null;
        if (areas.drainCleanerLogs) {
            drainCleanerLogs = gatherDrainCleanerLogs(
                sinceMinutes, completed, failed, mcpLog);
            DiagnosticHelper.sendProgress(progress, ++stepIndex, totalSteps, DIAGNOSTIC_LABEL);
            DiagnosticHelper.checkCancellation(cancellation);
        }

        // === Phase 3: Final analysis ===
        String analysis = produceAnalysis(sampling, cluster, nodePools, pods,
            operator, operatorLogs, clusterLogs, events, users, metrics,
            drainCleaner, drainCleanerLogs, symptom);

        return KafkaClusterDiagnosticReport.of(cluster, nodePools, pods, operator,
            operatorLogs, clusterLogs, events, users, metrics, drainCleaner, drainCleanerLogs,
            analysis, completed, failed.isEmpty() ? null : failed);
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
                String resolved = NamespaceElicitationHelper.elicitNamespace(e, elicitation, "diagnosed");
                return gatherClusterStatus(resolved, clusterName, null,
                    completed, mcpLog);
            }
            throw e;
        }
    }

    private List<KafkaNodePoolResponse> gatherNodePools(final String namespace,
                                                        final String clusterName,
                                                        final List<String> completed,
                                                        final List<String> failed,
                                                        final McpLog mcpLog) {
        try {
            List<KafkaNodePoolResponse> result = nodePoolService.listNodePools(namespace, clusterName);
            completed.add(STEP_NODE_POOLS);
            DiagnosticHelper.sendClientNotification(mcpLog, String.format("Found %d KafkaNodePools", result.size()));
            return result;
        } catch (Exception e) {
            LOG.warnf("Failed to gather KafkaNodePools: %s", e.getMessage());
            failed.add(STEP_NODE_POOLS + ": " + e.getMessage());
            return List.of();
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

    private DrainCleanerReadinessResponse gatherDrainCleanerStatus(final List<String> completed,
                                                                    final List<String> failed,
                                                                    final McpLog mcpLog) {
        try {
            DrainCleanerReadinessResponse result = drainCleanerService.checkReadiness(null);
            completed.add(STEP_DRAIN_CLEANER);
            DiagnosticHelper.sendClientNotification(mcpLog,
                "Checked Strimzi Drain Cleaner readiness: "
                    + (result.deployed() ? (result.overallReady() ? "ready" : "not ready") : "not deployed"));
            return result;
        } catch (Exception e) {
            LOG.warnf("Failed to gather Drain Cleaner status: %s", e.getMessage());
            failed.add(STEP_DRAIN_CLEANER + ": " + e.getMessage());
            return DrainCleanerReadinessResponse.notDeployed();
        }
    }

    // ---- Phase 2: Deep investigation ----

    private StrimziOperatorResponse gatherOperatorStatus(final String namespace,
                                                         final List<String> completed,
                                                         final List<String> failed,
                                                         final McpLog mcpLog) {
        try {
            List<StrimziOperatorResponse> operators = operatorService.listOperators(namespace);
            if (!operators.isEmpty()) {
                completed.add(STEP_OPERATOR_STATUS);
                StrimziOperatorResponse op = operators.getFirst();
                DiagnosticHelper.sendClientNotification(mcpLog,
                    String.format("Checked Strimzi operator '%s' status: %s", op.name(), op.status()));
                return op;
            }
            return null;
        } catch (Exception e) {
            LOG.warnf("Failed to gather Strimzi operator status: %s", e.getMessage());
            failed.add(STEP_OPERATOR_STATUS + ": " + e.getMessage());
            return null;
        }
    }

    private StrimziOperatorLogsResponse gatherOperatorLogs(final String namespace,
                                                           final Integer sinceMinutes,
                                                           final List<String> completed,
                                                           final List<String> failed,
                                                           final McpLog mcpLog) {
        try {
            StrimziOperatorLogsResponse result = operatorService.getOperatorLogs(
                namespace, null, buildErrorLogParams(sinceMinutes));
            completed.add(STEP_OPERATOR_LOGS);
            DiagnosticHelper.sendClientNotification(mcpLog, "Collected Strimzi operator logs");
            return result;
        } catch (Exception e) {
            LOG.warnf("Failed to gather Strimzi operator logs: %s", e.getMessage());
            failed.add(STEP_OPERATOR_LOGS + ": " + e.getMessage());
            return null;
        }
    }

    private KafkaClusterLogsResponse gatherClusterLogs(final String namespace,
                                                       final String clusterName,
                                                       final Integer sinceMinutes,
                                                       final List<String> completed,
                                                       final List<String> failed,
                                                       final McpLog mcpLog) {
        try {
            KafkaClusterLogsResponse result = kafkaService.getClusterLogs(
                namespace, clusterName, buildErrorLogParams(sinceMinutes));
            completed.add(STEP_CLUSTER_LOGS);
            DiagnosticHelper.sendClientNotification(mcpLog, "Collected Kafka cluster logs");
            return result;
        } catch (Exception e) {
            LOG.warnf("Failed to gather Kafka cluster logs: %s", e.getMessage());
            failed.add(STEP_CLUSTER_LOGS + ": " + e.getMessage());
            return null;
        }
    }

    private StrimziEventsResponse gatherEvents(final String namespace,
                                               final String clusterName,
                                               final Integer sinceMinutes,
                                               final List<String> completed,
                                               final List<String> failed,
                                               final McpLog mcpLog) {
        try {
            StrimziEventsResponse result = eventsService.getClusterEvents(
                namespace, clusterName, sinceMinutes);
            completed.add(STEP_EVENTS);
            DiagnosticHelper.sendClientNotification(mcpLog, String.format("Found %d events", result.totalEvents()));
            return result;
        } catch (Exception e) {
            LOG.warnf("Failed to gather Kafka related events: %s", e.getMessage());
            failed.add(STEP_EVENTS + ": " + e.getMessage());
            return null;
        }
    }

    private KafkaMetricsResponse gatherMetrics(final String namespace,
                                               final String clusterName,
                                               final List<String> completed,
                                               final List<String> failed,
                                               final McpLog mcpLog) {
        try {
            KafkaMetricsResponse result = kafkaMetricsService.getKafkaMetrics(
                namespace, clusterName, "replication", null, null, null, null, null);
            completed.add(STEP_METRICS);
            DiagnosticHelper.sendClientNotification(mcpLog, "Retrieved Kafka replication metrics");
            return result;
        } catch (Exception e) {
            LOG.warnf("Failed to gather Kafka metrics: %s", e.getMessage());
            failed.add(STEP_METRICS + ": " + e.getMessage());
            return null;
        }
    }

    private List<KafkaUserResponse> gatherUsers(final String namespace,
                                                    final String clusterName,
                                                    final List<String> completed,
                                                    final List<String> failed,
                                                    final McpLog mcpLog) {
        try {
            List<KafkaUserResponse> result = kafkaUserService.listUsers(namespace, clusterName);
            completed.add(STEP_USERS);
            DiagnosticHelper.sendClientNotification(mcpLog,
                String.format("Found %d KafkaUsers for cluster", result.size()));
            return result;
        } catch (Exception e) {
            LOG.warnf("Failed to gather KafkaUsers: %s", e.getMessage());
            failed.add(STEP_USERS + ": " + e.getMessage());
            return null;
        }
    }

    private DrainCleanerLogsResponse gatherDrainCleanerLogs(final Integer sinceMinutes,
                                                              final List<String> completed,
                                                              final List<String> failed,
                                                              final McpLog mcpLog) {
        try {
            DrainCleanerLogsResponse result = drainCleanerService.getDrainCleanerLogs(
                null, null, buildErrorLogParams(sinceMinutes));
            completed.add(STEP_DRAIN_CLEANER_LOGS);
            DiagnosticHelper.sendClientNotification(mcpLog, "Collected Strimzi Drain Cleaner logs");
            return result;
        } catch (Exception e) {
            LOG.warnf("Failed to gather Drain Cleaner logs: %s", e.getMessage());
            failed.add(STEP_DRAIN_CLEANER_LOGS + ": " + e.getMessage());
            return null;
        }
    }

    // ---- Sampling: triage and analysis ----

    private InvestigationAreas decideInvestigationAreas(final Sampling sampling,
                                                        final KafkaClusterResponse cluster,
                                                        final List<KafkaNodePoolResponse> nodePools,
                                                        final KafkaClusterPodsResponse pods,
                                                        final DrainCleanerReadinessResponse drainCleaner,
                                                        final String symptom) {
        if (sampling == null || !sampling.isSupported()) {
            return InvestigationAreas.all();
        }

        try {
            Map<String, Object> summary = buildPhase1Summary(cluster, nodePools, pods, drainCleaner, symptom);
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

    @SuppressWarnings("checkstyle:ParameterNumber")
    private String produceAnalysis(final Sampling sampling,
                                   final KafkaClusterResponse cluster,
                                   final List<KafkaNodePoolResponse> nodePools,
                                   final KafkaClusterPodsResponse pods,
                                   final StrimziOperatorResponse operator,
                                   final StrimziOperatorLogsResponse operatorLogs,
                                   final KafkaClusterLogsResponse clusterLogs,
                                   final StrimziEventsResponse events,
                                   final List<KafkaUserResponse> users,
                                   final KafkaMetricsResponse metrics,
                                   final DrainCleanerReadinessResponse drainCleaner,
                                   final DrainCleanerLogsResponse drainCleanerLogs,
                                   final String symptom) {
        if (sampling == null || !sampling.isSupported()) {
            return null;
        }

        try {
            Map<String, Object> fullData = buildFullSummary(
                cluster, nodePools, pods, operator, operatorLogs,
                clusterLogs, events, users, metrics, drainCleaner, drainCleanerLogs, symptom);
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

    private LogCollectionParams buildErrorLogParams(final Integer sinceMinutes) {
        return LogCollectionParams.builder(defaultTailLines)
            .filter("errors")
            .sinceSeconds(sinceMinutes != null ? sinceMinutes * SECONDS_PER_MINUTE : null)
            .build();
    }

    private Map<String, Object> buildPhase1Summary(final KafkaClusterResponse cluster,
                                                   final List<KafkaNodePoolResponse> nodePools,
                                                   final KafkaClusterPodsResponse pods,
                                                   final DrainCleanerReadinessResponse drainCleaner,
                                                   final String symptom) {
        Map<String, Object> summary = new LinkedHashMap<>();
        if (symptom != null) {
            summary.put("symptom", symptom);
        }
        summary.put("cluster_readiness", cluster.readiness());
        if (cluster.replicas() != null) {
            summary.put("expected_replicas", cluster.replicas().expected());
            summary.put("ready_replicas", cluster.replicas().ready());
        }
        summary.put("node_pool_count", nodePools.size());
        if (pods != null) {
            summary.put("pods", pods);
        }
        if (drainCleaner != null) {
            summary.put("drain_cleaner_deployed", drainCleaner.deployed());
            summary.put("drain_cleaner_ready", drainCleaner.overallReady());
        }
        return summary;
    }

    @SuppressWarnings("checkstyle:ParameterNumber")
    private Map<String, Object> buildFullSummary(final KafkaClusterResponse cluster,
                                                 final List<KafkaNodePoolResponse> nodePools,
                                                 final KafkaClusterPodsResponse pods,
                                                 final StrimziOperatorResponse operator,
                                                 final StrimziOperatorLogsResponse operatorLogs,
                                                 final KafkaClusterLogsResponse clusterLogs,
                                                 final StrimziEventsResponse events,
                                                 final List<KafkaUserResponse> users,
                                                 final KafkaMetricsResponse metrics,
                                                 final DrainCleanerReadinessResponse drainCleaner,
                                                 final DrainCleanerLogsResponse drainCleanerLogs,
                                                 final String symptom) {
        Map<String, Object> data = new LinkedHashMap<>();
        if (symptom != null) {
            data.put("symptom", symptom);
        }
        DiagnosticHelper.putIfNotNull(data, STEP_CLUSTER_STATUS, cluster);
        DiagnosticHelper.putIfNotNull(data, STEP_NODE_POOLS, nodePools);
        DiagnosticHelper.putIfNotNull(data, STEP_POD_HEALTH, pods);
        DiagnosticHelper.putIfNotNull(data, STEP_OPERATOR_STATUS, operator);
        DiagnosticHelper.putIfNotNull(data, STEP_OPERATOR_LOGS, operatorLogs);
        DiagnosticHelper.putIfNotNull(data, STEP_CLUSTER_LOGS, clusterLogs);
        DiagnosticHelper.putIfNotNull(data, STEP_EVENTS, events);
        DiagnosticHelper.putIfNotNull(data, STEP_USERS, users);
        DiagnosticHelper.putIfNotNull(data, STEP_METRICS, metrics);
        DiagnosticHelper.putIfNotNull(data, STEP_DRAIN_CLEANER, drainCleaner);
        DiagnosticHelper.putIfNotNull(data, STEP_DRAIN_CLEANER_LOGS, drainCleanerLogs);
        return data;
    }

    private InvestigationAreas parseInvestigationAreas(final SamplingResponse response) {
        try {
            String text = DiagnosticHelper.extractSamplingText(response);
            Map<String, Object> parsed = objectMapper.readValue(text, DiagnosticHelper.MAP_TYPE_REF);
            return new InvestigationAreas(
                Boolean.TRUE.equals(parsed.get(STEP_OPERATOR_LOGS)),
                Boolean.TRUE.equals(parsed.get(STEP_CLUSTER_LOGS)),
                Boolean.TRUE.equals(parsed.get(STEP_EVENTS)),
                Boolean.TRUE.equals(parsed.getOrDefault(STEP_USERS, Boolean.TRUE)),
                Boolean.TRUE.equals(parsed.get(STEP_METRICS)),
                Boolean.TRUE.equals(parsed.get(STEP_DRAIN_CLEANER_LOGS))
            );
        } catch (Exception e) {
            LOG.debugf("Could not parse triage response, investigating all: %s", e.getMessage());
            return InvestigationAreas.all();
        }
    }

    /**
     * Flags indicating which investigation areas the LLM recommended.
     *
     * @param operatorLogs     whether to gather operator logs
     * @param clusterLogs      whether to gather cluster logs
     * @param events           whether to gather Kubernetes events
     * @param users        whether to gather KafkaUser status
     * @param metrics          whether to gather replication metrics
     * @param drainCleanerLogs whether to gather drain cleaner logs
     */
    record InvestigationAreas(boolean operatorLogs, boolean clusterLogs,
                              boolean events, boolean users, boolean metrics,
                              boolean drainCleanerLogs) {

        /**
         * Returns areas with all flags set to true (fallback when Sampling is unavailable).
         *
         * @return investigation areas with all flags enabled
         */
        static InvestigationAreas all() {
            return new InvestigationAreas(true, true, true, true, true, true);
        }

        /**
         * Count how many investigation areas are enabled.
         * Used to calculate the total number of diagnostic steps for progress reporting.
         *
         * @return the number of enabled investigation areas
         */
        private int enabledCount() {
            int c = 0;
            if (operatorLogs) c++;
            if (clusterLogs) c++;
            if (events) c++;
            if (users) c++;
            if (metrics) c++;
            if (drainCleanerLogs) c++;
            return c;
        }
    }

    // ---- Sampling system prompts ----

    static final String TRIAGE_SYSTEM_PROMPT = """
        You are a Kafka infrastructure diagnostics assistant. \
        Analyze the initial cluster findings and decide which areas need deeper investigation. \
        Return ONLY a JSON object with these boolean fields: \
        operator_logs, cluster_logs, events, metrics, drain_cleaner_logs. \
        Set true only for areas likely to reveal the root cause. \
        For example, if all pods are healthy and the cluster is Ready, set all to false. \
        If pods are crashing, set cluster_logs and events to true. \
        If the cluster is NotReady but pods look fine, set operator_logs and metrics to true. \
        If symptom mentions node drain, rolling update, or pod eviction, \
        or if drain_cleaner_deployed is false, set drain_cleaner_logs to true.\
        """;

    static final String ANALYSIS_SYSTEM_PROMPT = """
        You are diagnosing a Kafka cluster issue. Analyze all the gathered diagnostic data \
        and produce a concise root cause analysis.
        
        Distinguish between:
        1. Operator-initiated changes (EXPECTED): rolling updates, certificate renewal, config changes
        2. Infrastructure failures (CRITICAL): OOM, disk full, node failures, network issues
        3. Configuration errors (HIGH): invalid specs, missing secrets, RBAC issues
        4. Performance degradation (MEDIUM): overloaded brokers, replication lag
        5. Cascading failures (CRITICAL): broker overload -> replication lag -> offline partitions
        6. Drain cleaner issues (HIGH): missing deployment, webhook unconfigured, \
        legacy mode, TLS certificate expiring \
        Action: deploy drain cleaner, verify webhook, switch to standard mode
        
        Structure your response as:
        - Root cause (single most likely cause)
        - Severity (CRITICAL/HIGH/MEDIUM/LOW)
        - Impact (what is affected)
        - Evidence (specific findings that support the diagnosis)
        - Recommendations (prioritized, actionable steps)
        
        Remember: symptoms are not root causes. Pod restarts are symptoms; \
        OOM or disk full are root causes.\
        """;
}
