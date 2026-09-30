/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.service.kafkanodepool;

import io.fabric8.kubernetes.api.model.PersistentVolumeClaim;
import io.fabric8.kubernetes.api.model.Pod;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import io.quarkiverse.mcp.server.Cancellation;
import io.quarkiverse.mcp.server.Elicitation;
import io.quarkiverse.mcp.server.McpException;
import io.quarkiverse.mcp.server.Progress;
import io.quarkiverse.mcp.server.Sampling;
import io.streamshub.mcp.common.config.KubernetesConstants;
import io.streamshub.mcp.common.dto.LogCollectionParams;
import io.streamshub.mcp.common.dto.PodLogsResult;
import io.streamshub.mcp.common.dto.PodSummaryResponse;
import io.streamshub.mcp.common.dto.ResourceEventsResult;
import io.streamshub.mcp.common.service.BaseDiagnosticService;
import io.streamshub.mcp.common.service.DiagnosticHelper;
import io.streamshub.mcp.common.service.KubernetesEventsService;
import io.streamshub.mcp.common.service.KubernetesResourceService;
import io.streamshub.mcp.common.service.log.LogCollectionService;
import io.streamshub.mcp.common.util.InputUtils;
import io.streamshub.mcp.common.util.McpErrors;
import io.streamshub.mcp.common.util.NamespaceElicitationHelper;
import io.streamshub.mcp.strimzi.config.StrimziConstants;
import io.streamshub.mcp.strimzi.dto.kafkanodepool.KafkaNodePoolDiagnosticReport;
import io.streamshub.mcp.strimzi.dto.kafkanodepool.KafkaNodePoolLogsResponse;
import io.streamshub.mcp.strimzi.dto.kafkanodepool.KafkaNodePoolPodsResponse;
import io.streamshub.mcp.strimzi.dto.kafkanodepool.KafkaNodePoolPvcStatus;
import io.streamshub.mcp.strimzi.dto.kafkanodepool.KafkaNodePoolResponse;
import io.streamshub.mcp.strimzi.dto.operator.StrimziEventsResponse;
import io.strimzi.api.ResourceLabels;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
/**
 * Orchestrates a multi-step diagnostic workflow for KafkaNodePool instances.
 *
 * <p>Phase 1 gathers node pool status (roles, replicas, node IDs, storage, conditions)
 * and pod health. Phase 2 uses Sampling to decide which of PVC storage binding,
 * Kubernetes events, and pod logs are worth gathering. Phase 3 uses Sampling for
 * root cause analysis, focused on the four failure modes that make a node pool
 * unhealthy: scaling stuck, node IDs not assigned, storage not bound, and pods
 * not scheduling.</p>
 */
@ApplicationScoped
public class KafkaNodePoolDiagnosticService extends BaseDiagnosticService {

    private static final Logger LOG = Logger.getLogger(KafkaNodePoolDiagnosticService.class);
    private static final int SECONDS_PER_MINUTE = 60;
    private static final int PHASE1_STEPS = 2;
    private static final String KIND_POD = "Pod";
    private static final String STEP_NODE_POOL = "node_pool";
    private static final String STEP_PODS = "pods";
    private static final String STEP_PVCS = "pvcs";
    private static final String STEP_EVENTS = "events";
    private static final String STEP_LOGS = "logs";

    @Inject
    KafkaNodePoolService nodePoolService;

    @Inject
    KubernetesResourceService k8sService;

    @Inject
    KubernetesEventsService eventsService;

    @Inject
    LogCollectionService logCollectionService;

    @Override
    protected Logger getLogger() {
        return LOG;
    }

    KafkaNodePoolDiagnosticService() {
    }

