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
import io.streamshub.mcp.common.service.DiagnosticHelper;
import io.streamshub.mcp.common.util.InputUtils;
import io.streamshub.mcp.common.util.NamespaceElicitationHelper;
import io.streamshub.mcp.strimzi.dto.KafkaConfigComparisonReport;
import io.streamshub.mcp.strimzi.dto.KafkaEffectiveConfigResponse;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Orchestrates a configuration comparison workflow for two Kafka clusters.
 *
 * <p>Gathers the effective configuration for both clusters, then optionally
 * uses Sampling to get LLM analysis of the differences. Individual step
 * failures do not abort the workflow.</p>
 */
@ApplicationScoped
public class KafkaConfigComparisonService {

    private static final Logger LOG = Logger.getLogger(KafkaConfigComparisonService.class);
    private static final int TOTAL_STEPS = 2;
    private static final String DIAGNOSTIC_LABEL = "Kafka cluster comparison";
    private static final String STEP_CLUSTER1_CONFIG = "cluster1_config";
    private static final String STEP_CLUSTER2_CONFIG = "cluster2_config";

    @Inject
    KafkaConfigService kafkaConfigService;

    @Inject
    ObjectMapper objectMapper;

    @ConfigProperty(name = "mcp.sampling.analysis-max-tokens", defaultValue = "1500")
    int analysisMaxTokens;

    KafkaConfigComparisonService() {
    }

    /**
     * Compare the effective configuration of two Kafka clusters.
     *
     * <p>Phase 1 gathers both cluster configs (with Elicitation for namespace
     * disambiguation). Phase 2 uses Sampling to analyze differences. Without
     * Sampling, both configs are returned side-by-side for the caller to compare.</p>
     *
     * @param namespace1   optional namespace for the first cluster
     * @param clusterName1 the first cluster name
     * @param namespace2   optional namespace for the second cluster
     * @param clusterName2 the second cluster name
     * @param sampling     MCP Sampling for LLM analysis
     * @param elicitation  MCP Elicitation for namespace disambiguation
     * @param mcpLog       MCP log for progress notifications
     * @param progress     MCP progress tracking
     * @param cancellation MCP cancellation checking
     * @return a configuration comparison report
     */
    @SuppressWarnings("checkstyle:ParameterNumber")
    public KafkaConfigComparisonReport compare(final String namespace1,
                                               final String clusterName1,
                                               final String namespace2,
                                               final String clusterName2,
                                               final Sampling sampling,
                                               final Elicitation elicitation,
                                               final McpLog mcpLog,
                                               final Progress progress,
                                               final Cancellation cancellation) {
        String ns1 = InputUtils.normalizeInput(namespace1);
        String name1 = InputUtils.normalizeInput(clusterName1);
        String ns2 = InputUtils.normalizeInput(namespace2);
        String name2 = InputUtils.normalizeInput(clusterName2);

        if (name1 == null) {
            throw new ToolCallException("First cluster name is required");
        }
        if (name2 == null) {
            throw new ToolCallException("Second cluster name is required");
        }

        LOG.infof("Starting comparison: cluster1=%s (ns=%s), cluster2=%s (ns=%s)",
            name1, ns1 != null ? ns1 : "auto", name2, ns2 != null ? ns2 : "auto");

        List<String> completed = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        int stepIndex = 0;

        // Phase 1: Gather configs
        KafkaEffectiveConfigResponse config1 = gatherConfig(
            ns1, name1, "diagnosed (cluster 1)", elicitation, completed, failed, mcpLog);
        DiagnosticHelper.sendProgress(progress, ++stepIndex, TOTAL_STEPS, DIAGNOSTIC_LABEL);
        DiagnosticHelper.checkCancellation(cancellation);

        KafkaEffectiveConfigResponse config2 = gatherConfig(
            ns2, name2, "diagnosed (cluster 2)", elicitation, completed, failed, mcpLog);
        DiagnosticHelper.sendProgress(progress, ++stepIndex, TOTAL_STEPS, DIAGNOSTIC_LABEL);
        DiagnosticHelper.checkCancellation(cancellation);

        // Phase 2: Sampling analysis
        String analysis = produceAnalysis(sampling, config1, config2);

        return KafkaConfigComparisonReport.of(config1, config2, analysis,
            completed, failed.isEmpty() ? null : failed);
    }

