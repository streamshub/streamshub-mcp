/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.service.metrics;

import io.fabric8.kubernetes.api.model.Pod;
import io.quarkiverse.mcp.server.ToolCallException;
import io.streamshub.mcp.common.dto.metrics.MetricSample;
import io.streamshub.mcp.common.dto.metrics.PodTarget;
import io.streamshub.mcp.common.service.metrics.MetricsQueryService;
import io.streamshub.mcp.common.util.InputUtils;
import io.streamshub.mcp.common.util.TimeRangeValidator;
import io.streamshub.mcp.strimzi.config.StrimziConstants;
import io.streamshub.mcp.strimzi.config.metrics.StrimziOperatorMetricCategories;
import io.streamshub.mcp.strimzi.dto.metrics.StrimziOperatorMetricsResponse;
import io.streamshub.mcp.strimzi.service.StrimziOperatorService;
import io.streamshub.mcp.strimzi.util.MetricNameResolver;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Service for retrieving Strimzi operator metrics via pluggable providers.
 * Supports cluster operator pods and, when a cluster name is provided,
 * entity operator pods (user-operator and topic-operator).
 */
@ApplicationScoped
public class StrimziOperatorMetricsService {

    private static final Logger LOG = Logger.getLogger(StrimziOperatorMetricsService.class);
    private static final String DEFAULT_CATEGORY = StrimziOperatorMetricCategories.RECONCILIATION;

    @Inject
    MetricsQueryService metricsQueryService;

    @Inject
    StrimziOperatorService strimziOperatorService;

    StrimziOperatorMetricsService() {
        // package-private no-arg constructor for CDI
    }

    /**
     * Retrieves metrics from Strimzi operator pods.
     * When {@code clusterName} is provided, also includes entity operator
     * (user-operator and topic-operator) metrics for that cluster.
     *
     * @param namespace    the namespace (optional, null for auto-discovery)
     * @param operatorName the operator deployment name (optional, null for any operator)
     * @param clusterName  the Kafka cluster name (optional, includes entity operator metrics when set)
     * @param category     the metric category (optional, defaults to "reconciliation")
     * @param metricNames  explicit metric names (optional, merged with category)
     * @param rangeMinutes range query duration in minutes (optional, null for instant)
     * @param startTime    absolute start time in ISO 8601 format (optional, use with endTime)
     * @param endTime      absolute end time in ISO 8601 format (optional, use with startTime)
     * @param stepSeconds  range query step interval (optional, uses default)
     * @return the operator metrics response
     */
    @SuppressWarnings("checkstyle:ParameterNumber")
    public StrimziOperatorMetricsResponse getOperatorMetrics(final String namespace,
                                                       final String operatorName,
                                                       final String clusterName,
                                                       final String category,
                                                       final String metricNames,
                                                       final Integer rangeMinutes,
                                                       final String startTime,
                                                       final String endTime,
                                                       final Integer stepSeconds) {
        String ns = InputUtils.normalizeInput(namespace);
        String name = InputUtils.normalizeInput(operatorName);
        String cluster = InputUtils.normalizeInput(clusterName);
        String cat = InputUtils.normalizeInput(category);

        // Validate time range parameters
        TimeRangeValidator.validateTimeRangeParameters(rangeMinutes, startTime, endTime);

        // Resolve metric names from category + explicit names
        List<String> resolvedMetrics = MetricNameResolver.resolve(
            cat, metricNames, DEFAULT_CATEGORY,
            StrimziOperatorMetricCategories::resolve, StrimziOperatorMetricCategories.allCategories());
        List<String> categories = new ArrayList<>();
        if (cat != null) {
            categories.add(cat);
        }

        // Find all operator pods
        List<Pod> coPods = strimziOperatorService.findClusterOperatorPods(ns, name);
        List<Pod> eoPods = cluster != null ? strimziOperatorService.findEntityOperatorPods(ns, cluster) : List.of();
        validatePodsFound(coPods, eoPods, ns);

        String resolvedName = name != null ? name : "cluster-operator";
        LOG.infof("Getting metrics for operator '%s'%s (provider=%s)",
            resolvedName,
            cluster != null ? " with entity operator for Kafka cluster '" + cluster + "'" : "",
            metricsQueryService.providerName());

        List<PodTarget> podTargets = buildPodTargets(coPods, eoPods);
        Map<String, String> labelMatchers = buildLabelMatchers(coPods, eoPods, cluster);

        List<MetricSample> samples = metricsQueryService.queryMetrics(
            podTargets, labelMatchers, resolvedMetrics, rangeMinutes, startTime, endTime, stepSeconds);

        String interpretation = buildInterpretation(categories, metricNames);
        String resolvedNs = !coPods.isEmpty()
            ? coPods.getFirst().getMetadata().getNamespace()
            : eoPods.getFirst().getMetadata().getNamespace();

        return StrimziOperatorMetricsResponse.of(resolvedName, cluster, resolvedNs,
            metricsQueryService.providerName(), categories, samples, interpretation);
    }

    private void validatePodsFound(final List<Pod> coPods, final List<Pod> eoPods, final String namespace) {
        if (coPods.isEmpty() && eoPods.isEmpty()) {
            String location = namespace != null
                ? "in namespace '" + namespace + "'"
                : "in any namespace";
            throw new ToolCallException("No Strimzi operator pods found " + location);
        }
    }

    private List<PodTarget> buildPodTargets(final List<Pod> coPods, final List<Pod> eoPods) {
        List<PodTarget> targets = new ArrayList<>();
        for (Pod pod : coPods) {
            targets.add(PodTarget.of(
                pod.getMetadata().getNamespace(),
                pod.getMetadata().getName()));
        }
        for (Pod pod : eoPods) {
            targets.add(PodTarget.of(
                pod.getMetadata().getNamespace(),
                pod.getMetadata().getName(),
                StrimziConstants.EntityOperator.USER_OPERATOR_PORT,
                PodTarget.DEFAULT_PATH));
            targets.add(PodTarget.of(
                pod.getMetadata().getNamespace(),
                pod.getMetadata().getName(),
                StrimziConstants.EntityOperator.TOPIC_OPERATOR_PORT,
                PodTarget.DEFAULT_PATH));
        }
        return targets;
    }

    private Map<String, String> buildLabelMatchers(final List<Pod> coPods, final List<Pod> eoPods,
                                                    final String cluster) {
        Map<String, String> matchers = new LinkedHashMap<>();
        String ns = !coPods.isEmpty()
            ? coPods.getFirst().getMetadata().getNamespace()
            : eoPods.getFirst().getMetadata().getNamespace();
        matchers.put("namespace", ns);
        if (cluster != null) {
            matchers.put("strimzi_io_cluster", cluster);
        }
        return matchers;
    }

    private String buildInterpretation(final List<String> categories, final String metricNames) {
        List<String> effectiveCategories = new ArrayList<>(categories);
        if (effectiveCategories.isEmpty() && (metricNames == null || metricNames.isBlank())) {
            effectiveCategories.add(DEFAULT_CATEGORY);
        }
        return StrimziOperatorMetricCategories.interpretation(effectiveCategories);
    }

}