    /**
     * Run a multi-step diagnostic for a KafkaNodePool.
     *
     * @param namespace    optional namespace
     * @param nodePoolName the KafkaNodePool name
     * @param clusterName  optional parent Kafka cluster name
     * @param symptom      optional symptom description
     * @param sinceMinutes optional time window for events/logs
     * @param sampling     MCP Sampling for LLM analysis
     * @param elicitation  MCP Elicitation for user input
     * @param progress     MCP progress tracking
     * @param cancellation MCP cancellation checking
     * @return the diagnostic report
     */
    @SuppressWarnings({"checkstyle:ParameterNumber", "checkstyle:NPathComplexity"})
    public KafkaNodePoolDiagnosticReport diagnose(final String namespace,
                                                   final String nodePoolName,
                                                   final String clusterName,
                                                   final String symptom,
                                                   final Integer sinceMinutes,
                                                   final Sampling sampling,
                                                   final Elicitation elicitation,
                                                   final Progress progress,
                                                   final Cancellation cancellation) {
        String ns = InputUtils.normalizeInput(namespace);
        ns = DiagnosticHelper.effectiveNamespace(sampling, ns);
        String name = InputUtils.normalizeInput(nodePoolName);
        String cluster = InputUtils.normalizeInput(clusterName);

        if (name == null) {
            throw McpErrors.invalidParams("KafkaNodePool name is required");
        }

        LOG.infof("Starting diagnostic for KafkaNodePool=%s (cluster=%s, namespace=%s, symptom=%s)",
            name, cluster != null ? cluster : "auto", ns != null ? ns : "auto", symptom);

        // Register push-based cancellation callback for async operations
        AtomicBoolean cancelled = new AtomicBoolean(false);
        DiagnosticHelper.registerCancellationCallback(cancellation, cancelled);

        List<String> completed = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        int stepIndex = 0;

        // === Phase 1: Initial data gathering ===
        int maxSteps = PHASE1_STEPS + InvestigationAreas.MAX_AREAS;

        KafkaNodePoolResponse nodePool = gatherNodePool(ns, cluster, name, elicitation, completed);
        DiagnosticHelper.sendProgress(progress, ++stepIndex, maxSteps,
            "Checked KafkaNodePool status: " + (Boolean.TRUE.equals(nodePool.ready()) ? "Ready" : "NotReady"));
        DiagnosticHelper.checkCancellation(cancellation);

        String resolvedNs = nodePool.namespace() != null ? nodePool.namespace() : ns;
        String resolvedCluster = nodePool.cluster() != null ? nodePool.cluster() : cluster;

        KafkaNodePoolPodsResponse pods = gatherPods(resolvedNs, resolvedCluster, name, completed, failed);
        DiagnosticHelper.sendProgress(progress, ++stepIndex, maxSteps,
            pods != null ? "Checked KafkaNodePool pod health" : "Failed to check pod health");
        DiagnosticHelper.checkCancellation(cancellation);

        // === Phase 2: Deep investigation ===
        InvestigationAreas areas = investigateAreas(sampling, nodePool, pods, symptom, cancelled);
        DiagnosticHelper.checkAsyncCancellation(cancelled);

        int totalSteps = PHASE1_STEPS + areas.enabledCount();

        List<KafkaNodePoolPvcStatus> pvcs = null;
        if (areas.pvcs) {
            pvcs = gatherPvcs(resolvedNs, resolvedCluster, name, completed, failed);
            DiagnosticHelper.sendProgress(progress, ++stepIndex, totalSteps,
                pvcs != null ? String.format("Found %d PVCs for KafkaNodePool", pvcs.size())
                    : "Failed to check PVC storage binding");
            DiagnosticHelper.checkCancellation(cancellation);
        }

        StrimziEventsResponse events = null;
        if (areas.events) {
            events = gatherEvents(resolvedNs, name, pods, sinceMinutes, completed, failed);
            DiagnosticHelper.sendProgress(progress, ++stepIndex, totalSteps,
                events != null ? String.format("Found %d related events", events.totalEvents())
                    : "Failed to gather events");
            DiagnosticHelper.checkCancellation(cancellation);
        }

        KafkaNodePoolLogsResponse logs = null;
        if (areas.logs) {
            logs = gatherLogs(resolvedNs, name, sinceMinutes, completed, failed);
            DiagnosticHelper.sendProgress(progress, ++stepIndex, totalSteps,
                logs != null ? "Collected KafkaNodePool pod logs" : "Failed to collect logs");
            DiagnosticHelper.checkCancellation(cancellation);
        }

        // === Phase 3: Analysis ===
        String analysis = produceAnalysis(sampling, nodePool, pods, pvcs, events, logs, symptom, cancelled);
        DiagnosticHelper.checkAsyncCancellation(cancelled);

        return KafkaNodePoolDiagnosticReport.of(nodePool, pods, pvcs, events, logs,
            analysis, completed, failed.isEmpty() ? null : failed);
    }

