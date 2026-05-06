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
import io.streamshub.mcp.common.service.DiagnosticHelper;
import io.streamshub.mcp.common.util.InputUtils;
import io.streamshub.mcp.common.util.NamespaceElicitationHelper;
import io.streamshub.mcp.strimzi.dto.DrainCleanerReadinessResponse;
import io.streamshub.mcp.strimzi.dto.KafkaCertificateResponse;
import io.streamshub.mcp.strimzi.dto.KafkaClusterPodsResponse;
import io.streamshub.mcp.strimzi.dto.KafkaClusterResponse;
import io.streamshub.mcp.strimzi.dto.KafkaNodePoolResponse;
import io.streamshub.mcp.strimzi.dto.StrimziEventsResponse;
import io.streamshub.mcp.strimzi.dto.StrimziOperatorResponse;
import io.streamshub.mcp.strimzi.dto.UpgradeReadinessReport;
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
 * Orchestrates a multistep upgrade readiness diagnostic workflow for Kafka clusters.
 *
 * <p>Gathers data from the Kafka cluster, Strimzi operator, node pools, pods,
 * metrics, drain cleaner, certificates, and events. Optionally uses MCP Sampling
 * for LLM triage and GO/NO-GO analysis.</p>
 *
 * <p>Individual step failures do not abort the workflow. Failed steps are
 * recorded and the report includes all data that was successfully gathered.</p>
 */
@ApplicationScoped
public class UpgradeReadinessDiagnosticService {

    private static final Logger LOG = Logger.getLogger(UpgradeReadinessDiagnosticService.class);
    private static final int PHASE1_STEPS = 4;
    private static final int MAX_PHASE2_STEPS = 5;
    private static final String DIAGNOSTIC_LABEL = "Upgrade readiness check";

    private static final String STEP_CLUSTER_STATUS = "cluster_status";
    private static final String STEP_OPERATOR_STATUS = "operator_status";
    private static final String STEP_NODE_POOLS_AND_PODS = "node_pools_and_pods";
    private static final String STEP_REPLICATION_METRICS = "replication_metrics";
    private static final String STEP_PERFORMANCE_METRICS = "performance_metrics";
    private static final String STEP_RESOURCE_METRICS = "resource_metrics";
    private static final String STEP_DRAIN_CLEANER = "drain_cleaner";
    private static final String STEP_CERTIFICATES = "certificates";
    private static final String STEP_EVENTS = "events";

    @Inject
    KafkaService kafkaService;

    @Inject
    KafkaNodePoolService nodePoolService;

    @Inject
    StrimziOperatorService operatorService;

    @Inject
    StrimziEventsService eventsService;

    @Inject
    DrainCleanerService drainCleanerService;

    @Inject
    KafkaCertificateService certificateService;

    @Inject
    KafkaMetricsService metricsService;

    @Inject
    ObjectMapper objectMapper;

    @ConfigProperty(name = "mcp.sampling.triage-max-tokens", defaultValue = "200")
    int triageMaxTokens;

    @ConfigProperty(name = "mcp.sampling.analysis-max-tokens", defaultValue = "1500")
    int analysisMaxTokens;

    UpgradeReadinessDiagnosticService() {
    }

