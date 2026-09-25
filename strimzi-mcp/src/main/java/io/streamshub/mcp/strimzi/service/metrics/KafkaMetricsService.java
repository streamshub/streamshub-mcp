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
import io.streamshub.mcp.strimzi.config.metrics.KafkaMetricCategories;
import io.streamshub.mcp.strimzi.dto.metrics.KafkaMetricsResponse;
import io.streamshub.mcp.strimzi.service.kafka.KafkaService;
import io.streamshub.mcp.strimzi.util.MetricNameResolver;
import io.streamshub.mcp.strimzi.util.MetricsBackend;
import io.strimzi.api.ResourceLabels;
import io.strimzi.api.kafka.model.kafka.Kafka;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
/**
 * Service for retrieving Kafka cluster metrics via pluggable providers.
 */
@ApplicationScoped
public class KafkaMetricsService {

    private static final Logger LOG = Logger.getLogger(KafkaMetricsService.class);
    private static final String DEFAULT_CATEGORY = KafkaMetricCategories.REPLICATION;
    private static final Set<String> DEFAULT_QUANTILES = Set.of("0.50", "0.99");

    /** Per-partition 0/1 gauges: a non-zero value marks the partition as worth reporting. */
    private static final Set<String> PARTITION_HEALTH_FLAGS = Set.of(
        "kafka_cluster_partition_underminisr",
        "kafka_cluster_partition_atminisr");

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
     * @param aggregation  aggregation level (optional, defaults to "broker")
     * @param requestTypes comma-separated request types to filter (optional)
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
                                                 final Integer stepSeconds,
                                                 final String aggregation,
                                                 final String requestTypes) {
        String ns = InputUtils.normalizeInput(namespace);
        String name = InputUtils.normalizeInput(clusterName);
        String cat = InputUtils.normalizeInput(category);

        if (name == null) {
            throw McpErrors.invalidParams("Cluster name is required");
        }

        // Validate time range parameters
        TimeRangeValidator.validateTimeRangeParameters(rangeMinutes, startTime, endTime);

        // Find the Kafka cluster
        Kafka kafka = kafkaService.findKafkaCluster(ns, name);
        String resolvedNs = kafka.getMetadata().getNamespace();

        // Resolve metric names — translate to backend-specific names when using SMR
        MetricsBackend backend = MetricsBackend.fromKafka(kafka);
        List<String> resolvedMetrics = MetricNameResolver.resolve(
            cat, metricNames, DEFAULT_CATEGORY,
            KafkaMetricCategories::resolve, KafkaMetricCategories.allCategories(),
            backend, KafkaMetricCategories.aliasMap());
        List<String> categories = new ArrayList<>();
        if (cat != null) {
            categories.add(cat);
        }

        LOG.infof("Getting metrics for cluster '%s' in namespace '%s' (provider=%s)",
            name, resolvedNs, metricsQueryService.providerName());

        // Find Kafka broker pods only (excludes exporter, cruise-control, entity-operator, etc.)
        List<Pod> pods = k8sService.queryResourcesByLabel(
                Pod.class, resolvedNs, ResourceLabels.STRIMZI_CLUSTER_LABEL, name)
            .stream()
            .filter(pod -> {
                Map<String, String> labels = pod.getMetadata().getLabels();
                if (labels == null) {
                    return false;
                }
                String componentType = labels.get(ResourceLabels.STRIMZI_COMPONENT_TYPE_LABEL);
                return componentType != null
                    && StrimziConstants.ComponentTypes.BROKER_TYPES.contains(componentType);
            })
            .toList();

        LOG.debugf("Found %d scrapeable pod(s) for cluster '%s': %s",
            pods.size(), name,
            pods.stream().map(p -> p.getMetadata().getName()).toList());

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

        samples = filterByKafkaNodePods(samples, pods);
        samples = filterByRequestTypes(samples, requestTypes);

        // Build interpretation from effective categories
        List<String> effectiveCategories = new ArrayList<>(categories);
        if (effectiveCategories.isEmpty() && (metricNames == null || metricNames.isBlank())) {
            effectiveCategories.add(DEFAULT_CATEGORY);
        }

        // Apply default quantile filter and zero-value stripping for performance category
        if (effectiveCategories.contains(KafkaMetricCategories.PERFORMANCE)) {
            samples = filterByQuantiles(samples, DEFAULT_QUANTILES);
            samples = filterZeroValues(samples);
        }

        String interpretation = KafkaMetricCategories.interpretation(effectiveCategories);

        // Healthy partitions are dropped from the partitions category — see the method javadoc.
        if (effectiveCategories.contains(KafkaMetricCategories.PARTITIONS)) {
            String note = partitionScanNote(samples);
            samples = filterHealthyPartitions(samples);
            interpretation = interpretation + note;
        }

        // The guide is written in JMX Exporter spelling; rewrite it to the names actually returned.
        interpretation = MetricNameResolver.alignInterpretation(
            interpretation, backend, KafkaMetricCategories.aliasMap(),
            samples.stream().map(MetricSample::name).toList());

        String effectiveAggCat = (cat != null || metricNames == null || metricNames.isBlank())
            ? (cat != null ? cat : DEFAULT_CATEGORY)
            : null;
        AggregationLevel level = effectiveAggCat != null
            ? AggregationLevel.resolve(aggregation, KafkaMetricCategories.maxGranularity(effectiveAggCat))
            : AggregationLevel.fromString(aggregation);
        return KafkaMetricsResponse.of(name, resolvedNs,
            metricsQueryService.providerName(), effectiveCategories, samples, interpretation, level);
    }

    private static List<MetricSample> filterByKafkaNodePods(final List<MetricSample> samples,
                                                           final List<Pod> kafkaNodePods) {
        Set<String> podNames = kafkaNodePods.stream()
            .map(p -> p.getMetadata().getName())
            .collect(Collectors.toSet());
        return samples.stream()
            .filter(s -> {
                if (s.labels() == null) {
                    return true;
                }
                // Keep samples from broker or controller pods (both are Kafka node pods)
                String brokerRole = s.labels().get("strimzi_io_broker_role");
                String controllerRole = s.labels().get("strimzi_io_controller_role");
                if (brokerRole != null || controllerRole != null) {
                    return "true".equals(brokerRole) || "true".equals(controllerRole);
                }
                // Fallback: filter by pod name
                String podLabel = s.labels().get("pod");
                return podLabel == null || podNames.contains(podLabel);
            })
            .toList();
    }

    private static List<MetricSample> filterByQuantiles(final List<MetricSample> samples,
                                                         final Set<String> quantiles) {
        return samples.stream()
            .filter(s -> {
                String quantile = s.labels() != null ? s.labels().get("quantile") : null;
                return quantile == null || quantiles.contains(quantile);
            })
            .toList();
    }

    private static List<MetricSample> filterZeroValues(final List<MetricSample> samples) {
        return samples.stream()
            .filter(s -> s.value() != 0.0)
            .toList();
    }

    /**
     * Drops healthy partitions from the {@code partitions} category.
     *
     * <p>This category emits one sample per partition per metric. A live 382-partition
     * cluster returned 390 KB — over the response limit — and all but a handful of those
     * samples were zeros. Aggregating them away is not the fix: that is precisely the bug
     * {@link AggregationLevel#resolve} exists to prevent, since 3 bad partitions out of
     * 382 average to 0.008 and read as healthy. What an operator asks this category is
     * "which partitions are in trouble", so keep those and drop the rest.</p>
     *
     * <p>{@code replicascount} is never zero, so it is kept only for partitions already
     * flagged by one of the health gauges — on a healthy cluster it would otherwise be the
     * entire remaining payload. To retrieve every partition regardless of health, request
     * the metric names explicitly instead of the category.</p>
     */
    private static List<MetricSample> filterHealthyPartitions(final List<MetricSample> samples) {
        Set<String> unhealthy = samples.stream()
            .filter(s -> PARTITION_HEALTH_FLAGS.contains(s.name()) && s.value() != 0.0)
            .map(KafkaMetricsService::partitionKey)
            .collect(Collectors.toSet());

        return samples.stream()
            .filter(s -> {
                if (PARTITION_HEALTH_FLAGS.contains(s.name())) {
                    return s.value() != 0.0;
                }
                if ("kafka_cluster_partition_replicascount".equals(s.name())) {
                    return unhealthy.contains(partitionKey(s));
                }
                return true;
            })
            .toList();
    }

    /**
     * Describes what the partitions scan covered, so an empty list reads as
     * "everything is healthy" rather than "the metrics are missing".
     */
    private static String partitionScanNote(final List<MetricSample> samples) {
        long scanned = samples.stream()
            .filter(s -> PARTITION_HEALTH_FLAGS.contains(s.name()))
            .map(KafkaMetricsService::partitionKey)
            .distinct()
            .count();
        long underMinIsr = countFlagged(samples, "kafka_cluster_partition_underminisr");
        long atMinIsr = countFlagged(samples, "kafka_cluster_partition_atminisr");

        return String.format("%n%n**[PARTITION SCAN]**%n%n"
            + "Scanned %d partitions: %d under min ISR, %d at min ISR. "
            + "Only partitions in a non-healthy state are listed; healthy partitions are "
            + "omitted so the response stays readable on large clusters. An empty series "
            + "list therefore means every partition is healthy, not that data is missing. "
            + "Request the metric names explicitly instead of the category to see all partitions.",
            scanned, underMinIsr, atMinIsr);
    }

    private static long countFlagged(final List<MetricSample> samples, final String metricName) {
        return samples.stream()
            .filter(s -> metricName.equals(s.name()) && s.value() != 0.0)
            .map(KafkaMetricsService::partitionKey)
            .distinct()
            .count();
    }

    private static String partitionKey(final MetricSample sample) {
        Map<String, String> labels = sample.labels();
        if (labels == null) {
            return "?/?";
        }
        return labels.get("topic") + "/" + labels.get("partition");
    }

    private static List<MetricSample> filterByRequestTypes(final List<MetricSample> samples,
                                                            final String requestTypes) {
        if (requestTypes == null || requestTypes.isBlank()) {
            return samples;
        }
        Set<String> types = new HashSet<>();
        for (String t : requestTypes.split(",")) {
            String trimmed = t.trim();
            if (!trimmed.isEmpty()) {
                types.add(trimmed);
            }
        }
        if (types.isEmpty()) {
            return samples;
        }
        return samples.stream()
            .filter(s -> {
                String req = s.labels() != null ? s.labels().get("request") : null;
                return req == null || types.contains(req);
            })
            .toList();
    }
}
