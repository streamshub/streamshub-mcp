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
import io.streamshub.mcp.strimzi.dto.KafkaBootstrapResponse;
import io.streamshub.mcp.strimzi.dto.KafkaCertificateResponse;
import io.streamshub.mcp.strimzi.dto.KafkaClusterLogsResponse;
import io.streamshub.mcp.strimzi.dto.KafkaClusterPodsResponse;
import io.streamshub.mcp.strimzi.dto.KafkaClusterResponse;
import io.streamshub.mcp.strimzi.dto.KafkaConnectivityDiagnosticReport;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Orchestrates a multistep connectivity diagnostic workflow for Kafka clusters.
 *
 * <p>Checks listeners, bootstrap addresses, TLS certificates, authentication,
 * pod health, and connection-related logs in a single operation. Optionally uses
 * MCP Sampling for LLM analysis and Elicitation for namespace disambiguation.</p>
 *
 * <p>Individual step failures do not abort the workflow. Failed steps are
 * recorded and the report includes all data that was successfully gathered.</p>
 */
@ApplicationScoped
public class KafkaConnectivityDiagnosticService {

    private static final Logger LOG = Logger.getLogger(KafkaConnectivityDiagnosticService.class);
    private static final int PHASE1_STEPS = 2;
    private static final int MAX_PHASE2_STEPS = 3;
    private static final String DIAGNOSTIC_LABEL = "Kafka connectivity diagnostic";
    private static final List<String> CONNECTIVITY_KEYWORDS = List.of(
        "TLS", "SSL", "handshake", "authentication", "SASL",
        "connection refused", "SocketException", "SSLHandshakeException",
        "SaslAuthenticationException", "listener");

    private static final String STEP_CLUSTER_STATUS = "cluster_status";
    private static final String STEP_BOOTSTRAP_SERVERS = "bootstrap_servers";
    private static final String STEP_CERTIFICATES = "certificates";
    private static final String STEP_POD_HEALTH = "pod_health";
    private static final String STEP_CLUSTER_LOGS = "cluster_logs";

    @Inject
    KafkaService kafkaService;

    @Inject
    KafkaCertificateService kafkaCertificateService;

    @Inject
    ObjectMapper objectMapper;

    @ConfigProperty(name = "mcp.log.tail-lines", defaultValue = "200")
    int defaultTailLines;

    @ConfigProperty(name = "mcp.sampling.triage-max-tokens", defaultValue = "200")
    int triageMaxTokens;

    @ConfigProperty(name = "mcp.sampling.analysis-max-tokens", defaultValue = "1500")
    int analysisMaxTokens;

    KafkaConnectivityDiagnosticService() {
    }