    /**
     * Run a multistep upgrade readiness diagnostic workflow for a Kafka cluster.
     *
     * <p>Phase 1 gathers cluster status, operator status, node pools with pods, and
     * replication metrics. Phase 2 uses Sampling (if supported) to decide which areas
     * need deeper investigation, then gathers performance metrics, resource metrics,
     * drain cleaner status, certificates, and events. Phase 3 uses Sampling to produce
     * a GO/NO-GO upgrade verdict.</p>
     *
     * @param namespace     optional namespace (elicited if ambiguous)
     * @param clusterName   the Kafka cluster name
     * @param targetVersion optional target Kafka version for context
     * @param sampling      MCP Sampling for LLM analysis (may be unsupported)
     * @param elicitation   MCP Elicitation for user input (may be unsupported)
     * @param mcpLog        MCP log for progress notifications
     * @param progress      MCP progress tracking
     * @param cancellation  MCP cancellation checking
     * @return a consolidated upgrade readiness report
     */
    @SuppressWarnings("checkstyle:ParameterNumber")
    public UpgradeReadinessReport diagnose(final String namespace,
                                            final String clusterName,
                                            final String targetVersion,
                                            final Sampling sampling,
                                            final Elicitation elicitation,
                                            final McpLog mcpLog,
                                            final Progress progress,
                                            final Cancellation cancellation) {
        String ns = InputUtils.normalizeInput(namespace);
        String name = InputUtils.normalizeInput(clusterName);
        String version = InputUtils.normalizeInput(targetVersion);

        if (name == null) {
            throw new ToolCallException("Cluster name is required");
        }

        LOG.infof("Starting upgrade readiness check for cluster=%s (namespace=%s, targetVersion=%s)",
            name, ns != null ? ns : "auto", version != null ? version : "not specified");

        List<String> completed = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        int stepIndex = 0;

        // === Phase 1: Initial data gathering ===
        int maxSteps = PHASE1_STEPS + MAX_PHASE2_STEPS;
        KafkaClusterResponse cluster = gatherClusterStatus(
            ns, name, elicitation, completed, mcpLog);
        DiagnosticHelper.sendProgress(progress, ++stepIndex, maxSteps, DIAGNOSTIC_LABEL);
        DiagnosticHelper.checkCancellation(cancellation);

        String resolvedNs = cluster.namespace();

        StrimziOperatorResponse operator = gatherOperatorStatus(
            resolvedNs, completed, failed, mcpLog);
        DiagnosticHelper.sendProgress(progress, ++stepIndex, maxSteps, DIAGNOSTIC_LABEL);
        DiagnosticHelper.checkCancellation(cancellation);

        List<KafkaNodePoolResponse> nodePools = gatherNodePoolsData(
            resolvedNs, name, mcpLog);
        KafkaClusterPodsResponse pods = gatherPodsData(
            resolvedNs, name, mcpLog);
        recordNodePoolsAndPodsStep(nodePools, pods, completed, failed, mcpLog);
        DiagnosticHelper.sendProgress(progress, ++stepIndex, maxSteps, DIAGNOSTIC_LABEL);
        DiagnosticHelper.checkCancellation(cancellation);

        KafkaMetricsResponse replicationMetrics = gatherReplicationMetrics(
            resolvedNs, name, completed, failed, mcpLog);
        DiagnosticHelper.sendProgress(progress, ++stepIndex, maxSteps, DIAGNOSTIC_LABEL);
        DiagnosticHelper.checkCancellation(cancellation);

        // === Phase 2: Deep investigation ===
        InvestigationAreas areas = investigateAreas(
            sampling, cluster, operator, nodePools, pods,
            replicationMetrics, version);

        int totalSteps = PHASE1_STEPS + areas.enabledCount();

        KafkaMetricsResponse performanceMetrics = null;
        if (areas.performanceMetrics) {
            performanceMetrics = gatherPerformanceMetrics(
                resolvedNs, name, completed, failed, mcpLog);
            DiagnosticHelper.sendProgress(progress, ++stepIndex, totalSteps, DIAGNOSTIC_LABEL);
            DiagnosticHelper.checkCancellation(cancellation);
        }

        KafkaMetricsResponse resourceMetrics = null;
        if (areas.resourceMetrics) {
            resourceMetrics = gatherResourceMetrics(
                resolvedNs, name, completed, failed, mcpLog);
            DiagnosticHelper.sendProgress(progress, ++stepIndex, totalSteps, DIAGNOSTIC_LABEL);
            DiagnosticHelper.checkCancellation(cancellation);
        }

        DrainCleanerReadinessResponse drainCleaner = null;
        if (areas.drainCleaner) {
            drainCleaner = gatherDrainCleaner(
                resolvedNs, completed, failed, mcpLog);
            DiagnosticHelper.sendProgress(progress, ++stepIndex, totalSteps, DIAGNOSTIC_LABEL);
            DiagnosticHelper.checkCancellation(cancellation);
        }

        KafkaCertificateResponse certificates = null;
        if (areas.certificates) {
            certificates = gatherCertificates(
                resolvedNs, name, completed, failed, mcpLog);
            DiagnosticHelper.sendProgress(progress, ++stepIndex, totalSteps, DIAGNOSTIC_LABEL);
            DiagnosticHelper.checkCancellation(cancellation);
        }

        StrimziEventsResponse events = null;
        if (areas.events) {
            events = gatherEvents(
                resolvedNs, name, completed, failed, mcpLog);
            DiagnosticHelper.sendProgress(progress, ++stepIndex, totalSteps, DIAGNOSTIC_LABEL);
            DiagnosticHelper.checkCancellation(cancellation);
        }

        // === Phase 3: Final analysis ===
        String analysis = produceAnalysis(sampling, cluster, operator,
            nodePools, pods, replicationMetrics,
            performanceMetrics, resourceMetrics, drainCleaner,
            certificates, events, version);

        return UpgradeReadinessReport.of(cluster, operator, nodePools, pods,
            replicationMetrics, performanceMetrics, resourceMetrics,
            drainCleaner, certificates, events, analysis,
            completed, failed.isEmpty() ? null : failed);
    }

