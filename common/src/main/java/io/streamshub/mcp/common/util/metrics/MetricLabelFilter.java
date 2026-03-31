/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.util.metrics;

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
        return filtered;
    }
}
