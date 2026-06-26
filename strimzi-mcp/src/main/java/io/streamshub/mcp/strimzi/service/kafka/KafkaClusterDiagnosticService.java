/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.service.kafka;

import io.opentelemetry.instrumentation.annotations.WithSpan;
import io.quarkiverse.mcp.server.Cancellation;
import io.quarkiverse.mcp.server.Elicitation;
import io.quarkiverse.mcp.server.McpLog;
import io.quarkiverse.mcp.server.Progress;
import io.quarkiverse.mcp.server.Sampling;
import io.quarkiverse.mcp.server.ToolCallException;
import io.streamshub.mcp.common.config.KubernetesConstants;
import io.streamshub.mcp.common.dto.LogCollectionParams;
import io.streamshub.mcp.common.dto.PodSummaryResponse;
import io.streamshub.mcp.common.service.BaseDiagnosticService;
import io.streamshub.mcp.common.service.DiagnosticHelper;
import io.streamshub.mcp.common.util.InputUtils;
import io.streamshub.mcp.common.util.NamespaceElicitationHelper;
import io.streamshub.mcp.strimzi.config.StrimziConstants;
import io.streamshub.mcp.strimzi.dto.draincleaner.DrainCleanerLogsResponse;
import io.streamshub.mcp.strimzi.dto.draincleaner.DrainCleanerReadinessResponse;
import io.streamshub.mcp.strimzi.dto.kafka.KafkaClusterDiagnosticReport;
import io.streamshub.mcp.strimzi.dto.kafka.KafkaClusterLogsResponse;
import io.streamshub.mcp.strimzi.dto.kafka.KafkaClusterPodsResponse;
import io.streamshub.mcp.strimzi.dto.kafka.KafkaClusterResponse;
import io.streamshub.mcp.strimzi.dto.kafkanodepool.KafkaNodePoolResponse;
import io.streamshub.mcp.strimzi.dto.kafkauser.KafkaUserResponse;
import io.streamshub.mcp.strimzi.dto.metrics.KafkaMetricsResponse;
import io.streamshub.mcp.strimzi.dto.operator.StrimziEventsResponse;
import io.streamshub.mcp.strimzi.dto.operator.StrimziOperatorLogsResponse;
import io.streamshub.mcp.strimzi.dto.operator.StrimziOperatorResponse;
import io.streamshub.mcp.strimzi.service.draincleaner.DrainCleanerService;
import io.streamshub.mcp.strimzi.service.kafkanodepool.KafkaNodePoolService;
import io.streamshub.mcp.strimzi.service.kafkauser.KafkaUserService;
import io.streamshub.mcp.strimzi.service.metrics.KafkaMetricsService;
import io.streamshub.mcp.strimzi.service.operator.StrimziEventsService;
import io.streamshub.mcp.strimzi.service.operator.StrimziOperatorService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
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
public class KafkaClusterDiagnosticService extends BaseDiagnosticService {

    private static final Logger LOG = Logger.getLogger(KafkaClusterDiagnosticService.class);
    private static final int SECONDS_PER_MINUTE = 60;
    private static final int PHASE1_STEPS = 4;
    private static final int MAX_PHASE2_STEPS = 6;
    private static final int DEFAULT_TIME_WINDOW_MINUTES = 30;
    private static final int ESCALATED_TIME_WINDOW_MINUTES = 60;
    private static final int ABSOLUTE_WINDOW_EXPANSION_MINUTES = 30;
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

    @ConfigProperty(name = "mcp.diagnostic.restart-threshold", defaultValue = "3")
    int restartThreshold;

    @Override
    protected Logger getLogger() {
        return LOG;
    }

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
        LogCollectionParams logParams = resolveLogParams(sinceMinutes, areas, mcpLog);
        Integer effectiveSinceMinutes = resolveEffectiveSinceMinutes(sinceMinutes, areas);

