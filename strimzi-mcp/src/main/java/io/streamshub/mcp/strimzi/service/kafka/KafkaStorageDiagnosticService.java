/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.service.kafka;

import io.opentelemetry.instrumentation.annotations.WithSpan;
import io.quarkiverse.mcp.server.Cancellation;
import io.quarkiverse.mcp.server.Elicitation;
import io.quarkiverse.mcp.server.McpException;
import io.quarkiverse.mcp.server.Progress;
import io.quarkiverse.mcp.server.Sampling;
import io.streamshub.mcp.common.service.BaseDiagnosticService;
import io.streamshub.mcp.common.service.DiagnosticHelper;
import io.streamshub.mcp.common.util.InputUtils;
import io.streamshub.mcp.common.util.McpErrors;
import io.streamshub.mcp.common.util.NamespaceElicitationHelper;
import io.streamshub.mcp.strimzi.config.StrimziConstants;
import io.streamshub.mcp.strimzi.config.metrics.KafkaMetricCategories;
import io.streamshub.mcp.strimzi.dto.kafka.KafkaClusterPodsResponse;
import io.streamshub.mcp.strimzi.dto.kafka.KafkaClusterResponse;
import io.streamshub.mcp.strimzi.dto.kafka.KafkaPvcResponse;
import io.streamshub.mcp.strimzi.dto.kafka.KafkaStorageDiagnosticReport;
import io.streamshub.mcp.strimzi.dto.kafkanodepool.KafkaNodePoolResponse;
import io.streamshub.mcp.strimzi.dto.metrics.KafkaMetricsResponse;
import io.streamshub.mcp.strimzi.dto.operator.StrimziEventsResponse;
import io.streamshub.mcp.strimzi.service.kafkanodepool.KafkaNodePoolService;
import io.streamshub.mcp.strimzi.service.metrics.KafkaMetricsService;
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
 * Orchestrates a multi-step storage diagnostic workflow for a Kafka cluster.
 *
 * <p>Phase 1 gathers cluster status, declared node pool storage layout, PVC status/capacity,
 * and pod health. Phase 2 gathers storage metrics and Kubernetes events.
 * Phase 3 uses Sampling for root cause analysis.</p>
 */
@ApplicationScoped
public class KafkaStorageDiagnosticService extends BaseDiagnosticService {

    private static final Logger LOG = Logger.getLogger(KafkaStorageDiagnosticService.class);

    private static final int PHASE1_STEPS = 4;
    private static final int MAX_PHASE2_STEPS = 2;

    private static final String STEP_CLUSTER_STATUS = "cluster_status";
    private static final String STEP_NODE_POOLS = "node_pools";
    private static final String STEP_PVCS = "pvcs";
    private static final String STEP_PODS = "pods";
    private static final String STEP_METRICS = "storage_metrics";
    private static final String STEP_EVENTS = "events";

    private static final String SYSTEM_PROMPT = """
        You are a storage diagnostic expert for Apache Kafka on Kubernetes managed by Strimzi.
        Analyze the gathered storage information (PVCs, capacity, volume expansion capability,
        node pool storage specs, pod health, disk metrics, and Kubernetes events) to determine:
        1. PVC binding issues (stuck Pending, missing StorageClass, provisioner failure).
        2. Storage capacity pressure or approaching disk exhaustion.
        3. Volume expansion capability (whether allowVolumeExpansion is true).
        4. Storage layout mismatch between NodePool storage declarations and actual PVCs.
        Provide a concise diagnosis and actionable remediation steps.
        """;

    @Inject
    KafkaService kafkaService;

    @Inject
    KafkaNodePoolService nodePoolService;

    @Inject
    KafkaMetricsService metricsService;

    @Inject
    StrimziEventsService eventsService;

    @Override
    protected Logger getLogger() {
        return LOG;
    }

    KafkaStorageDiagnosticService() {
    }