    private KafkaEffectiveConfigResponse gatherConfig(final String namespace,
                                                      final String clusterName,
                                                      final String elicitationContext,
                                                      final Elicitation elicitation,
                                                      final List<String> completed,
                                                      final List<String> failed,
                                                      final McpLog mcpLog) {
        String step = clusterName.equals(completed.isEmpty() ? "" : "x")
            ? STEP_CLUSTER1_CONFIG : (completed.isEmpty() ? STEP_CLUSTER1_CONFIG : STEP_CLUSTER2_CONFIG);

        try {
            KafkaEffectiveConfigResponse result = kafkaConfigService.getEffectiveConfig(namespace, clusterName);
            completed.add(step);
            DiagnosticHelper.sendClientNotification(mcpLog,
                String.format("Gathered configuration for Kafka cluster '%s'", result.name()));
            return result;
        } catch (ToolCallException e) {
            if (NamespaceElicitationHelper.isMultipleNamespacesError(e)
                    && elicitation != null && elicitation.isSupported()) {
                String resolved = NamespaceElicitationHelper.elicitNamespace(e, elicitation, elicitationContext);
                return gatherConfigResolved(resolved, clusterName, step, completed, failed, mcpLog);
            }
            throw e;
        }
    }

    private KafkaEffectiveConfigResponse gatherConfigResolved(final String namespace,
                                                              final String clusterName,
                                                              final String step,
                                                              final List<String> completed,
                                                              final List<String> failed,
                                                              final McpLog mcpLog) {
        try {
            KafkaEffectiveConfigResponse result = kafkaConfigService.getEffectiveConfig(namespace, clusterName);
            completed.add(step);
            DiagnosticHelper.sendClientNotification(mcpLog,
                String.format("Gathered configuration for Kafka cluster '%s'", result.name()));
            return result;
        } catch (Exception e) {
            LOG.warnf("Failed to gather config for cluster '%s': %s", clusterName, e.getMessage());
            failed.add(step + ": " + e.getMessage());
            return null;
        }
    }

    private String produceAnalysis(final Sampling sampling,
                                   final KafkaEffectiveConfigResponse config1,
                                   final KafkaEffectiveConfigResponse config2) {
        if (sampling == null || !sampling.isSupported()) {
            return null;
        }
        if (config1 == null || config2 == null) {
            return null;
        }

        try {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("cluster1", config1);
            data.put("cluster2", config2);
            String dataJson = objectMapper.writeValueAsString(data);

            SamplingResponse response = sampling.requestBuilder()
                .setSystemPrompt(COMPARISON_SYSTEM_PROMPT)
                .addMessage(SamplingMessage.withUserRole(dataJson))
                .setMaxTokens(analysisMaxTokens)
                .build()
                .sendAndAwait();

            return DiagnosticHelper.extractSamplingText(response);
        } catch (Exception e) {
            LOG.warnf("Sampling comparison analysis failed: %s: %s",
                e.getClass().getSimpleName(), e.getMessage());
            return null;
        }
    }

    static final String COMPARISON_SYSTEM_PROMPT = """
        You are comparing two Kafka cluster configurations. Analyze both configurations \
        and identify all significant differences.

        Categorize differences by impact level:
        1. CRITICAL: Differences that affect data safety or availability \
        (replication factors, min.insync.replicas, authorization, storage)
        2. HIGH: Differences that affect performance or operational behavior \
        (resources, JVM options, listener configuration, Cruise Control settings)
        3. MEDIUM: Differences that affect monitoring or observability \
        (metrics configuration, logging, Kafka Exporter settings)
        4. LOW: Cosmetic or environment-specific differences \
        (maintenance windows, rack awareness topology key, namespace)

        For each difference found:
        - Name the configuration area
        - Show cluster 1 value vs cluster 2 value
        - Explain the operational impact

        Structure your response as:
        - Summary (one sentence overview)
        - Critical differences (if any)
        - High-impact differences (if any)
        - Medium-impact differences (if any)
        - Low-impact differences (if any)
        - Recommendations (prioritized actions to align the clusters, if needed)\
        """;
}