    // ---- Phase 1: Initial data gathering ----

    @WithSpan("diagnose.upgrade.cluster_status")
    KafkaClusterResponse gatherClusterStatus(final String namespace,
                                              final String clusterName,
                                              final Elicitation elicitation,
                                              final List<String> completed,
                                              final McpLog mcpLog) {
        try {
            KafkaClusterResponse result = kafkaService.getCluster(namespace, clusterName);
            completed.add(STEP_CLUSTER_STATUS);
            DiagnosticHelper.sendClientNotification(mcpLog,
                "Checked Kafka cluster status: " + result.readiness()
                    + " (version: " + result.kafkaVersion() + ")");
            return result;
        } catch (ToolCallException e) {
            if (NamespaceElicitationHelper.isMultipleNamespacesError(e)
                    && elicitation != null && elicitation.isSupported()) {
                String resolved = NamespaceElicitationHelper.elicitNamespace(
                    e, elicitation, "checked for upgrade readiness");
                return gatherClusterStatus(resolved, clusterName, null, completed, mcpLog);
            }
            throw e;
        }
    }

    @WithSpan("diagnose.upgrade.operator_status")
    StrimziOperatorResponse gatherOperatorStatus(final String namespace,
                                                  final List<String> completed,
                                                  final List<String> failed,
                                                  final McpLog mcpLog) {
        try {
            List<StrimziOperatorResponse> operators = operatorService.listOperators(namespace);
            if (operators.isEmpty()) {
                failed.add(STEP_OPERATOR_STATUS + ": no Strimzi operator found");
                DiagnosticHelper.sendClientNotification(mcpLog,
                    "No Strimzi operator found in namespace");
                return null;
            }
            StrimziOperatorResponse result = operators.getFirst();
            completed.add(STEP_OPERATOR_STATUS);
            DiagnosticHelper.sendClientNotification(mcpLog,
                "Checked Strimzi operator '" + result.name() + "' status: " + result.status()
                    + " (version: " + result.version() + ")");
            return result;
        } catch (Exception e) {
            LOG.warnf("Failed to gather Strimzi operator status: %s", e.getMessage());
            failed.add(STEP_OPERATOR_STATUS + ": " + e.getMessage());
            return null;
        }
    }

    @WithSpan("diagnose.upgrade.node_pools")
    List<KafkaNodePoolResponse> gatherNodePoolsData(final String namespace,
                                                     final String clusterName,
                                                     final McpLog mcpLog) {
        try {
            return nodePoolService.listNodePools(namespace, clusterName);
        } catch (Exception e) {
            LOG.warnf("Failed to gather node pools: %s", e.getMessage());
            return null;
        }
    }

    @WithSpan("diagnose.upgrade.cluster_pods")
    KafkaClusterPodsResponse gatherPodsData(final String namespace,
                                             final String clusterName,
                                             final McpLog mcpLog) {
        try {
            return kafkaService.getClusterPods(namespace, clusterName);
        } catch (Exception e) {
            LOG.warnf("Failed to gather cluster pods: %s", e.getMessage());
            return null;
        }
    }

    void recordNodePoolsAndPodsStep(final List<KafkaNodePoolResponse> nodePools,
                                     final KafkaClusterPodsResponse pods,
                                     final List<String> completed,
                                     final List<String> failed,
                                     final McpLog mcpLog) {
        if (nodePools != null || pods != null) {
            completed.add(STEP_NODE_POOLS_AND_PODS);
            int poolCount = nodePools != null ? nodePools.size() : 0;
            DiagnosticHelper.sendClientNotification(mcpLog,
                String.format("Gathered %d node pool(s) and cluster pod health", poolCount));
        } else {
            failed.add(STEP_NODE_POOLS_AND_PODS + ": failed to gather node pools and pods");
        }
    }

