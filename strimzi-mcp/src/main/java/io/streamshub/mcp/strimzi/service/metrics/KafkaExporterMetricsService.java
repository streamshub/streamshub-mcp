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
import io.streamshub.mcp.strimzi.config.metrics.KafkaExporterMetricCategories;
import io.streamshub.mcp.strimzi.dto.metrics.KafkaExporterMetricsResponse;
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
 * Service for retrieving Kafka Exporter metrics via pluggable providers.
 * Kafka Exporter exposes consumer group lag, topic partition offsets,
 * and JVM metrics — distinct from broker JMX metrics.
 */
@ApplicationScoped
public class KafkaExporterMetricsService {

    private static final Logger LOG = Logger.getLogger(KafkaExporterMetricsService.class);
    private static final String DEFAULT_CATEGORY = "consumer_lag";

    @Inject
    KubernetesResourceService k8sService;

    @Inject
    MetricsQueryService metricsQueryService;

    @Inject
    KafkaService kafkaService;

    KafkaExporterMetricsService() {
        // package-private no-arg constructor for CDI
    }

    /**
     * Retrieves metrics from Kafka Exporter pods for a cluster.
     *
     * @param namespace    the namespace (optional, null for auto-discovery)
     * @param clusterName  the Kafka cluster name (required)
     * @param category     the metric category (optional, defaults to "consumer_lag")
     * @param metricNames  explicit metric names (optional, merged with category)
     * @param rangeMinutes range query duration in minutes (optional, null for instant)
     * @param startTime    absolute start time in ISO 8601 format (optional, use with endTime)
     * @param endTime      absolute end time in ISO 8601 format (optional, use with startTime)
     * @param stepSeconds  range query step interval (optional, uses default)
     * @return the Kafka Exporter metrics response
     */
    @SuppressWarnings("checkstyle:ParameterNumber")
    public KafkaExporterMetricsResponse getKafkaExporterMetrics(final String namespace,
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
            KafkaExporterMetricCategories::resolve, KafkaExporterMetricCategories.allCategories());
        List<String> categories = new ArrayList<>();
        if (cat != null) {
            categories.add(cat);
        }

        // Find the Kafka cluster
        Kafka kafka = kafkaService.findKafkaCluster(ns, name);
        String resolvedNs = kafka.getMetadata().getNamespace();

        LOG.infof("Getting Kafka Exporter metrics for cluster '%s' in namespace '%s' (provider=%s)",
            name, resolvedNs, metricsQueryService.providerName());

        // Find Kafka Exporter pods
        List<Pod> pods = k8sService.queryResourcesByLabel(
                Pod.class, resolvedNs, ResourceLabels.STRIMZI_CLUSTER_LABEL, name)
            .stream()
            .filter(pod -> {
                Map<String, String> labels = pod.getMetadata().getLabels();
                if (labels == null) {
                    return false;
                }
                String componentType = labels.get(ResourceLabels.STRIMZI_COMPONENT_TYPE_LABEL);
                return StrimziConstants.ComponentTypes.KAFKA_EXPORTER.equals(componentType);
            })
            .toList();

        LOG.debugf("Found %d Kafka Exporter pod(s) for cluster '%s': %s",
            pods.size(), name,
            pods.stream().map(p -> p.getMetadata().getName()).toList());

        if (pods.isEmpty()) {
            return KafkaExporterMetricsResponse.empty(name, resolvedNs,
                String.format("No Kafka Exporter pods found for cluster '%s' in namespace '%s'",
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
        String interpretation = KafkaExporterMetricCategories.interpretation(effectiveCategories);

        return KafkaExporterMetricsResponse.of(name, resolvedNs,
            metricsQueryService.providerName(), categories, samples, interpretation);
    }
}