    // ---- Phase 1 ----

    @WithSpan("diagnose.node_pool.status")
    KafkaNodePoolResponse gatherNodePool(final String namespace,
                                         final String clusterName,
                                         final String name,
                                         final Elicitation elicitation,
                                         final List<String> completed) {
        try {
            KafkaNodePoolResponse result = nodePoolService.getNodePool(namespace, clusterName, name);
            completed.add(STEP_NODE_POOL);
            return result;
        } catch (McpException e) {
            if (NamespaceElicitationHelper.isMultipleNamespacesError(e)
                    && elicitation != null && elicitation.isFormModeSupported()) {
                String resolved = NamespaceElicitationHelper.elicitNamespaceMrtr(
                    e, elicitation, "diagnosed", "namespace");
                return gatherNodePool(resolved, clusterName, name, null, completed);
            }
            throw e;
        }
    }

    @WithSpan("diagnose.node_pool.pods")
    KafkaNodePoolPodsResponse gatherPods(final String namespace,
                                         final String clusterName,
                                         final String name,
                                         final List<String> completed,
                                         final List<String> failed) {
        try {
            List<PodSummaryResponse.PodInfo> items = nodePoolService.getNodePoolPods(namespace, clusterName, name);
            KafkaNodePoolPodsResponse result = new KafkaNodePoolPodsResponse(items, items.size());
            completed.add(STEP_PODS);
            return result;
        } catch (Exception e) {
            LOG.warnf("Failed to gather KafkaNodePool pods: %s", e.getMessage());
            failed.add(STEP_PODS + ": " + e.getMessage());
            return null;
        }
    }

    // ---- Phase 2 ----

    @WithSpan("diagnose.node_pool.pvcs")
    List<KafkaNodePoolPvcStatus> gatherPvcs(final String namespace,
                                             final String clusterName,
                                             final String name,
                                             final List<String> completed,
                                             final List<String> failed) {
        try {
            List<PersistentVolumeClaim> claims = k8sService.queryResourcesByLabel(
                PersistentVolumeClaim.class, namespace, ResourceLabels.STRIMZI_CLUSTER_LABEL, clusterName);
            // Strimzi names PVCs data[-<volumeId>]-<cluster>-<pool>-<nodeId>. A plain "contains(pool)"
            // would also match a sibling pool whose name starts with this one (broker vs broker-2).
            Pattern poolSuffix = Pattern.compile("-" + Pattern.quote(name) + "-\\d+$");
            List<KafkaNodePoolPvcStatus> result = claims.stream()
                .filter(pvc -> poolSuffix.matcher(pvc.getMetadata().getName()).find())
                .map(this::toPvcStatus)
                .toList();
            completed.add(STEP_PVCS);
            return result;
        } catch (Exception e) {
            LOG.warnf("Failed to gather KafkaNodePool PVCs: %s", e.getMessage());
            failed.add(STEP_PVCS + ": " + e.getMessage());
            return null;
        }
    }

    @WithSpan("diagnose.node_pool.events")
    StrimziEventsResponse gatherEvents(final String namespace,
                                       final String name,
                                       final KafkaNodePoolPodsResponse pods,
                                       final Integer sinceMinutes,
                                       final List<String> completed,
                                       final List<String> failed) {
        try {
            if (pods == null || pods.items().isEmpty()) {
                completed.add(STEP_EVENTS);
                return StrimziEventsResponse.empty(name, namespace);
            }
            Instant sinceTime = sinceMinutes != null
                ? Instant.now().minusSeconds((long) sinceMinutes * SECONDS_PER_MINUTE)
                : null;
            List<ResourceEventsResult> results = new ArrayList<>();
            for (PodSummaryResponse.PodInfo pod : pods.items()) {
                ResourceEventsResult podEvents = eventsService.getEventsForResource(
                    namespace, pod.name(), KIND_POD, sinceTime);
                if (!podEvents.events().isEmpty()) {
                    results.add(podEvents);
                }
            }
            completed.add(STEP_EVENTS);
            return StrimziEventsResponse.of(name, namespace, results);
        } catch (Exception e) {
            LOG.warnf("Failed to gather KafkaNodePool related events: %s", e.getMessage());
            failed.add(STEP_EVENTS + ": " + e.getMessage());
            return null;
        }
    }

