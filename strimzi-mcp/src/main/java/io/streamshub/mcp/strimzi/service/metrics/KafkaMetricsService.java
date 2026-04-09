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
import io.streamshub.mcp.strimzi.config.metrics.KafkaMetricCategories;
import io.streamshub.mcp.strimzi.dto.metrics.KafkaMetricsResponse;
import io.streamshub.mcp.strimzi.service.KafkaService;
import io.streamshub.mcp.strimzi.util.MetricNameResolver;
import io.streamshub.mcp.strimzi.util.TimeRangeValidator;
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
 * Service for retrieving Kafka cluster metrics via pluggable providers.
 */
@ApplicationScoped
public class KafkaMetricsService {

    private static final Logger LOG = Logger.getLogger(KafkaMetricsService.class);
    private static final String DEFAULT_CATEGORY = "replication";

    @Inject
    KubernetesResourceService k8sService;

    @Inject
    MetricsQueryService metricsQueryService;

    @Inject
    KafkaService kafkaService;

    KafkaMetricsService() {
        // package-private no-arg constructor for CDI
    }

    /**
     * Retrieves metrics for a Kafka cluster.
     *
     * @param namespace    the namespace (optional, null for auto-discovery)
     * @param clusterName  the Kafka cluster name (required)
     * @param category     the metric category (optional, defaults to "replication")
     * @param metricNames  explicit metric names (optional, merged with category)
     * @param rangeMinutes range query duration in minutes (optional, null for instant)
     * @param startTime    absolute start time in ISO 8601 format (optional, use with endTime)
     * @param endTime      absolute end time in ISO 8601 format (optional, use with startTime)
     * @param stepSeconds  range query step interval (optional, uses default)
     * @return the metrics response
     */
    @SuppressWarnings("checkstyle:ParameterNumber")
    public KafkaMetricsResponse getKafkaMetrics(final String namespace,
                                                 final String clusterName,
                                                 final String category,
                                                 final String metricNames,
                                                 final Integer rangeMinutes,
                                                 final String startTime,
                                                 final String endTime,
                                                 final Integer stepSeconds) {
        String ns = InputUtils.normalizeInput(namespace);
        String name = InputUtils.normalizeInput(clusterName);
        String cat = InputUtils.normalizeInput(category);

        if (name == null) {
            throw new ToolCallException("Cluster name is required");
        }

        // Validate time range parameters
        TimeRangeValidator.validateTimeRangeParameters(rangeMinutes, startTime, endTime);

        // Resolve metric names from category + explicit names
        List<String> resolvedMetrics = MetricNameResolver.resolve(
            cat, metricNames, DEFAULT_CATEGORY,
            KafkaMetricCategories::resolve, KafkaMetricCategories.allCategories());
        List<String> categories = new ArrayList<>();
        if (cat != null) {
            categories.add(cat);
        }

        // Find the Kafka cluster
        Kafka kafka = kafkaService.findKafkaCluster(ns, name);
        String resolvedNs = kafka.getMetadata().getNamespace();

        LOG.infof("Getting metrics for cluster '%s' in namespace '%s' (provider=%s)",
            name, resolvedNs, metricsQueryService.providerName());

        // Find Kafka pods
        List<Pod> pods = k8sService.queryResourcesByLabel(
            Pod.class, resolvedNs, ResourceLabels.STRIMZI_CLUSTER_LABEL, name);

        if (pods.isEmpty()) {
            return KafkaMetricsResponse.empty(name, resolvedNs,
                String.format("No Kafka pods found for cluster '%s' in namespace '%s'",
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
        String interpretation = KafkaMetricCategories.interpretation(effectiveCategories);

        return KafkaMetricsResponse.of(name, resolvedNs,
            metricsQueryService.providerName(), categories, samples, interpretation);
    }
}