    @WithSpan("diagnose.upgrade.replication_metrics")
    KafkaMetricsResponse gatherReplicationMetrics(final String namespace,
                                                   final String clusterName,
                                                   final List<String> completed,
                                                   final List<String> failed,
                                                   final McpLog mcpLog) {
        try {
            KafkaMetricsResponse result = metricsService.getKafkaMetrics(
                namespace, clusterName, "replication",
                null, null, null, null, null, null, null);
            completed.add(STEP_REPLICATION_METRICS);
            DiagnosticHelper.sendClientNotification(mcpLog, "Gathered replication health metrics");
            return result;
        } catch (Exception e) {
            LOG.warnf("Failed to gather replication metrics: %s", e.getMessage());
            failed.add(STEP_REPLICATION_METRICS + ": " + e.getMessage());
            return null;
        }
    }

    // ---- Phase 2: Deep investigation ----

    @WithSpan("diagnose.upgrade.performance_metrics")
    KafkaMetricsResponse gatherPerformanceMetrics(final String namespace,
                                                   final String clusterName,
                                                   final List<String> completed,
                                                   final List<String> failed,
                                                   final McpLog mcpLog) {
        try {
            KafkaMetricsResponse result = metricsService.getKafkaMetrics(
                namespace, clusterName, "performance",
                null, null, null, null, null, null, null);
            completed.add(STEP_PERFORMANCE_METRICS);
            DiagnosticHelper.sendClientNotification(mcpLog, "Gathered performance metrics");
            return result;
        } catch (Exception e) {
            LOG.warnf("Failed to gather performance metrics: %s", e.getMessage());
            failed.add(STEP_PERFORMANCE_METRICS + ": " + e.getMessage());
            return null;
        }
    }

    @WithSpan("diagnose.upgrade.resource_metrics")
    KafkaMetricsResponse gatherResourceMetrics(final String namespace,
                                                final String clusterName,
                                                final List<String> completed,
                                                final List<String> failed,
                                                final McpLog mcpLog) {
        try {
            KafkaMetricsResponse result = metricsService.getKafkaMetrics(
                namespace, clusterName, "resources",
                null, null, null, null, null, null, null);
            completed.add(STEP_RESOURCE_METRICS);
            DiagnosticHelper.sendClientNotification(mcpLog, "Gathered resource metrics");
            return result;
        } catch (Exception e) {
            LOG.warnf("Failed to gather resource metrics: %s", e.getMessage());
            failed.add(STEP_RESOURCE_METRICS + ": " + e.getMessage());
            return null;
        }
    }

    @WithSpan("diagnose.upgrade.drain_cleaner")
    DrainCleanerReadinessResponse gatherDrainCleaner(final String namespace,
                                                      final List<String> completed,
                                                      final List<String> failed,
                                                      final McpLog mcpLog) {
        try {
            DrainCleanerReadinessResponse result = drainCleanerService.checkReadiness(namespace);
            completed.add(STEP_DRAIN_CLEANER);
            DiagnosticHelper.sendClientNotification(mcpLog,
                "Checked Drain Cleaner readiness: "
                    + (result.overallReady() ? "ready" : "not ready"));
            return result;
        } catch (Exception e) {
            LOG.warnf("Failed to check Drain Cleaner readiness: %s", e.getMessage());
            failed.add(STEP_DRAIN_CLEANER + ": " + e.getMessage());
            return null;
        }
    }

    @WithSpan("diagnose.upgrade.certificates")
    KafkaCertificateResponse gatherCertificates(final String namespace,
                                                 final String clusterName,
                                                 final List<String> completed,
                                                 final List<String> failed,
                                                 final McpLog mcpLog) {
        try {
            KafkaCertificateResponse result = certificateService.getCertificates(
                namespace, clusterName, null);
            completed.add(STEP_CERTIFICATES);
            DiagnosticHelper.sendClientNotification(mcpLog, "Checked certificate expiry status");
            return result;
        } catch (Exception e) {
            LOG.warnf("Failed to gather certificate info: %s", e.getMessage());
            failed.add(STEP_CERTIFICATES + ": " + e.getMessage());
            return null;
        }
    }