    @WithSpan("diagnose.node_pool.logs")
    KafkaNodePoolLogsResponse gatherLogs(final String namespace,
                                         final String name,
                                         final Integer sinceMinutes,
                                         final List<String> completed,
                                         final List<String> failed) {
        try {
            List<Pod> pods = k8sService.queryResourcesByLabel(
                Pod.class, namespace, StrimziConstants.Labels.POOL_NAME, name);
            if (pods.isEmpty()) {
                completed.add(STEP_LOGS);
                return KafkaNodePoolLogsResponse.empty(name, namespace);
            }

            LogCollectionParams options = LogCollectionParams.builder(defaultTailLines)
                .filter("errors")
                .sinceSeconds(sinceMinutes != null ? sinceMinutes * SECONDS_PER_MINUTE : null)
                .build();
            PodLogsResult result = logCollectionService.collectLogs(namespace, pods, options);
            KafkaNodePoolLogsResponse response = KafkaNodePoolLogsResponse.of(name, namespace, result.podNames(),
                result.hasErrors(), result.errorCount(), result.failedPods(),
                result.totalLines(), result.hasMore(), result.logs(), result.warnings());
            completed.add(STEP_LOGS);
            return response;
        } catch (Exception e) {
            LOG.warnf("Failed to gather KafkaNodePool logs: %s", e.getMessage());
            failed.add(STEP_LOGS + ": " + e.getMessage());
            return null;
        }
    }

    private KafkaNodePoolPvcStatus toPvcStatus(final PersistentVolumeClaim pvc) {
        String phase = pvc.getStatus() != null ? pvc.getStatus().getPhase() : KubernetesConstants.UNKNOWN;
        return KafkaNodePoolPvcStatus.of(pvc.getMetadata().getName(), phase);
    }

    // ---- Sampling: triage and analysis ----

    @WithSpan("diagnose.node_pool.investigation")
    InvestigationAreas investigateAreas(final Sampling sampling,
                                        final KafkaNodePoolResponse nodePool,
                                        final KafkaNodePoolPodsResponse pods,
                                        final String symptom,
                                        final AtomicBoolean cancelled) {
        Map<String, Object> parsed = performTriage(sampling, TRIAGE_SYSTEM_PROMPT,
            buildPhase1Summary(nodePool, pods, symptom), cancelled);
        return parsed != null ? parseInvestigationAreas(parsed) : InvestigationAreas.all();
    }

    @WithSpan("diagnose.node_pool.analysis")
    @SuppressWarnings("checkstyle:ParameterNumber")
    String produceAnalysis(final Sampling sampling,
                           final KafkaNodePoolResponse nodePool,
                           final KafkaNodePoolPodsResponse pods,
                           final List<KafkaNodePoolPvcStatus> pvcs,
                           final StrimziEventsResponse events,
                           final KafkaNodePoolLogsResponse logs,
                           final String symptom,
                           final AtomicBoolean cancelled) {
        return performAnalysisMrtr(sampling, ANALYSIS_SYSTEM_PROMPT,
            buildFullSummary(nodePool, pods, pvcs, events, logs, symptom),
            "analysis", nodePool.namespace(), cancelled);
    }

    // ---- Helpers ----

    private Map<String, Object> buildPhase1Summary(final KafkaNodePoolResponse nodePool,
                                                    final KafkaNodePoolPodsResponse pods,
                                                    final String symptom) {
        Map<String, Object> summary = new LinkedHashMap<>();
        if (symptom != null) {
            summary.put("symptom", symptom);
        }
        summary.put("node_pool_name", nodePool.name());
        summary.put("roles", nodePool.roles());
        summary.put("replicas", nodePool.replicas());
        summary.put("status_replicas", nodePool.statusReplicas());
        summary.put("node_ids", nodePool.nodeIds());
        summary.put("storage_type", nodePool.storageType());
        summary.put("ready", nodePool.ready());
        DiagnosticHelper.putIfNotNull(summary, STEP_PODS, pods);
        return summary;
    }

