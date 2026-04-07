/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.util.metrics;

import io.streamshub.mcp.common.dto.metrics.MetricSample;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Parses Prometheus text exposition format into {@link MetricSample} objects.
 * Handles comment lines, metrics with and without labels, and optional timestamps.
 */
public final class PrometheusTextParser {

    private static final Logger LOG = Logger.getLogger(PrometheusTextParser.class);

    /**
     * Prometheus exposition format uses curly braces to delimit the label set.
     */
    private static final char LABELS_OPEN_CHAR = '{';
    private static final char LABELS_CLOSE_CHAR = '}';
    private static final char FIELD_SEPARATOR_CHAR = ' ';
    private static final char LABEL_SEPARATOR_CHAR = ',';
    private static final char KEY_VALUE_SEPARATOR_CHAR = '=';
    private static final char QUOTED_VALUE_CHAR = '"';
    private static final char ESCAPE_CHAR = '\\';

    /**
     * A metric sample may contain the numeric value only, or value plus timestamp.
     */
    private static final int MAX_VALUE_AND_TIMESTAMP_PARTS = 2;
    private static final int VALUE_PART_INDEX = 0;
    private static final int TIMESTAMP_PART_INDEX = 1;

    private PrometheusTextParser() {
        // Utility class — no instantiation
    }

    /**
     * Parses all metrics from Prometheus text exposition format.
     *
     * @param text the raw Prometheus text output
     * @return a list of parsed metric samples
     */
    public static List<MetricSample> parse(final String text) {
        return parse(text, null);
    }

    /**
     * Parses metrics from Prometheus text exposition format, optionally filtering by metric name.
     *
     * @param text        the raw Prometheus text output
     * @param metricNames metric names to include (null or empty to include all)
     * @return a list of parsed metric samples matching the filter
     */
    public static List<MetricSample> parse(final String text, final Set<String> metricNames) {
        if (text == null || text.isBlank()) {
            return List.of();
        }

        List<MetricSample> samples = new ArrayList<>();

        for (String line : text.lines().toList()) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }

            MetricSample sample = parseLine(trimmed);
            if (sample == null) {
                LOG.debugf("Skipping malformed metric line: %s", trimmed);
                continue;
            }

            if (metricNames == null || metricNames.isEmpty() || metricNames.contains(sample.name())) {
                samples.add(sample);
            }
        }

        return Collections.unmodifiableList(samples);
    }

    private static MetricSample parseLine(final String line) {
        String metricName;
        Map<String, String> labels;
        String remainder;

        int braceOpen = line.indexOf(LABELS_OPEN_CHAR);
        if (braceOpen >= 0) {
            metricName = line.substring(0, braceOpen);
            int braceClose = line.indexOf(LABELS_CLOSE_CHAR, braceOpen);
            if (braceClose < 0) {
                return null;
            }
            labels = parseLabels(line.substring(braceOpen + 1, braceClose));
            // Everything after the label block should contain the metric value
            // and optionally the sample timestamp in epoch milliseconds.
            remainder = line.substring(braceClose + 1).trim();
        } else {
            int firstSpace = line.indexOf(FIELD_SEPARATOR_CHAR);
            if (firstSpace < 0) {
                return null;
            }
            metricName = line.substring(0, firstSpace);
            labels = Map.of();
            remainder = line.substring(firstSpace + 1).trim();
        }

        if (metricName.isEmpty() || remainder.isEmpty()) {
            return null;
        }

        // Split into at most two tokens: mandatory metric value and optional timestamp.
        String[] parts = remainder.split("\\s+", MAX_VALUE_AND_TIMESTAMP_PARTS);
        double value;
        try {
            value = Double.parseDouble(parts[VALUE_PART_INDEX]);
        } catch (NumberFormatException e) {
            return null;
        }

        Instant timestamp = null;
        if (parts.length > TIMESTAMP_PART_INDEX) {
            try {
                timestamp = Instant.ofEpochMilli(Long.parseLong(parts[TIMESTAMP_PART_INDEX]));
            } catch (NumberFormatException e) {
                // Ignore invalid timestamp
            }
        }

        return MetricSample.of(metricName, labels, value, timestamp);
    }

    /**
     * Parse the content of a Prometheus label block into a map of label names to values.
     * <p>
     * The input is expected to be the text between the surrounding curly braces, for example
     * {@code instance="broker-0",namespace="kafka"}. The parser walks the string from left
     * to right, skipping separators, reading each label key up to {@code =}, and then reading
     * the quoted label value. Escaped characters inside quoted values are preserved as their
     * literal character values.
     * <p>
     * If the label string is blank, an empty map is returned. If malformed content is encountered,
     * parsing stops and the labels collected so far are returned.
     *
     * @param labelString raw label content without the surrounding {@code {}} characters
     * @return immutable map of parsed labels
     */
    private static Map<String, String> parseLabels(final String labelString) {
        if (labelString.isBlank()) {
            return Map.of();
        }

        Map<String, String> labels = new LinkedHashMap<>();
        int i = 0;
        int len = labelString.length();

        while (i < len) {
            // Skip whitespace and commas
            while (i < len && (labelString.charAt(i) == LABEL_SEPARATOR_CHAR
                || labelString.charAt(i) == FIELD_SEPARATOR_CHAR)) {
                i++;
            }
            if (i >= len) {
                break;
            }

            // Read key
            int eqIdx = labelString.indexOf(KEY_VALUE_SEPARATOR_CHAR, i);
            if (eqIdx < 0) {
                break;
            }
            String key = labelString.substring(i, eqIdx).trim();

            // Read value (expect ="...")
            int valStart = eqIdx + 1;
            if (valStart >= len || labelString.charAt(valStart) != QUOTED_VALUE_CHAR) {
                break;
            }
            valStart++; // skip opening quote

            StringBuilder value = new StringBuilder();
            int j = valStart;
            while (j < len) {
                char c = labelString.charAt(j);
                if (c == ESCAPE_CHAR && j + 1 < len) {
                    // Preserve the escaped next character without the escape prefix.
                    value.append(labelString.charAt(j + 1));
                    j += 2;
                } else if (c == QUOTED_VALUE_CHAR) {
                    break;
                } else {
                    value.append(c);
                    j++;
                }
            }

            labels.put(key, value.toString());
            i = j + 1; // skip closing quote
        }

        return Collections.unmodifiableMap(labels);
    }
}