    /**
     * Run a multistep connectivity diagnostic workflow for a Kafka cluster.
     *
     * <p>Phase 1 gathers cluster status and bootstrap server addresses.
     * Phase 2 uses Sampling (if supported) to decide which areas need
     * deeper investigation, then gathers certificates, pod health,
     * and/or connection-related logs. Phase 3 uses Sampling to produce
     * a connectivity analysis.</p>
     *
     * @param namespace    optional namespace (elicited if ambiguous)
     * @param clusterName  the Kafka cluster name
     * @param listenerName optional listener to focus on
     * @param sampling     MCP Sampling for LLM analysis (may be unsupported)
     * @param elicitation  MCP Elicitation for user input (may be unsupported)
     * @param mcpLog       MCP log for progress notifications
     * @param progress     MCP progress tracking
     * @param cancellation MCP cancellation checking
     * @return a consolidated connectivity diagnostic report
     */
    @SuppressWarnings("checkstyle:ParameterNumber")
    public KafkaConnectivityDiagnosticReport diagnose(final String namespace,
                                                      final String clusterName,
                                                      final String listenerName,
                                                      final Sampling sampling,
                                                      final Elicitation elicitation,
                                                      final McpLog mcpLog,
                                                      final Progress progress,
                                                      final Cancellation cancellation) {
        String ns = InputUtils.normalizeInput(namespace);
        String name = InputUtils.normalizeInput(clusterName);
        String listener = InputUtils.normalizeInput(listenerName);

        if (name == null) {
            throw new ToolCallException("Cluster name is required");
        }

        LOG.infof("Starting connectivity diagnostic for cluster=%s (namespace=%s, listener=%s)",
            name, ns != null ? ns : "auto", listener != null ? listener : "all");

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

        KafkaBootstrapResponse bootstrapServers = gatherBootstrapServers(
            resolvedNs, name, completed, failed, mcpLog);
        DiagnosticHelper.sendProgress(progress, ++stepIndex, maxSteps, DIAGNOSTIC_LABEL);
        DiagnosticHelper.checkCancellation(cancellation);

        // === Phase 2: Deep investigation ===
        InvestigationAreas areas = decideInvestigationAreas(
            sampling, cluster, bootstrapServers);

        int totalSteps = PHASE1_STEPS + areas.count();

        KafkaCertificateResponse certificates = null;
        if (areas.certificates) {
            certificates = gatherCertificates(
                resolvedNs, name, listener, completed, failed, mcpLog);
            DiagnosticHelper.sendProgress(progress, ++stepIndex, totalSteps, DIAGNOSTIC_LABEL);
            DiagnosticHelper.checkCancellation(cancellation);
        }

        KafkaClusterPodsResponse pods = null;
        if (areas.pods) {
            pods = gatherClusterPods(
                resolvedNs, name, completed, failed, mcpLog);
            DiagnosticHelper.sendProgress(progress, ++stepIndex, totalSteps, DIAGNOSTIC_LABEL);
            DiagnosticHelper.checkCancellation(cancellation);
        }

        KafkaClusterLogsResponse clusterLogs = null;
        if (areas.clusterLogs) {
            clusterLogs = gatherConnectivityLogs(
                resolvedNs, name, completed, failed, mcpLog);
            DiagnosticHelper.sendProgress(progress, ++stepIndex, totalSteps, DIAGNOSTIC_LABEL);
            DiagnosticHelper.checkCancellation(cancellation);
        }

        // === Phase 3: Final analysis ===
        String analysis = produceAnalysis(sampling, cluster, bootstrapServers,
            certificates, pods, clusterLogs, listener);

        return KafkaConnectivityDiagnosticReport.of(cluster, bootstrapServers,
            certificates, pods, clusterLogs, analysis,
            completed, failed.isEmpty() ? null : failed);
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
                String resolved = NamespaceElicitationHelper.elicitNamespace(e, elicitation, "checked for connectivity");
                return gatherClusterStatus(resolved, clusterName, null,
                    completed, mcpLog);
            }
            throw e;
        }
    }

    private KafkaBootstrapResponse gatherBootstrapServers(final String namespace,
                                                          final String clusterName,
                                                          final List<String> completed,
                                                          final List<String> failed,
                                                          final McpLog mcpLog) {
        try {
            KafkaBootstrapResponse result = kafkaService.getBootstrapServers(namespace, clusterName);
            completed.add(STEP_BOOTSTRAP_SERVERS);
            int listenerCount = result.bootstrapServers() != null ? result.bootstrapServers().size() : 0;
            DiagnosticHelper.sendClientNotification(mcpLog, String.format("Found %d Kafka listeners", listenerCount));
            return result;
        } catch (Exception e) {
            LOG.warnf("Failed to gather Kafka bootstrap servers: %s", e.getMessage());
            failed.add(STEP_BOOTSTRAP_SERVERS + ": " + e.getMessage());
            return null;
        }
    }

    // ---- Phase 2: Deep investigation ----

    private KafkaCertificateResponse gatherCertificates(final String namespace,
                                                        final String clusterName,
                                                        final String listenerName,
                                                        final List<String> completed,
                                                        final List<String> failed,
                                                        final McpLog mcpLog) {
        try {
            KafkaCertificateResponse result = kafkaCertificateService.getCertificates(
                namespace, clusterName, listenerName);
            completed.add(STEP_CERTIFICATES);
            DiagnosticHelper.sendClientNotification(mcpLog, "Checked TLS certificates and authentication");
            return result;
        } catch (Exception e) {
            LOG.warnf("Failed to gather Kafka TLS certificates: %s", e.getMessage());
            failed.add(STEP_CERTIFICATES + ": " + e.getMessage());
            return null;
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

    private KafkaClusterLogsResponse gatherConnectivityLogs(final String namespace,
                                                            final String clusterName,
                                                            final List<String> completed,
                                                            final List<String> failed,
                                                            final McpLog mcpLog) {
        try {
            LogCollectionParams params = LogCollectionParams.builder(defaultTailLines)
                .keywords(CONNECTIVITY_KEYWORDS)
                .build();
            KafkaClusterLogsResponse result = kafkaService.getClusterLogs(
                namespace, clusterName, params);
            completed.add(STEP_CLUSTER_LOGS);
            DiagnosticHelper.sendClientNotification(mcpLog, "Collected connectivity-related logs");
            return result;
        } catch (Exception e) {
            LOG.warnf("Failed to gather Kafka connectivity logs: %s", e.getMessage());
            failed.add(STEP_CLUSTER_LOGS + ": " + e.getMessage());
            return null;
        }
    }

    // ---- Sampling: triage and analysis ----

    private InvestigationAreas decideInvestigationAreas(final Sampling sampling,
                                                        final KafkaClusterResponse cluster,
                                                        final KafkaBootstrapResponse bootstrapServers) {
        if (sampling == null || !sampling.isSupported()) {
            return InvestigationAreas.all();
        }

        try {
            Map<String, Object> summary = buildPhase1Summary(cluster, bootstrapServers);
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
                                   final KafkaBootstrapResponse bootstrapServers,
                                   final KafkaCertificateResponse certificates,
                                   final KafkaClusterPodsResponse pods,
                                   final KafkaClusterLogsResponse clusterLogs,
                                   final String listenerName) {
        if (sampling == null || !sampling.isSupported()) {
            return null;
        }

        try {
            Map<String, Object> fullData = buildFullSummary(
                cluster, bootstrapServers, certificates, pods, clusterLogs, listenerName);
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

    private Map<String, Object> buildPhase1Summary(final KafkaClusterResponse cluster,
                                                   final KafkaBootstrapResponse bootstrapServers) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("cluster_readiness", cluster.readiness());
        if (bootstrapServers != null) {
            summary.put(STEP_BOOTSTRAP_SERVERS, bootstrapServers);
        }
        return summary;
    }

    @SuppressWarnings("checkstyle:ParameterNumber")
    private Map<String, Object> buildFullSummary(final KafkaClusterResponse cluster,
                                                 final KafkaBootstrapResponse bootstrapServers,
                                                 final KafkaCertificateResponse certificates,
                                                 final KafkaClusterPodsResponse pods,
                                                 final KafkaClusterLogsResponse clusterLogs,
                                                 final String listenerName) {
        Map<String, Object> data = new LinkedHashMap<>();
        if (listenerName != null) {
            data.put("listener_filter", listenerName);
        }
        DiagnosticHelper.putIfNotNull(data, STEP_CLUSTER_STATUS, cluster);
        DiagnosticHelper.putIfNotNull(data, STEP_BOOTSTRAP_SERVERS, bootstrapServers);
        DiagnosticHelper.putIfNotNull(data, STEP_CERTIFICATES, certificates);
        DiagnosticHelper.putIfNotNull(data, STEP_POD_HEALTH, pods);
        DiagnosticHelper.putIfNotNull(data, STEP_CLUSTER_LOGS, clusterLogs);
        return data;
    }

    private InvestigationAreas parseInvestigationAreas(final SamplingResponse response) {
        try {
            String text = DiagnosticHelper.extractSamplingText(response);
            Map<String, Object> parsed = objectMapper.readValue(text, DiagnosticHelper.MAP_TYPE_REF);
            return new InvestigationAreas(
                Boolean.TRUE.equals(parsed.get(STEP_CERTIFICATES)),
                Boolean.TRUE.equals(parsed.get(STEP_POD_HEALTH)),
                Boolean.TRUE.equals(parsed.get(STEP_CLUSTER_LOGS))
            );
        } catch (Exception e) {
            LOG.debugf("Could not parse triage response, investigating all: %s", e.getMessage());
            return InvestigationAreas.all();
        }
    }

    /**
     * Flags indicating which investigation areas the LLM recommended.
     *
     * @param certificates whether to gather TLS certificates and authentication
     * @param pods         whether to gather pod health
     * @param clusterLogs  whether to gather connection-related logs
     */
    record InvestigationAreas(boolean certificates, boolean pods, boolean clusterLogs) {

        /**
         * Returns areas with all flags set to true (fallback when Sampling is unavailable).
         *
         * @return investigation areas with all flags enabled
         */
        static InvestigationAreas all() {
            return new InvestigationAreas(true, true, true);
        }

        int count() {
            int c = 0;
            if (certificates) c++;
            if (pods) c++;
            if (clusterLogs) c++;
            return c;
        }
    }

    // ---- Sampling system prompts ----

    static final String TRIAGE_SYSTEM_PROMPT = """
        You are a Kafka connectivity diagnostics assistant. \
        Analyze the cluster status and listener configuration to decide which areas \
        need deeper investigation. \
        Return ONLY a JSON object with these boolean fields: \
        certificates, pod_health, cluster_logs. \
        Set true only for areas likely to reveal connectivity issues. \
        For example, if listeners use TLS, set certificates to true. \
        If the cluster is NotReady, set pod_health to true. \
        If listeners look correct but connectivity fails, set cluster_logs to true.\
        """;

    static final String ANALYSIS_SYSTEM_PROMPT = """
        You are troubleshooting Kafka cluster connectivity. Analyze all gathered data \
        and produce a concise connectivity analysis.
        
        For each listener, determine:
        - Connection protocol (PLAINTEXT, SSL, SASL_PLAINTEXT, SASL_SSL)
        - Whether the listener is accessible based on type (internal, route, loadbalancer, nodeport, ingress)
        - Any blocking issues (expired certs, auth misconfiguration, pods not ready)
        
        Common connectivity issues:
        1. TLS certificate expired or about to expire (< 30 days)
        2. Authentication type mismatch (client uses wrong auth method)
        3. Listener type mismatch (client tries internal address from outside cluster)
        4. Broker pods not ready (clients cannot connect regardless of config)
        5. Port conflicts or listener binding failures
        6. SSL/TLS handshake failures (wrong truststore, protocol mismatch)
        
        Structure your response as:
        - Issue summary (what is preventing connectivity, or "no issues found")
        - Affected listeners (which listeners have problems)
        - Root cause (most likely cause)
        - Recommendations (specific steps to fix, with example client properties)
        - Example client configuration for working listener(s)\
        """;
}
