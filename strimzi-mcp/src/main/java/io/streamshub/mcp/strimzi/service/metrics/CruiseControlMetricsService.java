/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.service.metrics;

import io.fabric8.kubernetes.api.model.Pod;
import io.streamshub.mcp.common.dto.metrics.AggregationLevel;
import io.streamshub.mcp.common.dto.metrics.MetricSample;
import io.streamshub.mcp.common.dto.metrics.PodTarget;
import io.streamshub.mcp.common.service.KubernetesResourceService;
import io.streamshub.mcp.common.service.metrics.MetricsQueryService;
import io.streamshub.mcp.common.util.InputUtils;
import io.streamshub.mcp.common.util.McpErrors;
import io.streamshub.mcp.common.util.TimeRangeValidator;
import io.streamshub.mcp.strimzi.config.StrimziConstants;
import io.streamshub.mcp.strimzi.config.metrics.CruiseControlMetricCategories;
import io.streamshub.mcp.strimzi.dto.metrics.CruiseControlMetricsResponse;
import io.streamshub.mcp.strimzi.service.kafka.KafkaService;
import io.streamshub.mcp.strimzi.util.MetricNameResolver;
import io.strimzi.api.ResourceLabels;
import io.strimzi.api.kafka.model.kafka.Kafka;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Service for retrieving Cruise Control metrics via pluggable providers.
 * Cruise Control exposes sample collection, partition monitoring, and anomaly detection metrics.
 */
@ApplicationScoped
public class CruiseControlMetricsService {

    private static final Logger LOG = Logger.getLogger(CruiseControlMetricsService.class);
    private static final String DEFAULT_CATEGORY = CruiseControlMetricCategories.SAMPLING;

    @Inject
    KubernetesResourceService k8sService;

    @Inject
    MetricsQueryService metricsQueryService;

    @Inject
    KafkaService kafkaService;

    CruiseControlMetricsService() {
        // package-private no-arg constructor for CDI
    }

    /**
     * Retrieves metrics from Cruise Control pods for a cluster.
     *
     * @param namespace    the namespace (optional, null for auto-discovery)
     * @param clusterName  the Kafka cluster name (required)
     * @param category     the metric category (optional, defaults to "sampling")
     * @param metricNames  explicit metric names (optional, merged with category)
     * @param rangeMinutes range query duration in minutes (optional, null for instant)
     * @param startTime    absolute start time in ISO 8601 format (optional, use with endTime)
     * @param endTime      absolute end time in ISO 8601 format (optional, use with startTime)
     * @param stepSeconds  range query step interval (optional, uses default)
     * @param aggregation  aggregation level (optional, defaults to "cluster")
     * @return the Cruise Control metrics response
     */
    @SuppressWarnings("checkstyle:ParameterNumber")
    public CruiseControlMetricsResponse getCruiseControlMetrics(final String namespace,
                                                               final String clusterName,
                                                               final String category,
                                                               final String metricNames,
                                                               final Integer rangeMinutes,
                                                               final String startTime,
                                                               final String endTime,
                                                               final Integer stepSeconds,
                                                               final String aggregation) {
        String ns = InputUtils.normalizeInput(namespace);
        String name = InputUtils.normalizeInput(clusterName);
        String cat = InputUtils.normalizeInput(category);

        if (name == null) {
            throw McpErrors.invalidParams("Cluster name is required");
        }

        // Validate time range parameters
        TimeRangeValidator.validateTimeRangeParameters(rangeMinutes, startTime, endTime);

        // Resolve metric names from category + explicit names
        List<String> resolvedMetrics = MetricNameResolver.resolve(
            cat, metricNames, DEFAULT_CATEGORY,
            CruiseControlMetricCategories::resolve, CruiseControlMetricCategories.allCategories());
        List<String> categories = new ArrayList<>();
        if (cat != null) {
            categories.add(cat);
        }

        // Find the Kafka cluster
        Kafka kafka = kafkaService.findKafkaCluster(ns, name);
        String resolvedNs = kafka.getMetadata().getNamespace();

        LOG.infof("Getting Cruise Control metrics for cluster '%s' in namespace '%s' (provider=%s)",
            name, resolvedNs, metricsQueryService.providerName());

        // Find Cruise Control pods
        Map<String, String> podLabels = Map.of(
            ResourceLabels.STRIMZI_CLUSTER_LABEL, name,
            ResourceLabels.STRIMZI_COMPONENT_TYPE_LABEL, StrimziConstants.ComponentTypes.KAFKA_CRUISE_CONTROL);
        List<Pod> pods = k8sService.queryResourcesByLabels(Pod.class, resolvedNs, podLabels);

        LOG.debugf("Found %d Cruise Control pod(s) for cluster '%s': %s",
            pods.size(), name,
            pods.stream().map(p -> p.getMetadata().getName()).toList());

        if (pods.isEmpty()) {
            return CruiseControlMetricsResponse.empty(name, resolvedNs,
                String.format("No Cruise Control pods found for cluster '%s' in namespace '%s'",
                    name, resolvedNs));
        }

        // Build pod targets and label matchers
        List<PodTarget> podTargets = pods.stream()
            .map(pod -> PodTarget.of(
                pod.getMetadata().getNamespace(),
                pod.getMetadata().getName()))
            .toList();

        Map<String, String> labelMatchers = new LinkedHashMap<>();
        labelMatchers.put("namespace", resolvedNs);
        labelMatchers.put("strimzi_io_cluster", name);

        // Query metrics via general service
        List<MetricSample> samples = metricsQueryService.queryMetrics(
            podTargets, labelMatchers, resolvedMetrics, rangeMinutes, startTime, endTime, stepSeconds);

        // Build interpretation from effective categories
        List<String> effectiveCategories = new ArrayList<>(categories);
        if (effectiveCategories.isEmpty() && (metricNames == null || metricNames.isBlank())) {
            effectiveCategories.add(DEFAULT_CATEGORY);
        }
        String interpretation = MetricNameResolver.alignInterpretation(
            CruiseControlMetricCategories.interpretation(effectiveCategories),
            samples.stream().map(MetricSample::name).toList());

        String effectiveAggCat = (cat != null || metricNames == null || metricNames.isBlank())
            ? (cat != null ? cat : DEFAULT_CATEGORY)
            : null;
        AggregationLevel level = effectiveAggCat != null
            ? AggregationLevel.resolve(aggregation, CruiseControlMetricCategories.maxGranularity(effectiveAggCat))
            : AggregationLevel.fromString(aggregation);
        return CruiseControlMetricsResponse.of(name, resolvedNs,
            metricsQueryService.providerName(), effectiveCategories, samples, interpretation, level);
    }
}
