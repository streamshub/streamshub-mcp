/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.dto.metrics;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.Map;

/**
 * A single metric sample scraped from a Prometheus-compatible endpoint or query result.
 *
 * @param name      the metric name
 * @param labels    the metric labels as key-value pairs
 * @param value     the metric value
 * @param timestamp the time the sample was collected (null if not available)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MetricSample(
    @JsonProperty("name") String name,
    @JsonProperty("labels") Map<String, String> labels,
    @JsonProperty("value") double value,
    @JsonProperty("timestamp") Instant timestamp
) {

    /**
     * Creates a metric sample with a timestamp.
     *
     * @param name      the metric name
     * @param labels    the metric labels
     * @param value     the metric value
     * @param timestamp the collection timestamp
     * @return a new metric sample
     */
    public static MetricSample of(final String name, final Map<String, String> labels,
                                   final double value, final Instant timestamp) {
        return new MetricSample(name, labels, value, timestamp);
    }

    /**
     * Creates a metric sample without a timestamp.
     *
     * @param name   the metric name
     * @param labels the metric labels
     * @param value  the metric value
     * @return a new metric sample with null timestamp
     */
    public static MetricSample of(final String name, final Map<String, String> labels,
                                   final double value) {
        return new MetricSample(name, labels, value, null);
    }
}
