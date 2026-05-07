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
import io.streamshub.mcp.strimzi.config.metrics.KafkaConnectMetricCategories;
import io.streamshub.mcp.strimzi.dto.metrics.KafkaConnectMetricsResponse;
import io.streamshub.mcp.strimzi.service.kafkaconnect.KafkaConnectService;
import io.streamshub.mcp.strimzi.util.MetricNameResolver;
import io.strimzi.api.ResourceLabels;
import io.strimzi.api.kafka.model.connect.KafkaConnect;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Service for retrieving KafkaConnect metrics via pluggable providers.
 * KafkaConnect exposes worker, connector task, source, sink,
 * and JVM metrics — distinct from Kafka broker JMX metrics.
 */
@ApplicationScoped
public class KafkaConnectMetricsService {

    private static final Logger LOG = Logger.getLogger(KafkaConnectMetricsService.class);
    private static final String DEFAULT_CATEGORY = KafkaConnectMetricCategories.WORKER;

    @Inject
    KubernetesResourceService k8sService;

    @Inject
    MetricsQueryService metricsQueryService;

    @Inject
    KafkaConnectService kafkaConnectService;

    KafkaConnectMetricsService() {
    }

    /**
     * Retrieves metrics from KafkaConnect pods.
     *
     * @param namespace    the namespace (optional, null for auto-discovery)
     * @param connectName  the KafkaConnect cluster name (required)
     * @param category     the metric category (optional, defaults to "worker")
     * @param metricNames  explicit metric names (optional, merged with category)
     * @param rangeMinutes range query duration in minutes (optional, null for instant)
     * @param startTime    absolute start time in ISO 8601 format (optional, use with endTime)
     * @param endTime      absolute end time in ISO 8601 format (optional, use with startTime)
     * @param stepSeconds  range query step interval (optional, uses default)
     * @param aggregation  aggregation level (optional, defaults to "broker")
     * @return the KafkaConnect metrics response
     */
    @SuppressWarnings("checkstyle:ParameterNumber")
    public KafkaConnectMetricsResponse getKafkaConnectMetrics(final String namespace,
                                                               final String connectName,
                                                               final String category,
                                                               final String metricNames,
                                                               final Integer rangeMinutes,
                                                               final String startTime,
                                                               final String endTime,
                                                               final Integer stepSeconds,
                                                               final String aggregation) {
        String ns = InputUtils.normalizeInput(namespace);
        String name = InputUtils.normalizeInput(connectName);
        String cat = InputUtils.normalizeInput(category);

        if (name == null) {
            throw new ToolCallException("KafkaConnect name is required");
        }

        TimeRangeValidator.validateTimeRangeParameters(rangeMinutes, startTime, endTime);

        List<String> resolvedMetrics = MetricNameResolver.resolve(
            cat, metricNames, DEFAULT_CATEGORY,
            KafkaConnectMetricCategories::resolve, KafkaConnectMetricCategories.allCategories());
        List<String> categories = new ArrayList<>();
        if (cat != null) {
            categories.add(cat);
        }

        KafkaConnect connect = kafkaConnectService.findKafkaConnect(ns, name);
        String resolvedNs = connect.getMetadata().getNamespace();

        LOG.infof("Getting KafkaConnect metrics for cluster '%s' in namespace '%s' (provider=%s)",
            name, resolvedNs, metricsQueryService.providerName());

        Map<String, String> podLabels = Map.of(
            ResourceLabels.STRIMZI_CLUSTER_LABEL, name,
            ResourceLabels.STRIMZI_KIND_LABEL, StrimziConstants.KindValues.KAFKA_CONNECT);
        List<Pod> pods = k8sService.queryResourcesByLabels(Pod.class, resolvedNs, podLabels);

        LOG.debugf("Found %d KafkaConnect pod(s) for cluster '%s': %s",
            pods.size(), name,
            pods.stream().map(p -> p.getMetadata().getName()).toList());

        if (pods.isEmpty()) {
            return KafkaConnectMetricsResponse.empty(name, resolvedNs,
                String.format("No KafkaConnect pods found for cluster '%s' in namespace '%s'",
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
        String interpretation = KafkaConnectMetricCategories.interpretation(effectiveCategories);

        AggregationLevel level = AggregationLevel.fromString(aggregation);
        if (cat != null || metricNames == null || metricNames.isBlank()) {
            String effectiveCat = cat != null ? cat : DEFAULT_CATEGORY;
            level = level.clampTo(KafkaConnectMetricCategories.maxGranularity(effectiveCat));
        }
        return KafkaConnectMetricsResponse.of(name, resolvedNs,
            metricsQueryService.providerName(), categories, samples, interpretation, level);
    }
}