        StrimziOperatorResponse operator = null;
        StrimziOperatorLogsResponse operatorLogs = null;
        if (areas.operatorLogs) {
            operator = gatherOperatorStatus(null, completed, failed, mcpLog);
            operatorLogs = gatherOperatorLogs(
                null, effectiveSinceMinutes, completed, failed, mcpLog);
            DiagnosticHelper.sendProgress(progress, ++stepIndex, totalSteps, DIAGNOSTIC_LABEL);
            DiagnosticHelper.checkCancellation(cancellation);
        }

        KafkaClusterLogsResponse clusterLogs = null;
        if (areas.clusterLogs) {
            clusterLogs = gatherClusterLogs(
                resolvedNs, name, logParams, pods, elicitation,
                completed, failed, mcpLog);
            DiagnosticHelper.sendProgress(progress, ++stepIndex, totalSteps, DIAGNOSTIC_LABEL);
            DiagnosticHelper.checkCancellation(cancellation);
        }

        StrimziEventsResponse events = null;
        if (areas.events) {
            events = gatherEvents(
                resolvedNs, name, effectiveSinceMinutes, completed, failed, mcpLog);
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
                resolvedNs, name, logParams, completed, failed, mcpLog);
            DiagnosticHelper.sendProgress(progress, ++stepIndex, totalSteps, DIAGNOSTIC_LABEL);
            DiagnosticHelper.checkCancellation(cancellation);
        }

        DrainCleanerLogsResponse drainCleanerLogs = null;
        if (areas.drainCleanerLogs) {
            drainCleanerLogs = gatherDrainCleanerLogs(
                effectiveSinceMinutes, completed, failed, mcpLog);
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

    @WithSpan("diagnose.cluster.status")
    KafkaClusterResponse gatherClusterStatus(final String namespace,
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
                    && elicitation != null && elicitation.isFormModeSupported()) {
                String resolved = NamespaceElicitationHelper.elicitNamespace(e, elicitation, "diagnosed");
                return gatherClusterStatus(resolved, clusterName, null,
                    completed, mcpLog);
            }
            throw e;
        }
    }

    @WithSpan("diagnose.cluster.node_pools")
    List<KafkaNodePoolResponse> gatherNodePools(final String namespace,
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

    @WithSpan("diagnose.cluster.pods")
    KafkaClusterPodsResponse gatherClusterPods(final String namespace,
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

    @WithSpan("diagnose.cluster.operator_status")
    StrimziOperatorResponse gatherOperatorStatus(final String namespace,
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

    @WithSpan("diagnose.cluster.operator_logs")
    StrimziOperatorLogsResponse gatherOperatorLogs(final String namespace,
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

    @WithSpan("diagnose.cluster.logs")
    @SuppressWarnings("checkstyle:ParameterNumber")
    KafkaClusterLogsResponse gatherClusterLogs(final String namespace,
                                               final String clusterName,
                                               final LogCollectionParams logParams,
                                               final KafkaClusterPodsResponse pods,
                                               final Elicitation elicitation,
                                               final List<String> completed,
                                               final List<String> failed,
                                               final McpLog mcpLog) {
        try {
            Set<String> problematicPods = identifyProblematicPods(pods);

            KafkaClusterLogsResponse result = collectClusterLogs(
                namespace, clusterName, logParams, problematicPods, pods, mcpLog);

            // Escalation: if no errors found, try a broader time window
            if (result != null && !result.hasErrors()) {
                result = escalateLogCollection(
                    namespace, clusterName, logParams, problematicPods, pods,
                    elicitation, mcpLog);
            }

            completed.add(STEP_CLUSTER_LOGS);
            return result;
        } catch (Exception e) {
            LOG.warnf("Failed to gather Kafka cluster logs: %s", e.getMessage());
            failed.add(STEP_CLUSTER_LOGS + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * Identify pods that need log investigation based on health indicators.
     * A pod is considered problematic if it is not Running, not ready,
     * or has a high restart count.
     *
     * @param pods the pod health data from Phase 1
     * @return set of problematic pod names, empty if all pods are healthy
     */
    Set<String> identifyProblematicPods(final KafkaClusterPodsResponse pods) {
        if (pods == null || pods.podSummary() == null || pods.podSummary().pods() == null) {
            return Set.of();
        }
        return pods.podSummary().pods().stream()
            .filter(pod -> !KubernetesConstants.PodPhases.RUNNING.equals(pod.phase())
                || !pod.ready()
                || pod.restarts() > restartThreshold)
            .map(PodSummaryResponse.PodInfo::name)
            .collect(Collectors.toSet());
    }

    @WithSpan("diagnose.cluster.events")
    StrimziEventsResponse gatherEvents(final String namespace,
                                       final String clusterName,
                                       final Integer sinceMinutes,
                                       final List<String> completed,
                                       final List<String> failed,
                                       final McpLog mcpLog) {
        try {
            StrimziEventsResponse result = eventsService.getEvents(
                namespace, clusterName, StrimziConstants.KindValues.KAFKA, sinceMinutes);
            completed.add(STEP_EVENTS);
            DiagnosticHelper.sendClientNotification(mcpLog, String.format("Found %d events", result.totalEvents()));
            return result;
        } catch (Exception e) {
            LOG.warnf("Failed to gather Kafka related events: %s", e.getMessage());
            failed.add(STEP_EVENTS + ": " + e.getMessage());
            return null;
        }
    }

    @WithSpan("diagnose.cluster.metrics")
    KafkaMetricsResponse gatherMetrics(final String namespace,
                                       final String clusterName,
                                       final LogCollectionParams timeWindow,
                                       final List<String> completed,
                                       final List<String> failed,
                                       final McpLog mcpLog) {
        try {
            Integer rangeMinutes = timeWindow.sinceSeconds() != null
                ? timeWindow.sinceSeconds() / SECONDS_PER_MINUTE : null;
            KafkaMetricsResponse result = kafkaMetricsService.getKafkaMetrics(
                namespace, clusterName, "replication", null,
                rangeMinutes, timeWindow.startTime(), timeWindow.endTime(),
                null, null, null);
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

    @WithSpan("diagnose.cluster.triage")
    InvestigationAreas decideInvestigationAreas(final Sampling sampling,
                                                final KafkaClusterResponse cluster,
                                                final List<KafkaNodePoolResponse> nodePools,
                                                final KafkaClusterPodsResponse pods,
                                                final DrainCleanerReadinessResponse drainCleaner,
                                                        final String symptom) {
        Map<String, Object> parsed = performTriage(sampling, TRIAGE_SYSTEM_PROMPT,
            buildPhase1Summary(cluster, nodePools, pods, drainCleaner, symptom));
        return parsed != null ? parseInvestigationAreas(parsed) : InvestigationAreas.all();
    }

    @WithSpan("diagnose.cluster.analysis")
    @SuppressWarnings("checkstyle:ParameterNumber")
    String produceAnalysis(final Sampling sampling,
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
        return performAnalysis(sampling, ANALYSIS_SYSTEM_PROMPT,
            buildFullSummary(cluster, nodePools, pods, operator, operatorLogs,
                clusterLogs, events, users, metrics, drainCleaner, drainCleanerLogs, symptom));
    }

    // ---- Helpers ----

    private LogCollectionParams buildErrorLogParams(final Integer sinceMinutes) {
        return LogCollectionParams.builder(defaultTailLines)
            .filter("errors")
            .sinceSeconds(sinceMinutes != null ? sinceMinutes * SECONDS_PER_MINUTE : null)
            .build();
    }

    /**
     * Resolve log collection params from the user-provided sinceMinutes and triage-recommended window.
     * Priority: user sinceMinutes > triage absolute window > triage relative window > default 30 min.
     */
    LogCollectionParams resolveLogParams(final Integer userSinceMinutes,
                                         final InvestigationAreas areas,
                                         final McpLog mcpLog) {
        if (userSinceMinutes != null) {
            return buildErrorLogParams(userSinceMinutes);
        }
        if (areas.hasAbsoluteWindow()) {
            DiagnosticHelper.sendClientNotification(mcpLog,
                String.format("Using triage-recommended time window: %s to %s",
                    areas.windowStartTime(), areas.windowEndTime()));
            return LogCollectionParams.builder(defaultTailLines)
                .filter("errors")
                .startTime(areas.windowStartTime())
                .endTime(areas.windowEndTime())
                .build();
        }
        int window = areas.hasRelativeWindow()
            ? areas.timeWindowMinutes() : DEFAULT_TIME_WINDOW_MINUTES;
        DiagnosticHelper.sendClientNotification(mcpLog,
            String.format("Using time window: last %d minutes", window));
        return buildErrorLogParams(window);
    }

    /**
     * Resolve the effective sinceMinutes for events and operator logs.
     * Returns the user value, triage relative value, or default 30 min.
     * For absolute windows, returns null (events use sinceMinutes, not start/end).
     */
    Integer resolveEffectiveSinceMinutes(final Integer userSinceMinutes,
                                          final InvestigationAreas areas) {
        if (userSinceMinutes != null) {
            return userSinceMinutes;
        }
        if (areas.hasAbsoluteWindow()) {
            return null;
        }
        return areas.hasRelativeWindow()
            ? areas.timeWindowMinutes() : DEFAULT_TIME_WINDOW_MINUTES;
    }

    private KafkaClusterLogsResponse collectClusterLogs(final String namespace,
                                                         final String clusterName,
                                                         final LogCollectionParams logParams,
                                                         final Set<String> problematicPods,
                                                         final KafkaClusterPodsResponse pods,
                                                         final McpLog mcpLog) {
        if (problematicPods.isEmpty()) {
            DiagnosticHelper.sendClientNotification(mcpLog,
                "Collecting Kafka cluster logs (all pods healthy, collecting from all)");
            return kafkaService.getClusterLogs(namespace, clusterName, logParams);
        }
        int totalPods = pods != null && pods.podSummary() != null
            ? pods.podSummary().totalPods() : 0;
        DiagnosticHelper.sendClientNotification(mcpLog,
            String.format("Collecting logs from %d problematic pods (out of %d total): %s",
                problematicPods.size(), totalPods, problematicPods));
        return kafkaService.getClusterLogs(namespace, clusterName, logParams, problematicPods);
    }

    @SuppressWarnings("checkstyle:ParameterNumber")
    private KafkaClusterLogsResponse escalateLogCollection(final String namespace,
                                                            final String clusterName,
                                                            final LogCollectionParams originalParams,
                                                            final Set<String> problematicPods,
                                                            final KafkaClusterPodsResponse pods,
                                                            final Elicitation elicitation,
                                                            final McpLog mcpLog) {
        // Auto-escalate once: expand the time window
        LogCollectionParams expandedParams = expandTimeWindow(originalParams);
        if (expandedParams == null) {
            return collectClusterLogs(namespace, clusterName, originalParams,
                problematicPods, pods, mcpLog);
        }

        DiagnosticHelper.sendClientNotification(mcpLog,
            "No errors found in initial time window, expanding and re-collecting");
        KafkaClusterLogsResponse result = collectClusterLogs(
            namespace, clusterName, expandedParams, problematicPods, pods, mcpLog);

        if (result != null && !result.hasErrors()) {
            // Still no errors — ask the user if they want to expand further
            result = elicitFurtherExpansion(namespace, clusterName, expandedParams,
                problematicPods, pods, elicitation, mcpLog, result);
        }
        return result;
    }

    @SuppressWarnings("checkstyle:ParameterNumber")
    private KafkaClusterLogsResponse elicitFurtherExpansion(final String namespace,
                                                            final String clusterName,
                                                            final LogCollectionParams currentParams,
                                                            final Set<String> problematicPods,
                                                            final KafkaClusterPodsResponse pods,
                                                            final Elicitation elicitation,
                                                            final McpLog mcpLog,
                                                            final KafkaClusterLogsResponse currentResult) {
        if (elicitation == null || !elicitation.isFormModeSupported()) {
            return currentResult;
        }

        String selected = DiagnosticHelper.elicitSelection(
            elicitation,
            "No errors found in the expanded time window. Would you like to expand the search window further?",
            "time_expansion",
            "Select how much to expand the time window",
            List.of("Expand to 2 hours on each side",
                     "Expand to 4 hours on each side",
                     "Accept current results"));

        if (selected == null || selected.contains("Accept")) {
            return currentResult;
        }

        int expansionMinutes = selected.contains("4 hours") ? 240 : 120;
        LogCollectionParams widerParams = expandTimeWindowBy(currentParams, expansionMinutes);
        if (widerParams == null) {
            return currentResult;
        }

        DiagnosticHelper.sendClientNotification(mcpLog,
            String.format("Expanding time window by %d minutes on each side", expansionMinutes));
        return collectClusterLogs(namespace, clusterName, widerParams, problematicPods, pods, mcpLog);
    }

    /**
     * Auto-expand the time window for the first escalation attempt.
     * Relative windows: double (e.g. 30 → 60 min, capped at 120).
     * Absolute windows: expand by 30 min on each side.
     */
    LogCollectionParams expandTimeWindow(final LogCollectionParams params) {
        if (params.startTime() != null && params.endTime() != null) {
            return expandAbsoluteWindow(params, ABSOLUTE_WINDOW_EXPANSION_MINUTES);
        }
        if (params.sinceSeconds() != null) {
            int currentMinutes = params.sinceSeconds() / SECONDS_PER_MINUTE;
            int expanded = Math.min(currentMinutes * 2, ESCALATED_TIME_WINDOW_MINUTES);
            if (expanded <= currentMinutes) {
                return null;
            }
            return LogCollectionParams.builder(params.tailLines())
                .filter(params.filter())
                .sinceSeconds(expanded * SECONDS_PER_MINUTE)
                .build();
        }
        return buildErrorLogParams(ESCALATED_TIME_WINDOW_MINUTES);
    }

    /**
     * Expand the time window by a user-selected amount (for elicitation escalation).
     */
    private LogCollectionParams expandTimeWindowBy(final LogCollectionParams params,
                                                    final int expansionMinutes) {
        if (params.startTime() != null && params.endTime() != null) {
            return expandAbsoluteWindow(params, expansionMinutes);
        }
        if (params.sinceSeconds() != null) {
            int currentMinutes = params.sinceSeconds() / SECONDS_PER_MINUTE;
            return LogCollectionParams.builder(params.tailLines())
                .filter(params.filter())
                .sinceSeconds((currentMinutes + expansionMinutes) * SECONDS_PER_MINUTE)
                .build();
        }
        return LogCollectionParams.builder(params.tailLines())
            .filter(params.filter())
            .sinceSeconds(expansionMinutes * SECONDS_PER_MINUTE)
            .build();
    }

    private LogCollectionParams expandAbsoluteWindow(final LogCollectionParams params,
                                                      final int expansionMinutes) {
        try {
            Instant start = Instant.parse(params.startTime());
            Instant end = Instant.parse(params.endTime());
            String newStart = start.minusSeconds((long) expansionMinutes * SECONDS_PER_MINUTE).toString();
            String newEnd = end.plusSeconds((long) expansionMinutes * SECONDS_PER_MINUTE).toString();
            return LogCollectionParams.builder(params.tailLines())
                .filter(params.filter())
                .startTime(newStart)
                .endTime(newEnd)
                .build();
        } catch (Exception e) {
            LOG.warnf("Could not expand absolute time window: %s", e.getMessage());
            return null;
        }
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
        if (cluster.brokerReplicas() != null) {
            summary.put("expected_broker_replicas", cluster.brokerReplicas().expected());
            summary.put("ready_broker_replicas", cluster.brokerReplicas().ready());
        }
        if (cluster.controllerReplicas() != null) {
            summary.put("expected_controller_replicas", cluster.controllerReplicas().expected());
            summary.put("ready_controller_replicas", cluster.controllerReplicas().ready());
        }
        summary.put("node_pool_count", nodePools.size());
        if (pods != null) {
            summary.put("pods", pods);
        }
        if (drainCleaner != null) {
            summary.put("drain_cleaner_deployed", drainCleaner.deployed());
            summary.put("drain_cleaner_ready", drainCleaner.overallReady());
        }
        summary.put("current_time_utc", Instant.now().toString());
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

    private InvestigationAreas parseInvestigationAreas(final Map<String, Object> parsed) {
        Integer timeWindow = parsed.get("time_window_minutes") instanceof Number n
            ? n.intValue() : null;
        String startTime = parsed.get("window_start_time") instanceof String s ? s : null;
        String endTime = parsed.get("window_end_time") instanceof String s ? s : null;

        return new InvestigationAreas(
            Boolean.TRUE.equals(parsed.get(STEP_OPERATOR_LOGS)),
            Boolean.TRUE.equals(parsed.get(STEP_CLUSTER_LOGS)),
            Boolean.TRUE.equals(parsed.get(STEP_EVENTS)),
            Boolean.TRUE.equals(parsed.getOrDefault(STEP_USERS, Boolean.TRUE)),
            Boolean.TRUE.equals(parsed.get(STEP_METRICS)),
            Boolean.TRUE.equals(parsed.get(STEP_DRAIN_CLEANER_LOGS)),
            timeWindow, startTime, endTime
        );
    }

    /**
     * Flags indicating which investigation areas the LLM recommended,
     * plus an optional time window for log and event collection.
     *
     * @param operatorLogs     whether to gather operator logs
     * @param clusterLogs      whether to gather cluster logs
     * @param events           whether to gather Kubernetes events
     * @param users            whether to gather KafkaUser status
     * @param metrics          whether to gather replication metrics
     * @param drainCleanerLogs whether to gather drain cleaner logs
     * @param timeWindowMinutes relative time window in minutes (e.g. 30 = last 30 min)
     * @param windowStartTime  absolute start time in ISO 8601 for past incidents
     * @param windowEndTime    absolute end time in ISO 8601 for past incidents
     */
    record InvestigationAreas(boolean operatorLogs, boolean clusterLogs,
                              boolean events, boolean users, boolean metrics,
                              boolean drainCleanerLogs,
                              Integer timeWindowMinutes,
                              String windowStartTime,
                              String windowEndTime) {

        static InvestigationAreas all() {
            return new InvestigationAreas(true, true, true, true, true, true,
                null, null, null);
        }

        boolean hasAbsoluteWindow() {
            return windowStartTime != null && windowEndTime != null;
        }

        boolean hasRelativeWindow() {
            return timeWindowMinutes != null;
        }

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
        or if drain_cleaner_deployed is false, set drain_cleaner_logs to true. \
        Also recommend a time window for log and event collection: \
        For current or active issues, return "time_window_minutes" (integer, e.g. 30). \
        For past incidents where the symptom mentions a specific time, return \
        "window_start_time" and "window_end_time" in ISO 8601 format \
        (e.g. "2024-01-15T01:30:00Z"). Center the window around the incident time \
        with 30 minutes buffer on each side. \
        The current UTC time is provided in the data as current_time_utc for reference. \
        Default to time_window_minutes: 30 if unsure.\
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