    private Map<String, Object> buildFullSummary(final KafkaNodePoolResponse nodePool,
                                                  final KafkaNodePoolPodsResponse pods,
                                                  final List<KafkaNodePoolPvcStatus> pvcs,
                                                  final StrimziEventsResponse events,
                                                  final KafkaNodePoolLogsResponse logs,
                                                  final String symptom) {
        Map<String, Object> data = new LinkedHashMap<>();
        if (symptom != null) {
            data.put("symptom", symptom);
        }
        DiagnosticHelper.putIfNotNull(data, STEP_NODE_POOL, nodePool);
        DiagnosticHelper.putIfNotNull(data, STEP_PODS, pods);
        DiagnosticHelper.putIfNotNull(data, STEP_PVCS, pvcs);
        DiagnosticHelper.putIfNotNull(data, STEP_EVENTS, events);
        DiagnosticHelper.putIfNotNull(data, STEP_LOGS, logs);
        return data;
    }

    private InvestigationAreas parseInvestigationAreas(final Map<String, Object> parsed) {
        return new InvestigationAreas(
            Boolean.TRUE.equals(parsed.get(STEP_PVCS)),
            Boolean.TRUE.equals(parsed.get(STEP_EVENTS)),
            Boolean.TRUE.equals(parsed.get(STEP_LOGS))
        );
    }

    /**
     * Flags indicating which investigation areas the LLM recommended.
     *
     * @param pvcs   whether to check PVC storage binding status
     * @param events whether to gather Kubernetes events for the pool's pods
     * @param logs   whether to gather KafkaNodePool pod logs
     */
    record InvestigationAreas(boolean pvcs, boolean events, boolean logs) {

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
            if (pvcs) c++;
            if (events) c++;
            if (logs) c++;
            return c;
        }
    }

    // ---- Sampling system prompts ----

    static final String TRIAGE_SYSTEM_PROMPT = """
        You are a Kafka KRaft node pool diagnostics assistant. \
        Analyze the initial KafkaNodePool status and pod findings and decide which areas \
        need deeper investigation. \
        Return ONLY a JSON object with these boolean fields: \
        pvcs, events, logs. \
        Set true only for areas likely to reveal the root cause. \
        Watch for four failure modes: scaling stuck (replicas does not match status_replicas), \
        node IDs not assigned (node_ids is empty while replicas is greater than zero), \
        storage not bound (PVCs stuck Pending), and pods not scheduling. \
        If replicas does not match status_replicas or node_ids looks incomplete, set events to true. \
        If storage_type indicates persistent storage, set pvcs to true. \
        If pods are pending, not ready, or restarting, set events and logs to true. \
        If the pool is ready and replicas match, set all to false.\
        """;

    static final String ANALYSIS_SYSTEM_PROMPT = """
        You are diagnosing a KafkaNodePool (KRaft node pool) issue. \
        Analyze all gathered data and produce a structured diagnosis.

        Structure your response as:
        - Root cause (one sentence)
        - Severity: CRITICAL / HIGH / MEDIUM / LOW
        - Impact: what is affected (broker capacity, controller quorum, data availability)
        - Evidence: key findings from status, pods, PVCs, events, logs
        - Recommendations: specific, actionable remediation steps

        Common KafkaNodePool issue categories:
        1. Scaling stuck: spec.replicas does not match status.replicas after a scale operation,
           usually because new pods cannot schedule or the operator reconciliation is failing
        2. Node IDs not assigned: status.nodeIds is empty or incomplete for a KRaft pool,
           often caused by a stuck reconciliation or a full node ID range
        3. Storage not bound: a PersistentVolumeClaim is stuck Pending, typically due to a missing
           StorageClass, no available Persistent Volumes, or a storage provisioner failure
        4. Pods not scheduling: FailedScheduling or FailedAttachVolume events indicate insufficient
           node resources, taints/tolerations mismatches, or volume attachment failures
        5. Crash loops: repeated container restarts visible in pod logs, e.g. misconfiguration or OOM\
        """;
}
