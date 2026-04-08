/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.metrics.prometheus.util;

import java.util.regex.Pattern;

/**
 * Sanitizes user-supplied values before interpolating them into PromQL queries.
 * Prevents PromQL injection by validating metric names and label names against
 * the Prometheus naming specification, and by escaping special characters in
 * label values.
 */
public final class PromQLSanitizer {

    /** Valid Prometheus metric name pattern: starts with letter, underscore, or colon. */
    private static final Pattern METRIC_NAME_PATTERN =
        Pattern.compile("^[a-zA-Z_:][a-zA-Z0-9_:]*$");

    /** Valid Prometheus label name pattern: starts with letter or underscore. */
    private static final Pattern LABEL_NAME_PATTERN =
        Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_]*$");

    private PromQLSanitizer() {
        // Utility class — no instantiation
    }

    /**
     * Validates a metric name against the Prometheus naming specification.
     *
     * @param name the metric name to validate
     * @return the validated metric name
     * @throws IllegalArgumentException if the name is invalid
     */
    public static String sanitizeMetricName(final String name) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("Metric name must not be null or empty");
        }
        if (!METRIC_NAME_PATTERN.matcher(name).matches()) {
            throw new IllegalArgumentException(
                String.format("Invalid metric name '%s'. Must match pattern: %s",
                    name, METRIC_NAME_PATTERN.pattern()));
        }
        return name;
    }

    /**
     * Validates a label name against the Prometheus naming specification.
     *
     * @param name the label name to validate
     * @return the validated label name
     * @throws IllegalArgumentException if the name is invalid
     */
    public static String sanitizeLabelName(final String name) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("Label name must not be null or empty");
        }
        if (!LABEL_NAME_PATTERN.matcher(name).matches()) {
            throw new IllegalArgumentException(
                String.format("Invalid label name '%s'. Must match pattern: %s",
                    name, LABEL_NAME_PATTERN.pattern()));
        }
        return name;
    }

    /**
     * Escapes special characters in a label value for safe interpolation
     * into PromQL string literals. Escapes backslash, double-quote, and newline.
     *
     * @param value the label value to escape
     * @return the escaped label value safe for use in PromQL
     */
    public static String sanitizeLabelValue(final String value) {
        if (value == null) {
            return "";
        }
        return value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n");
    }
}
