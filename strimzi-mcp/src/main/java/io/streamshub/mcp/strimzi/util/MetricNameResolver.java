/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.util;

import io.quarkiverse.mcp.server.ToolCallException;
import io.streamshub.mcp.metrics.prometheus.util.PromQLSanitizer;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * Resolves metric names from a combination of category and explicit metric names.
 * Shared across metrics services to avoid duplicated resolution logic.
 */
public final class MetricNameResolver {

    private static final Logger LOG = Logger.getLogger(MetricNameResolver.class);

    private MetricNameResolver() {
    }

    /**
     * Resolves metric names from a category and/or explicit metric names.
     * When neither category nor metric names are provided, uses the default category.
     *
     * @param category         the metric category (may be null)
     * @param metricNames      comma-separated explicit metric names (may be null)
     * @param defaultCategory  the default category to use when no inputs are provided
     * @param categoryResolver function that maps a category name to its metric names
     * @param allCategories    supplier of all valid category names (for error messages)
     * @return the resolved list of metric names
     */
    public static List<String> resolve(final String category,
                                        final String metricNames,
                                        final String defaultCategory,
                                        final Function<String, List<String>> categoryResolver,
                                        final Set<String> allCategories) {
        List<String> resolved = new ArrayList<>();

        String effectiveCategory = category;
        if (effectiveCategory == null && (metricNames == null || metricNames.isBlank())) {
            effectiveCategory = defaultCategory;
        }

        if (effectiveCategory != null) {
            List<String> categoryMetrics = categoryResolver.apply(effectiveCategory);
            if (categoryMetrics.isEmpty() && category != null) {
                throw new ToolCallException(
                    String.format("Unknown metric category '%s'. Available: %s",
                        category, allCategories));
            }
            resolved.addAll(categoryMetrics);
        }

        if (metricNames != null && !metricNames.isBlank()) {
            for (String metric : metricNames.split(",")) {
                String trimmed = metric.trim();
                if (!trimmed.isEmpty()) {
                    try {
                        String validated = PromQLSanitizer.sanitizeMetricName(trimmed);
                        if (!resolved.contains(validated)) {
                            resolved.add(validated);
                        }
                    } catch (IllegalArgumentException e) {
                        LOG.warnf("Invalid metric name '%s': %s", trimmed, e.getMessage());
                    }
                }
            }
        }

        return resolved;
    }
}