    @WithSpan("diagnose.upgrade.events")
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
                String.format("Found %d cluster-related events", result.totalEvents()));
            return result;
        } catch (Exception e) {
            LOG.warnf("Failed to gather cluster events: %s", e.getMessage());
            failed.add(STEP_EVENTS + ": " + e.getMessage());
            return null;
        }
    }

    // ---- Sampling: triage and analysis ----

    @WithSpan("diagnose.upgrade.investigation")
    @SuppressWarnings("checkstyle:ParameterNumber")
    InvestigationAreas investigateAreas(final Sampling sampling,
                                         final KafkaClusterResponse cluster,
                                         final StrimziOperatorResponse operator,
                                         final List<KafkaNodePoolResponse> nodePools,
                                         final KafkaClusterPodsResponse pods,
                                         final KafkaMetricsResponse replicationMetrics,
                                         final String targetVersion) {
        if (sampling == null || !sampling.isSupported()) {
            return InvestigationAreas.all();
        }

        try {
            Map<String, Object> summary = buildPhase1Summary(
                cluster, operator, nodePools, pods, replicationMetrics, targetVersion);
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

    @WithSpan("diagnose.upgrade.analysis")
    @SuppressWarnings("checkstyle:ParameterNumber")
    String produceAnalysis(final Sampling sampling,
                           final KafkaClusterResponse cluster,
                           final StrimziOperatorResponse operator,
                           final List<KafkaNodePoolResponse> nodePools,
                           final KafkaClusterPodsResponse pods,
                           final KafkaMetricsResponse replicationMetrics,
                           final KafkaMetricsResponse performanceMetrics,
                           final KafkaMetricsResponse resourceMetrics,
                           final DrainCleanerReadinessResponse drainCleaner,
                           final KafkaCertificateResponse certificates,
                           final StrimziEventsResponse events,
                           final String targetVersion) {
        if (sampling == null || !sampling.isSupported()) {
            return null;
        }

        try {
            Map<String, Object> fullData = buildFullSummary(
                cluster, operator, nodePools, pods, replicationMetrics,
                performanceMetrics, resourceMetrics, drainCleaner,
                certificates, events, targetVersion);
            String dataJson = objectMapper.writeValueAsString(fullData);

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

    // ---- Helpers ----

    @SuppressWarnings("checkstyle:ParameterNumber")
    private Map<String, Object> buildPhase1Summary(final KafkaClusterResponse cluster,
                                                    final StrimziOperatorResponse operator,
                                                    final List<KafkaNodePoolResponse> nodePools,
                                                    final KafkaClusterPodsResponse pods,
                                                    final KafkaMetricsResponse replicationMetrics,
                                                    final String targetVersion) {
        Map<String, Object> summary = new LinkedHashMap<>();
        if (targetVersion != null) {
            summary.put("target_version", targetVersion);
        }
        summary.put("cluster_readiness", cluster.readiness());
        summary.put("cluster_version", cluster.kafkaVersion());
        if (cluster.replicas() != null) {
            summary.put("expected_replicas", cluster.replicas().expected());
            summary.put("ready_replicas", cluster.replicas().ready());
        }
        if (operator != null) {
            summary.put("operator_status", operator.status());
            summary.put("operator_version", operator.version());
        }
        if (nodePools != null) {
            summary.put("node_pool_count", nodePools.size());
        }
        DiagnosticHelper.putIfNotNull(summary, STEP_REPLICATION_METRICS, replicationMetrics);
        return summary;
    }

    @SuppressWarnings("checkstyle:ParameterNumber")
    private Map<String, Object> buildFullSummary(final KafkaClusterResponse cluster,
                                                  final StrimziOperatorResponse operator,
                                                  final List<KafkaNodePoolResponse> nodePools,
                                                  final KafkaClusterPodsResponse pods,
                                                  final KafkaMetricsResponse replicationMetrics,
                                                  final KafkaMetricsResponse performanceMetrics,
                                                  final KafkaMetricsResponse resourceMetrics,
                                                  final DrainCleanerReadinessResponse drainCleaner,
                                                  final KafkaCertificateResponse certificates,
                                                  final StrimziEventsResponse events,
                                                  final String targetVersion) {
        Map<String, Object> data = new LinkedHashMap<>();
        if (targetVersion != null) {
            data.put("target_version", targetVersion);
        }
        DiagnosticHelper.putIfNotNull(data, STEP_CLUSTER_STATUS, cluster);
        DiagnosticHelper.putIfNotNull(data, STEP_OPERATOR_STATUS, operator);
        DiagnosticHelper.putIfNotNull(data, "node_pools", nodePools);
        DiagnosticHelper.putIfNotNull(data, "pods", pods);
        DiagnosticHelper.putIfNotNull(data, STEP_REPLICATION_METRICS, replicationMetrics);
        DiagnosticHelper.putIfNotNull(data, STEP_PERFORMANCE_METRICS, performanceMetrics);
        DiagnosticHelper.putIfNotNull(data, STEP_RESOURCE_METRICS, resourceMetrics);
        DiagnosticHelper.putIfNotNull(data, STEP_DRAIN_CLEANER, drainCleaner);
        DiagnosticHelper.putIfNotNull(data, STEP_CERTIFICATES, certificates);
        DiagnosticHelper.putIfNotNull(data, STEP_EVENTS, events);
        return data;
    }

    private InvestigationAreas parseInvestigationAreas(final SamplingResponse response) {
        try {
            String text = DiagnosticHelper.extractSamplingText(response);
            Map<String, Object> parsed = objectMapper.readValue(text, DiagnosticHelper.MAP_TYPE_REF);
            return new InvestigationAreas(
                Boolean.TRUE.equals(parsed.get(STEP_PERFORMANCE_METRICS)),
                Boolean.TRUE.equals(parsed.get(STEP_RESOURCE_METRICS)),
                Boolean.TRUE.equals(parsed.get(STEP_DRAIN_CLEANER)),
                Boolean.TRUE.equals(parsed.get(STEP_CERTIFICATES)),
                Boolean.TRUE.equals(parsed.get(STEP_EVENTS))
            );
        } catch (Exception e) {
            LOG.debugf("Could not parse triage response, investigating all: %s", e.getMessage());
            return InvestigationAreas.all();
        }
    }

    /**
     * Flags indicating which Phase 2 investigation areas the LLM recommended.
     *
     * @param performanceMetrics whether to gather performance metrics
     * @param resourceMetrics    whether to gather JVM/resource metrics
     * @param drainCleaner       whether to check Drain Cleaner readiness
     * @param certificates       whether to check certificate expiry
     * @param events             whether to gather Kubernetes events
     */
    record InvestigationAreas(boolean performanceMetrics,
                              boolean resourceMetrics,
                              boolean drainCleaner,
                              boolean certificates,
                              boolean events) {

        /**
         * Returns areas with all flags set to true (fallback when Sampling is unavailable).
         *
         * @return investigation areas with all flags enabled
         */
        static InvestigationAreas all() {
            return new InvestigationAreas(true, true, true, true, true);
        }

        /**
         * Count how many investigation areas are enabled.
         *
         * @return the number of enabled investigation areas
         */
        private int enabledCount() {
            int c = 0;
            if (performanceMetrics) c++;
            if (resourceMetrics) c++;
            if (drainCleaner) c++;
            if (certificates) c++;
            if (events) c++;
            return c;
        }
    }

    // ---- Sampling system prompts ----

    static final String TRIAGE_SYSTEM_PROMPT = """
        You are a Kafka upgrade readiness assistant. \
        Analyze the initial cluster, operator, node pool, and replication findings and decide which \
        additional areas need deeper investigation before upgrading. \
        Return ONLY a JSON object with these boolean fields: \
        performance_metrics, resource_metrics, drain_cleaner, certificates, events. \
        Set true only for areas likely to reveal upgrade blockers. \
        For example, if the cluster is Ready with all replicas up and no replication lag, \
        you may skip performance_metrics and resource_metrics. \
        If the operator is not running or its version is very old, set events to true. \
        Always set certificates to true if the cluster uses TLS. \
        Always set drain_cleaner to true if node pools have more than a few replicas.\
        """;

    static final String ANALYSIS_SYSTEM_PROMPT = """
        You are assessing Kafka cluster upgrade readiness. Analyze all gathered diagnostic data \
        and produce a GO/NO-GO/CONDITIONAL upgrade verdict.

        Structure your response as:
        - Verdict: GO / NO-GO / CONDITIONAL
        - Pre-flight checklist (pass/fail for each area checked):
          * Cluster health
          * Operator readiness
          * Node pool and pod status
          * Replication health (under-replicated partitions, ISR lag)
          * Performance headroom (request queue times, handler idle ratio)
          * Resource headroom (JVM heap, GC pressure)
          * Drain Cleaner status (if applicable)
          * Certificate expiry (days remaining)
          * Recent warning/error events
        - Blocking issues (if any, with severity)
        - Recommendations (prioritized, actionable steps before upgrading)
        - Maintenance window estimate (based on cluster size and rolling restart time)

        Be conservative: flag CONDITIONAL if any area shows degradation, \
        and NO-GO if there are under-replicated partitions, expired certificates, \
        or the operator is not healthy.\
        """;
}
