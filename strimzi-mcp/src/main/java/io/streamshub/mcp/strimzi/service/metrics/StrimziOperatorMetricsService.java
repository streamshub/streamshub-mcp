/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.service.metrics;

import io.fabric8.kubernetes.api.model.Pod;
import io.quarkiverse.mcp.server.ToolCallException;
import io.streamshub.mcp.common.dto.metrics.MetricSample;
import io.streamshub.mcp.common.dto.metrics.PodTarget;
import io.streamshub.mcp.common.service.KubernetesResourceService;
import io.streamshub.mcp.common.service.metrics.MetricsQueryService;
import io.streamshub.mcp.common.util.InputUtils;
import io.streamshub.mcp.strimzi.config.StrimziConstants;
import io.streamshub.mcp.strimzi.config.metrics.StrimziOperatorMetricCategories;
import io.streamshub.mcp.strimzi.dto.metrics.StrimziOperatorMetricsResponse;
import io.strimzi.api.ResourceLabels;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Service for retrieving Strimzi cluster operator metrics via pluggable providers.
 */
@ApplicationScoped
public class StrimziOperatorMetricsService {

    private static final Logger LOG = Logger.getLogger(StrimziOperatorMetricsService.class);
    private static final String DEFAULT_CATEGORY = "reconciliation";

    @Inject
    KubernetesResourceService k8sService;

    @Inject
    MetricsQueryService metricsQueryService;

    StrimziOperatorMetricsService() {
        // package-private no-arg constructor for CDI
    }

    /**
     * Retrieves metrics from Strimzi cluster operator pods.
     *
     * @param namespace    the namespace (optional, null for auto-discovery)
     * @param operatorName the operator deployment name (optional, null for any operator)
     * @param category     the metric category (optional, defaults to "reconciliation")
     * @param metricNames  explicit metric names (optional, merged with category)
     * @param rangeMinutes range query duration in minutes (optional, null for instant)
     * @param stepSeconds  range query step interval (optional, uses default)
     * @return the operator metrics response
     */
    @SuppressWarnings("checkstyle:ParameterNumber")
    public StrimziOperatorMetricsResponse getOperatorMetrics(final String namespace,
                                                       final String operatorName,
                                                       final String category,
                                                       final String metricNames,
                                                       final Integer rangeMinutes,
                                                       final Integer stepSeconds) {
        String ns = InputUtils.normalizeInput(namespace);
        String name = InputUtils.normalizeInput(operatorName);
        String cat = InputUtils.normalizeInput(category);

        // Resolve metric names from category + explicit names
        List<String> resolvedMetrics = resolveMetricNames(cat, metricNames);
        List<String> categories = new ArrayList<>();
        if (cat != null) {
            categories.add(cat);
        }

        // Find operator pods
        List<Pod> pods = findOperatorPods(ns, name);
        String resolvedNs = pods.getFirst().getMetadata().getNamespace();
        String resolvedName = name != null ? name : "cluster-operator";

        LOG.infof("Getting metrics for operator '%s' in namespace '%s' (provider=%s)",
            resolvedName, resolvedNs, metricsQueryService.providerName());

        // Build pod targets and label matchers
        List<PodTarget> podTargets = pods.stream()
            .map(pod -> PodTarget.of(
                pod.getMetadata().getNamespace(),
                pod.getMetadata().getName()))
            .toList();

        Map<String, String> labelMatchers = new LinkedHashMap<>();
        labelMatchers.put("namespace", resolvedNs);

        // Query metrics via general service
        List<MetricSample> samples = metricsQueryService.queryMetrics(
            podTargets, labelMatchers, resolvedMetrics, rangeMinutes, stepSeconds);

        // Build interpretation from effective categories
        List<String> effectiveCategories = new ArrayList<>(categories);
        if (effectiveCategories.isEmpty() && (metricNames == null || metricNames.isBlank())) {
            effectiveCategories.add(DEFAULT_CATEGORY);
        }
        String interpretation = StrimziOperatorMetricCategories.interpretation(effectiveCategories);

        return StrimziOperatorMetricsResponse.of(resolvedName, resolvedNs,
            metricsQueryService.providerName(), categories, samples, interpretation);
    }

    private List<String> resolveMetricNames(final String category, final String metricNames) {
        List<String> resolved = new ArrayList<>();

        String effectiveCategory = category;
        if (effectiveCategory == null && (metricNames == null || metricNames.isBlank())) {
            effectiveCategory = DEFAULT_CATEGORY;
        }

        if (effectiveCategory != null) {
            List<String> categoryMetrics = StrimziOperatorMetricCategories.resolve(effectiveCategory);
            if (categoryMetrics.isEmpty() && category != null) {
                throw new ToolCallException(
                    String.format("Unknown metric category '%s'. Available: %s",
                        category, StrimziOperatorMetricCategories.allCategories()));
            }
            resolved.addAll(categoryMetrics);
        }

        if (metricNames != null && !metricNames.isBlank()) {
            for (String metric : metricNames.split(",")) {
                String trimmed = metric.trim();
                if (!trimmed.isEmpty() && !resolved.contains(trimmed)) {
                    resolved.add(trimmed);
                }
            }
        }

        return resolved;
    }

    private List<Pod> findOperatorPods(final String namespace, final String operatorName) {
        List<Pod> pods;

        if (namespace != null) {
            pods = k8sService.queryResourcesByLabel(
                Pod.class, namespace,
                ResourceLabels.STRIMZI_KIND_LABEL, StrimziConstants.KindValues.CLUSTER_OPERATOR);
        } else {
            pods = k8sService.queryResourcesByLabelInAnyNamespace(
                Pod.class,
                ResourceLabels.STRIMZI_KIND_LABEL, StrimziConstants.KindValues.CLUSTER_OPERATOR);
        }

        // Filter by operator name if specified (pod names start with the deployment name)
        if (operatorName != null) {
            pods = pods.stream()
                .filter(pod -> pod.getMetadata().getName().startsWith(operatorName))
                .toList();
        }

        if (pods.isEmpty()) {
            String location = namespace != null
                ? "in namespace '" + namespace + "'"
                : "in any namespace";

            if (operatorName != null) {
                throw new ToolCallException(
                    "No Strimzi operator pods found for '" + operatorName + "' " + location);
            } else {
                throw new ToolCallException(
                    "No Strimzi operator pods found " + location);
            }
        }

        // Verify all pods are in the same namespace
        List<String> namespaces = pods.stream()
            .map(pod -> pod.getMetadata().getNamespace())
            .distinct()
            .toList();

        if (namespaces.size() > 1) {
            throw new ToolCallException(
                "Strimzi operator pods found in multiple namespaces: "
                    + String.join(", ", namespaces) + ". Please specify a namespace.");
        }

        return pods;
    }
}