    /**
     * Run a multi-step storage diagnostic workflow for a Kafka cluster.
     *
     * @param namespace    optional namespace
     * @param clusterName  the Kafka cluster name
     * @param symptom      optional symptom description
     * @param sinceMinutes optional time window for events/metrics
     * @param sampling     MCP Sampling
     * @param elicitation  MCP Elicitation
     * @param progress     MCP progress tracking
     * @param cancellation MCP cancellation checking
     * @return the diagnostic report
     */
    @SuppressWarnings({"checkstyle:ParameterNumber", "checkstyle:NPathComplexity"})
    public KafkaStorageDiagnosticReport diagnose(final String namespace,
                                                 final String clusterName,
                                                 final String symptom,
                                                 final Integer sinceMinutes,
                                                 final Sampling sampling,
                                                 final Elicitation elicitation,
                                                 final Progress progress,
                                                 final Cancellation cancellation) {
        String ns = InputUtils.normalizeInput(namespace);
        ns = DiagnosticHelper.effectiveNamespace(sampling, ns);
        String name = InputUtils.normalizeInput(clusterName);

        if (name == null) {
            throw McpErrors.invalidParams("Cluster name is required");
        }

        LOG.infof("Starting storage diagnostic for cluster=%s (namespace=%s, symptom=%s)",
            name, ns != null ? ns : "auto", symptom);

        AtomicBoolean cancelled = new AtomicBoolean(false);
        DiagnosticHelper.registerCancellationCallback(cancellation, cancelled);

        List<String> completed = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        int stepIndex = 0;
        int maxSteps = PHASE1_STEPS + MAX_PHASE2_STEPS;

        // Phase 1: Cluster Status
        KafkaClusterResponse cluster = gatherClusterStatus(ns, name, elicitation, completed);
        DiagnosticHelper.sendProgress(progress, ++stepIndex, maxSteps,
            "Checked Kafka cluster status: " + cluster.readiness());
        DiagnosticHelper.checkCancellation(cancellation);

        String resolvedNs = cluster.namespace();

        // Phase 1: Node Pools
        List<KafkaNodePoolResponse> nodePools = gatherNodePools(resolvedNs, name, completed, failed);
        DiagnosticHelper.sendProgress(progress, ++stepIndex, maxSteps,
            String.format("Found %d KafkaNodePools", nodePools.size()));
        DiagnosticHelper.checkCancellation(cancellation);

        // Phase 1: PVCs
        KafkaPvcResponse pvcs = gatherPvcs(resolvedNs, name, completed, failed);
        DiagnosticHelper.sendProgress(progress, ++stepIndex, maxSteps,
            String.format("Found %d PersistentVolumeClaims", pvcs != null ? pvcs.pvcs().size() : 0));
        DiagnosticHelper.checkCancellation(cancellation);

        // Phase 1: Pods
        KafkaClusterPodsResponse pods = gatherPods(resolvedNs, name, completed, failed);
        DiagnosticHelper.sendProgress(progress, ++stepIndex, maxSteps,
            "Checked Kafka cluster pods");
        DiagnosticHelper.checkCancellation(cancellation);

        // Phase 2: Metrics
        KafkaMetricsResponse storageMetrics = gatherStorageMetrics(resolvedNs, name, sinceMinutes, completed, failed);
        DiagnosticHelper.sendProgress(progress, ++stepIndex, maxSteps,
            "Collected Kafka storage metrics");
        DiagnosticHelper.checkCancellation(cancellation);

        // Phase 2: Events
        StrimziEventsResponse events = gatherEvents(resolvedNs, name, sinceMinutes, completed, failed);
        DiagnosticHelper.sendProgress(progress, ++stepIndex, maxSteps,
            "Collected Kafka storage events");
        DiagnosticHelper.checkCancellation(cancellation);

        // Phase 3: Analysis
        String analysis = null;
        if (sampling != null && sampling.isSupported()) {
            Map<String, Object> fullData = new LinkedHashMap<>();
            DiagnosticHelper.putIfNotNull(fullData, "cluster", cluster);
            DiagnosticHelper.putIfNotNull(fullData, "node_pools", nodePools);
            DiagnosticHelper.putIfNotNull(fullData, "pvcs", pvcs);
            DiagnosticHelper.putIfNotNull(fullData, "pods", pods);
            DiagnosticHelper.putIfNotNull(fullData, "storage_metrics", storageMetrics);
            DiagnosticHelper.putIfNotNull(fullData, "events", events);
            if (symptom != null) {
                fullData.put("symptom", symptom);
            }

            analysis = performAnalysisMrtr(
                sampling, SYSTEM_PROMPT, fullData,
                "diagnose_kafka_storage", resolvedNs, cancelled);
        }

        return KafkaStorageDiagnosticReport.of(
            cluster,
            nodePools,
            pvcs,
            pods,
            storageMetrics,
            events,
            analysis,
            completed,
            failed);
    }

