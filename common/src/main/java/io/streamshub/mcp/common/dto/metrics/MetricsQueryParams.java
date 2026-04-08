/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.dto.metrics;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Parameters for a metrics query. Supports both pod-scraping and Prometheus providers
 * through union fields: {@code podTargets} for scraping and {@code labelMatchers} for Prometheus.
 *
 * @param metricNames   the metric names to retrieve
 * @param labelMatchers label key-value pairs for Prometheus query filtering
 * @param podTargets    pod endpoints to scrape (used by pod-scraping provider)
 * @param startTime     range query start time (null for instant queries)
 * @param endTime       range query end time (null for instant queries)
 * @param stepSeconds   range query step interval in seconds
 */
public record MetricsQueryParams(
    List<String> metricNames,
    Map<String, String> labelMatchers,
    List<PodTarget> podTargets,
    Instant startTime,
    Instant endTime,
    Integer stepSeconds
) {

    /**
     * Creates parameters for an instant (point-in-time) query.
     *
     * @param metricNames   the metric names to retrieve
     * @param labelMatchers label matchers for Prometheus filtering
     * @param podTargets    pod endpoints to scrape
     * @return instant query parameters
     */
    public static MetricsQueryParams instant(final List<String> metricNames,
                                              final Map<String, String> labelMatchers,
                                              final List<PodTarget> podTargets) {
        return new MetricsQueryParams(metricNames, labelMatchers, podTargets, null, null, null);
    }

    /**
     * Creates parameters for a range query over a time window.
     *
     * @param metricNames   the metric names to retrieve
     * @param labelMatchers label matchers for Prometheus filtering
     * @param startTime     the range start time
     * @param endTime       the range end time
     * @param stepSeconds   the step interval in seconds
     * @return range query parameters
     */
    public static MetricsQueryParams range(final List<String> metricNames,
                                            final Map<String, String> labelMatchers,
                                            final Instant startTime,
                                            final Instant endTime,
                                            final int stepSeconds) {
        return new MetricsQueryParams(metricNames, labelMatchers, List.of(),
            startTime, endTime, stepSeconds);
    }

    /**
     * Returns whether this represents a range query (both start and end time are set).
     *
     * @return true if this is a range query
     */
    public boolean isRangeQuery() {
        return startTime != null && endTime != null;
    }
}
