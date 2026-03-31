/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.service.metrics;

import io.fabric8.kubernetes.api.model.Pod;
import io.quarkiverse.mcp.server.ToolCallException;
import io.streamshub.mcp.common.dto.metrics.MetricSample;
import io.streamshub.mcp.common.dto.metrics.MetricsQueryParams;
import io.streamshub.mcp.common.dto.metrics.PodTarget;
import io.streamshub.mcp.common.service.KubernetesResourceService;
import io.streamshub.mcp.common.service.metrics.MetricsProvider;
import io.streamshub.mcp.common.util.InputUtils;
import io.streamshub.mcp.strimzi.config.metrics.KafkaMetricCategories;
import io.streamshub.mcp.strimzi.dto.metrics.KafkaMetricsResponse;
import io.strimzi.api.ResourceLabels;
import io.strimzi.api.kafka.model.kafka.Kafka;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Service for retrieving Kafka cluster metrics via pluggable providers.
 */
@ApplicationScoped
public class KafkaMetricsService {

    private static final Logger LOG = Logger.getLogger(KafkaMetricsService.class);
    private static final String DEFAULT_CATEGORY = "replication";
    private static final int SECONDS_PER_MINUTE = 60;

    @Inject
    KubernetesResourceService k8sService;

    @Inject
    Instance<MetricsProvider> metricsProviderInstance;

    @ConfigProperty(name = "mcp.metrics.provider", defaultValue = "pod-scraping")
    String providerName;

    @ConfigProperty(name = "mcp.metrics.default-step-seconds", defaultValue = "60")
    int defaultStepSeconds;

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
     * @param stepSeconds  range query step interval (optional, uses default)
     * @return the metrics response
     */
    @SuppressWarnings("checkstyle:ParameterNumber")
    public KafkaMetricsResponse getKafkaMetrics(final String namespace,
                                                 final String clusterName,
                                                 final String category,
                                                 final String metricNames,
                                                 final Integer rangeMinutes,
                                                 final Integer stepSeconds) {
        String ns = InputUtils.normalizeInput(namespace);
        String name = InputUtils.normalizeInput(clusterName);
        String cat = InputUtils.normalizeInput(category);

        if (name == null) {
            throw new ToolCallException("Cluster name is required");
        }

        if (metricsProviderInstance.isUnsatisfied()) {
            throw new ToolCallException(
                "No metrics provider configured."
                    + " Set 'mcp.metrics.provider' to 'streamshub-pod-scraping' or 'streamshub-prometheus'.");
        }

        // Resolve metric names from category + explicit names
        List<String> resolvedMetrics = resolveMetricNames(cat, metricNames);
        List<String> categories = new ArrayList<>();
        if (cat != null) {
            categories.add(cat);
        }

        // Find the Kafka cluster
        Kafka kafka = findKafkaCluster(ns, name);
        String resolvedNs = kafka.getMetadata().getNamespace();

        LOG.infof("Getting metrics for cluster '%s' in namespace '%s' (provider=%s)",
            name, resolvedNs, providerName);

        // Find Kafka pods
        List<Pod> pods = k8sService.queryResourcesByLabel(
            Pod.class, resolvedNs, ResourceLabels.STRIMZI_CLUSTER_LABEL, name);

        if (pods.isEmpty()) {
            return KafkaMetricsResponse.empty(name, resolvedNs,
                String.format("No Kafka pods found for cluster '%s' in namespace '%s'",
                    name, resolvedNs));
        }

        // Build query params
        List<PodTarget> podTargets = pods.stream()
            .map(pod -> PodTarget.of(
                pod.getMetadata().getNamespace(),
                pod.getMetadata().getName()))
            .toList();

        Map<String, String> labelMatchers = new LinkedHashMap<>();
        labelMatchers.put("namespace", resolvedNs);
        labelMatchers.put("strimzi_io_cluster", name);

        MetricsQueryParams params;
        if (rangeMinutes != null && rangeMinutes > 0) {
            Instant end = Instant.now();
            Instant start = end.minusSeconds((long) rangeMinutes * SECONDS_PER_MINUTE);
            int step = stepSeconds != null ? stepSeconds : defaultStepSeconds;
            params = MetricsQueryParams.range(resolvedMetrics, labelMatchers, start, end, step);
        } else {
            params = MetricsQueryParams.instant(resolvedMetrics, labelMatchers, podTargets);
        }

        // Query metrics
        List<MetricSample> samples = metricsProviderInstance.get().queryMetrics(params);

        return KafkaMetricsResponse.of(name, resolvedNs, providerName, categories, samples);
    }

    private List<String> resolveMetricNames(final String category, final String metricNames) {
        List<String> resolved = new ArrayList<>();

        String effectiveCategory = category;
        if (effectiveCategory == null && (metricNames == null || metricNames.isBlank())) {
            effectiveCategory = DEFAULT_CATEGORY;
        }

        if (effectiveCategory != null) {
            List<String> categoryMetrics = KafkaMetricCategories.resolve(effectiveCategory);
            if (categoryMetrics.isEmpty() && category != null) {
                throw new ToolCallException(
                    String.format("Unknown metric category '%s'. Available: %s",
                        category, KafkaMetricCategories.allCategories()));
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

    private Kafka findKafkaCluster(final String namespace, final String name) {
        Kafka kafka;
        if (namespace != null) {
            kafka = k8sService.getResource(Kafka.class, namespace, name);
        } else {
            List<Kafka> allClusters = k8sService.queryResourcesInAnyNamespace(Kafka.class);
            List<Kafka> matching = allClusters.stream()
                .filter(k -> name.equals(k.getMetadata().getName()))
                .toList();

            if (matching.size() > 1) {
                String namespaces = matching.stream()
                    .map(k -> k.getMetadata().getNamespace())
                    .distinct()
                    .collect(Collectors.joining(", "));
                throw new ToolCallException(
                    "Multiple clusters named '" + name + "' found in namespaces: "
                        + namespaces + ". Specify a namespace.");
            }
            kafka = matching.isEmpty() ? null : matching.get(0);
        }

        if (kafka == null) {
            if (namespace != null) {
                throw new ToolCallException(
                    "Kafka cluster '" + name + "' not found in namespace " + namespace);
            } else {
                throw new ToolCallException(
                    "Kafka cluster '" + name + "' not found in any namespace");
            }
        }

        return kafka;
    }
}