    private KafkaClusterResponse gatherClusterStatus(final String ns,
                                                     final String name,
                                                     final Elicitation elicitation,
                                                     final List<String> completed) {
        try {
            KafkaClusterResponse cluster = kafkaService.getCluster(ns, name);
            completed.add(STEP_CLUSTER_STATUS);
            return cluster;
        } catch (McpException e) {
            if (NamespaceElicitationHelper.isMultipleNamespacesError(e)
                    && elicitation != null && elicitation.isFormModeSupported()) {
                String chosenNs = NamespaceElicitationHelper.elicitNamespaceMrtr(
                    e, elicitation, "diagnosed", "namespace");
                return gatherClusterStatus(chosenNs, name, null, completed);
            }
            throw e;
        }
    }

    @WithSpan("diagnose.storage.nodepools")
    List<KafkaNodePoolResponse> gatherNodePools(final String ns,
                                                final String name,
                                                final List<String> completed,
                                                final List<String> failed) {
        try {
            List<KafkaNodePoolResponse> result = nodePoolService.listNodePools(ns, name);
            completed.add(STEP_NODE_POOLS);
            return result;
        } catch (Exception e) {
            LOG.warnf("Failed to gather node pools for cluster %s in ns %s: %s", name, ns, e.getMessage());
            failed.add(STEP_NODE_POOLS + ": " + e.getMessage());
            return List.of();
        }
    }

    @WithSpan("diagnose.storage.pvcs")
    KafkaPvcResponse gatherPvcs(final String ns,
                                final String name,
                                final List<String> completed,
                                final List<String> failed) {
        try {
            KafkaPvcResponse result = kafkaService.getClusterPvcs(ns, name);
            completed.add(STEP_PVCS);
            return result;
        } catch (Exception e) {
            LOG.warnf("Failed to gather PVCs for cluster %s in ns %s: %s", name, ns, e.getMessage());
            failed.add(STEP_PVCS + ": " + e.getMessage());
            return KafkaPvcResponse.of(name, ns, List.of());
        }
    }

    @WithSpan("diagnose.storage.pods")
    KafkaClusterPodsResponse gatherPods(final String ns,
                                        final String name,
                                        final List<String> completed,
                                        final List<String> failed) {
        try {
            KafkaClusterPodsResponse result = kafkaService.getClusterPods(ns, name);
            completed.add(STEP_PODS);
            return result;
        } catch (Exception e) {
            LOG.warnf("Failed to gather pods for cluster %s in ns %s: %s", name, ns, e.getMessage());
            failed.add(STEP_PODS + ": " + e.getMessage());
            return null;
        }
    }

    @WithSpan("diagnose.storage.metrics")
    KafkaMetricsResponse gatherStorageMetrics(final String ns,
                                              final String name,
                                              final Integer sinceMinutes,
                                              final List<String> completed,
                                              final List<String> failed) {
        try {
            KafkaMetricsResponse result = metricsService.getKafkaMetrics(
                ns, name, KafkaMetricCategories.STORAGE, null, sinceMinutes, null, null, null, null, null);
            completed.add(STEP_METRICS);
            return result;
        } catch (Exception e) {
            LOG.warnf("Failed to gather storage metrics for cluster %s in ns %s: %s", name, ns, e.getMessage());
            failed.add(STEP_METRICS + ": " + e.getMessage());
            return null;
        }
    }

    @WithSpan("diagnose.storage.events")
    StrimziEventsResponse gatherEvents(final String ns,
                                       final String name,
                                       final Integer sinceMinutes,
                                       final List<String> completed,
                                       final List<String> failed) {
        try {
            StrimziEventsResponse result = eventsService.getEvents(
                ns, name, StrimziConstants.KindValues.KAFKA, sinceMinutes);
            completed.add(STEP_EVENTS);
            return result;
        } catch (Exception e) {
            LOG.warnf("Failed to gather storage events for cluster %s in ns %s: %s", name, ns, e.getMessage());
            failed.add(STEP_EVENTS + ": " + e.getMessage());
            return null;
        }
    }
}
