/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.service.metrics;

import io.fabric8.kubernetes.api.model.Pod;
import io.quarkiverse.mcp.server.ToolCallException;
import io.streamshub.mcp.common.dto.metrics.AggregationLevel;
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
     * @param aggregation  aggregation level (optional, defaults to "broker")
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
                                                       final Integer stepSeconds,
                                                       final String aggregation) {
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

        List<MetricSample> samples = queryByNamespace(
            coPods, eoPods, resolvedMetrics, rangeMinutes, startTime, endTime, stepSeconds);

        String interpretation = buildInterpretation(categories, metricNames);
        String resolvedNs = !coPods.isEmpty()
            ? coPods.getFirst().getMetadata().getNamespace()
            : eoPods.getFirst().getMetadata().getNamespace();

        AggregationLevel level = AggregationLevel.fromString(aggregation);
        if (cat != null || metricNames == null || metricNames.isBlank()) {
            String effectiveCat = cat != null ? cat : DEFAULT_CATEGORY;
            level = level.clampTo(StrimziOperatorMetricCategories.maxGranularity(effectiveCat));
        }
        return StrimziOperatorMetricsResponse.of(resolvedName, cluster, resolvedNs,
            metricsQueryService.providerName(), categories, samples, interpretation, level);
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

    /**
     * Queries CO and EO metrics separately to handle different namespaces and
     * avoid label mismatches (operator metrics don't carry strimzi_io_cluster).
     */
    private List<MetricSample> queryByNamespace(final List<Pod> coPods, final List<Pod> eoPods,
                                                 final List<String> metricNames,
                                                 final Integer rangeMinutes, final String startTime,
                                                 final String endTime, final Integer stepSeconds) {
        String coNamespace = !coPods.isEmpty()
            ? coPods.getFirst().getMetadata().getNamespace() : null;
        String eoNamespace = !eoPods.isEmpty()
            ? eoPods.getFirst().getMetadata().getNamespace() : null;

        boolean sameNamespace = eoPods.isEmpty() || coPods.isEmpty()
            || coNamespace.equals(eoNamespace);

        if (sameNamespace) {
            List<PodTarget> targets = buildPodTargets(coPods, eoPods);
            Map<String, String> matchers = new LinkedHashMap<>();
            matchers.put("namespace", coNamespace != null ? coNamespace : eoNamespace);
            return metricsQueryService.queryMetrics(
                targets, matchers, metricNames, rangeMinutes, startTime, endTime, stepSeconds);
        }

        List<MetricSample> allSamples = new ArrayList<>();

        List<PodTarget> coTargets = buildPodTargets(coPods, List.of());
        Map<String, String> coMatchers = new LinkedHashMap<>();
        coMatchers.put("namespace", coNamespace);
        allSamples.addAll(metricsQueryService.queryMetrics(
            coTargets, coMatchers, metricNames, rangeMinutes, startTime, endTime, stepSeconds));

        List<PodTarget> eoTargets = buildPodTargets(List.of(), eoPods);
        Map<String, String> eoMatchers = new LinkedHashMap<>();
        eoMatchers.put("namespace", eoNamespace);
        eoMatchers.put("pod", eoPods.getFirst().getMetadata().getName());
        allSamples.addAll(metricsQueryService.queryMetrics(
            eoTargets, eoMatchers, metricNames, rangeMinutes, startTime, endTime, stepSeconds));

        return allSamples;
    }

    private String buildInterpretation(final List<String> categories, final String metricNames) {
        List<String> effectiveCategories = new ArrayList<>(categories);
        if (effectiveCategories.isEmpty() && (metricNames == null || metricNames.isBlank())) {
            effectiveCategories.add(DEFAULT_CATEGORY);
        }
        return StrimziOperatorMetricCategories.interpretation(effectiveCategories);
    }

}
