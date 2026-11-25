/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.util.metrics;

import io.streamshub.mcp.common.dto.metrics.MetricSample;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Applies {@code mcp.metrics.max-samples} to each metric name separately.
 *
 * <p>A single limit shared by every metric in a query lets one high-cardinality metric
 * (for example a per-request-type histogram) use up the whole budget, so the metrics queried
 * after it come back empty. Counting per name keeps every requested metric represented.</p>
 */
public final class PerMetricSampleLimit {

    private final int limit;
    private final Map<String, Integer> counts = new HashMap<>();
    private final Set<String> truncated = new LinkedHashSet<>();

    /**
     * Creates a limiter.
     *
     * @param limit the maximum number of samples per metric name, or 0 or less for no limit
     */
    public PerMetricSampleLimit(final int limit) {
        this.limit = limit;
    }

    /**
     * Records one more sample of a metric if its limit allows it.
     *
     * @param metricName the metric name of the sample
     * @return true when the sample may be kept, false when the metric already reached the limit
     */
    public boolean tryAccept(final String metricName) {
        if (limit <= 0) {
            return true;
        }
        int count = counts.getOrDefault(metricName, 0);
        if (count >= limit) {
            truncated.add(metricName);
            return false;
        }
        counts.put(metricName, count + 1);
        return true;
    }

    /**
     * Returns the metric names that had samples dropped.
     *
     * @return the truncated metric names in the order they first hit the limit
     */
    public Set<String> truncatedMetrics() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(truncated));
    }

    /**
     * Keeps at most {@code limit} samples of each metric name, preserving order.
     *
     * @param samples the samples to cap
     * @param limit   the maximum number of samples per metric name, or 0 or less for no limit
     * @return the capped samples; the input list itself when nothing was dropped
     */
    public static List<MetricSample> apply(final List<MetricSample> samples, final int limit) {
        if (limit <= 0 || samples.size() <= limit) {
            return samples;
        }
        PerMetricSampleLimit cap = new PerMetricSampleLimit(limit);
        List<MetricSample> kept = new ArrayList<>(samples.size());
        for (MetricSample sample : samples) {
            if (cap.tryAccept(sample.name())) {
                kept.add(sample);
            }
        }
        return kept.size() == samples.size() ? samples : kept;
    }

    /**
     * Reports whether any metric in a result reached the limit. Providers stop collecting a metric
     * at the limit, so a metric with exactly {@code limit} samples is treated as truncated: the
     * backend may have had more.
     *
     * @param samples the samples as returned by the provider, before any filtering
     * @param limit   the maximum number of samples per metric name, or 0 or less for no limit
     * @return true when at least one metric name has {@code limit} or more samples
     */
    public static boolean reachedLimit(final Collection<MetricSample> samples, final int limit) {
        if (limit <= 0 || samples.size() < limit) {
            return false;
        }
        Map<String, Integer> counts = new HashMap<>();
        for (MetricSample sample : samples) {
            if (counts.merge(sample.name(), 1, Integer::sum) >= limit) {
                return true;
            }
        }
        return false;
    }
}
