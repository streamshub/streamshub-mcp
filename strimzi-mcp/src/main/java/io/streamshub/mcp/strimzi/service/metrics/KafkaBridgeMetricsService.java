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
import io.streamshub.mcp.common.service.KubernetesResourceService;
import io.streamshub.mcp.common.service.metrics.MetricsQueryService;
import io.streamshub.mcp.common.util.InputUtils;
import io.streamshub.mcp.common.util.TimeRangeValidator;
import io.streamshub.mcp.strimzi.config.StrimziConstants;
import io.streamshub.mcp.strimzi.config.metrics.KafkaBridgeMetricCategories;
import io.streamshub.mcp.strimzi.dto.metrics.KafkaBridgeMetricsResponse;
import io.streamshub.mcp.strimzi.service.kafkabridge.KafkaBridgeService;
import io.streamshub.mcp.strimzi.util.MetricNameResolver;
import io.strimzi.api.ResourceLabels;
import io.strimzi.api.kafka.model.bridge.KafkaBridge;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Service for retrieving KafkaBridge metrics via pluggable providers.
 * KafkaBridge exposes HTTP request metrics, Kafka producer/consumer metrics,
 * and JVM metrics — distinct from Kafka broker JMX metrics.
 */
@ApplicationScoped
public class KafkaBridgeMetricsService {

    private static final Logger LOG = Logger.getLogger(KafkaBridgeMetricsService.class);
    private static final String DEFAULT_CATEGORY = KafkaBridgeMetricCategories.HTTP;

    @Inject
    KubernetesResourceService k8sService;

    @Inject
    MetricsQueryService metricsQueryService;

    @Inject
    KafkaBridgeService kafkaBridgeService;

    KafkaBridgeMetricsService() {
    }

    /**
     * Retrieves metrics from KafkaBridge pods.
     *
     * @param namespace    the namespace (optional, null for auto-discovery)
     * @param bridgeName   the KafkaBridge name (required)
     * @param category     the metric category (optional, defaults to "http")
     * @param metricNames  explicit metric names (optional, merged with category)
     * @param rangeMinutes range query duration in minutes (optional, null for instant)
     * @param startTime    absolute start time in ISO 8601 format (optional, use with endTime)
     * @param endTime      absolute end time in ISO 8601 format (optional, use with startTime)
     * @param stepSeconds  range query step interval (optional, uses default)
     * @param aggregation  aggregation level (optional, defaults to "broker")
     * @return the KafkaBridge metrics response
     */
    @SuppressWarnings("checkstyle:ParameterNumber")
    public KafkaBridgeMetricsResponse getKafkaBridgeMetrics(final String namespace,
                                                              final String bridgeName,
                                                              final String category,
                                                              final String metricNames,
                                                              final Integer rangeMinutes,
                                                              final String startTime,
                                                              final String endTime,
                                                              final Integer stepSeconds,
                                                              final String aggregation) {
        String ns = InputUtils.normalizeInput(namespace);
        String name = InputUtils.normalizeInput(bridgeName);
        String cat = InputUtils.normalizeInput(category);

        if (name == null) {
            throw new ToolCallException("KafkaBridge name is required");
        }

        TimeRangeValidator.validateTimeRangeParameters(rangeMinutes, startTime, endTime);

        List<String> resolvedMetrics = MetricNameResolver.resolve(
            cat, metricNames, DEFAULT_CATEGORY,
            KafkaBridgeMetricCategories::resolve, KafkaBridgeMetricCategories.allCategories());
        List<String> categories = new ArrayList<>();
        if (cat != null) {
            categories.add(cat);
        }

        KafkaBridge bridge = kafkaBridgeService.findKafkaBridge(ns, name);
        String resolvedNs = bridge.getMetadata().getNamespace();

        LOG.infof("Getting KafkaBridge metrics for bridge '%s' in namespace '%s' (provider=%s)",
            name, resolvedNs, metricsQueryService.providerName());

        List<Pod> pods = k8sService.queryResourcesByLabel(
                Pod.class, resolvedNs, ResourceLabels.STRIMZI_CLUSTER_LABEL, name)
            .stream()
            .filter(pod -> {
                Map<String, String> labels = pod.getMetadata().getLabels();
                if (labels == null) {
                    return false;
                }
                String componentType = labels.get(ResourceLabels.STRIMZI_COMPONENT_TYPE_LABEL);
                return StrimziConstants.ComponentTypes.KAFKA_BRIDGE.equals(componentType);
            })
            .toList();

        LOG.debugf("Found %d KafkaBridge pod(s) for bridge '%s': %s",
            pods.size(), name,
            pods.stream().map(p -> p.getMetadata().getName()).toList());

        if (pods.isEmpty()) {
            return KafkaBridgeMetricsResponse.empty(name, resolvedNs,
                String.format("No KafkaBridge pods found for bridge '%s' in namespace '%s'",
                    name, resolvedNs));
        }

        List<PodTarget> podTargets = pods.stream()
            .map(pod -> PodTarget.of(
                pod.getMetadata().getNamespace(),
                pod.getMetadata().getName()))
            .toList();

        Map<String, String> labelMatchers = new LinkedHashMap<>();
        labelMatchers.put("namespace", resolvedNs);
        labelMatchers.put("strimzi_io_cluster", name);

        List<MetricSample> samples = metricsQueryService.queryMetrics(
            podTargets, labelMatchers, resolvedMetrics, rangeMinutes, startTime, endTime, stepSeconds);

        List<String> effectiveCategories = new ArrayList<>(categories);
        if (effectiveCategories.isEmpty() && (metricNames == null || metricNames.isBlank())) {
            effectiveCategories.add(DEFAULT_CATEGORY);
        }
        String interpretation = KafkaBridgeMetricCategories.interpretation(effectiveCategories);

        AggregationLevel level = AggregationLevel.fromString(aggregation);
        if (cat != null || metricNames == null || metricNames.isBlank()) {
            String effectiveCat = cat != null ? cat : DEFAULT_CATEGORY;
            level = level.clampTo(KafkaBridgeMetricCategories.maxGranularity(effectiveCat));
        }
        return KafkaBridgeMetricsResponse.of(name, resolvedNs,
            metricsQueryService.providerName(), categories, samples, interpretation, level);
    }
}
