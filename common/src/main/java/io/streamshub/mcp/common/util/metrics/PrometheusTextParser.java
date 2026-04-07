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

        int braceOpen = line.indexOf('{');
        if (braceOpen >= 0) {
            metricName = line.substring(0, braceOpen);
            int braceClose = line.indexOf('}', braceOpen);
            if (braceClose < 0) {
                return null;
            }
            labels = parseLabels(line.substring(braceOpen + 1, braceClose));
            remainder = line.substring(braceClose + 1).trim();
        } else {
            int firstSpace = line.indexOf(' ');
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

        String[] parts = remainder.split("\\s+", 2);
        double value;
        try {
            value = Double.parseDouble(parts[0]);
        } catch (NumberFormatException e) {
            return null;
        }

        Instant timestamp = null;
        if (parts.length > 1) {
            try {
                timestamp = Instant.ofEpochMilli(Long.parseLong(parts[1]));
            } catch (NumberFormatException e) {
                // Ignore invalid timestamp
            }
        }

        return MetricSample.of(metricName, labels, value, timestamp);
    }

    private static Map<String, String> parseLabels(final String labelString) {
        if (labelString.isBlank()) {
            return Map.of();
        }

        Map<String, String> labels = new LinkedHashMap<>();
        int i = 0;
        int len = labelString.length();

        while (i < len) {
            // Skip whitespace and commas
            while (i < len && (labelString.charAt(i) == ',' || labelString.charAt(i) == ' ')) {
                i++;
            }
            if (i >= len) {
                break;
            }

            // Read key
            int eqIdx = labelString.indexOf('=', i);
            if (eqIdx < 0) {
                break;
            }
            String key = labelString.substring(i, eqIdx).trim();

            // Read value (expect ="...")
            int valStart = eqIdx + 1;
            if (valStart >= len || labelString.charAt(valStart) != '"') {
                break;
            }
            valStart++; // skip opening quote

            StringBuilder value = new StringBuilder();
            int j = valStart;
            while (j < len) {
                char c = labelString.charAt(j);
                if (c == '\\' && j + 1 < len) {
                    value.append(labelString.charAt(j + 1));
                    j += 2;
                } else if (c == '"') {
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
