/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.util.metrics;

import io.streamshub.mcp.common.dto.metrics.AggregationLevel;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Filters internal Prometheus and infrastructure labels from metric samples.
 * Keeps domain-relevant labels that help understand metric context (namespace, pod, kind, etc.)
 * while stripping scrape-config and monitoring-infrastructure labels.
 */
public final class MetricLabelFilter {

    private static final Set<String> INTERNAL_LABELS = Set.of(
        "__name__",
        "job",
        "instance",
        "endpoint",
        "container",
        "prometheus",
        "service"
    );

    private static final Set<String> POD_IDENTITY_LABELS = Set.of(
        "pod", "kubernetes_pod_name", "strimzi_io_pod_name", "node_name", "node_ip"
    );

    private static final Set<String> CANONICAL_LABELS = Set.of("pod", "topic", "partition");

    private static final Set<String> TOPIC_PARTITION_LABELS = Set.of(
        "topic", "partition"
    );

    private static final Set<String> PARTITION_LABELS = Set.of(
        "partition"
    );

    private MetricLabelFilter() {
        // Utility class — no instantiation
    }

    /**
     * Filters out internal/infrastructure labels, returning only domain-relevant labels.
     *
     * @param labels the original label map (may be null)
     * @return a new map with internal labels removed, or an empty map if input is null
     */
    public static Map<String, String> filterLabels(final Map<String, String> labels) {
        if (labels == null || labels.isEmpty()) {
            return Map.of();
        }

        Map<String, String> filtered = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : labels.entrySet()) {
            if (!INTERNAL_LABELS.contains(entry.getKey())) {
                filtered.put(entry.getKey(), entry.getValue());
            }
        }
        return deduplicateByValue(filtered);
    }

    /**
     * Filters labels for aggregation at the given level. Always strips internal labels,
     * then strips additional labels based on the aggregation level:
     * <ul>
     *   <li>PARTITION — no extra stripping (full detail)</li>
     *   <li>TOPIC — strips partition labels</li>
     *   <li>BROKER — strips topic + partition labels</li>
     *   <li>CLUSTER — strips topic + partition + pod identity labels</li>
     * </ul>
     *
     * @param labels the original label map (may be null)
     * @param level  the aggregation level
     * @return a new map with the appropriate labels removed
     */
    public static Map<String, String> labelsForAggregation(final Map<String, String> labels,
                                                            final AggregationLevel level) {
        if (labels == null || labels.isEmpty()) {
            return Map.of();
        }

        Map<String, String> filtered = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : labels.entrySet()) {
            String key = entry.getKey();
            if (INTERNAL_LABELS.contains(key)) {
                continue;
            }
            if (shouldStripForLevel(key, level)) {
                continue;
            }
            filtered.put(key, entry.getValue());
        }
        return deduplicateByValue(filtered);
    }

    static Map<String, String> deduplicateByValue(final Map<String, String> labels) {
        if (labels == null || labels.size() <= 1) {
            return labels == null ? Map.of() : labels;
        }

        Set<String> canonicalValues = new HashSet<>();
        for (String canonical : CANONICAL_LABELS) {
            String val = labels.get(canonical);
            if (val != null) {
                canonicalValues.add(val);
            }
        }

        if (canonicalValues.isEmpty()) {
            return labels;
        }

        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : labels.entrySet()) {
            if (CANONICAL_LABELS.contains(entry.getKey())
                    || !canonicalValues.contains(entry.getValue())) {
                result.put(entry.getKey(), entry.getValue());
            }
        }
        return result;
    }

    private static boolean shouldStripForLevel(final String key, final AggregationLevel level) {
        return switch (level) {
            case PARTITION -> false;
            case TOPIC -> PARTITION_LABELS.contains(key);
            case BROKER -> TOPIC_PARTITION_LABELS.contains(key);
            case CLUSTER -> TOPIC_PARTITION_LABELS.contains(key) || POD_IDENTITY_LABELS.contains(key);
        };
    }
}
