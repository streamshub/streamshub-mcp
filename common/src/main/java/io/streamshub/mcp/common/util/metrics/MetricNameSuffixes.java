/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.util.metrics;

/**
 * The counter suffix rename applied when a Prometheus counter is rate-converted.
 *
 * <p>A name reaches the client as {@code foo_rate_per_second} where the catalog and every
 * scraped exposition call it {@code foo_total}. Three places need to agree on that: the
 * provider that performs the rename, the resolver that rewrites interpretation text, and the
 * aggregation table that is keyed on catalog spelling but looked up on the returned name.
 * Keeping the pair here is what stops them drifting apart.</p>
 */
public final class MetricNameSuffixes {

    /** Suffix of a Prometheus counter, before rate conversion. */
    public static final String TOTAL = "_total";

    /** Suffix the counter carries once rate-converted. */
    public static final String RATE_PER_SECOND = "_rate_per_second";

    private MetricNameSuffixes() {
        // Constant holder — no instantiation
    }

    /**
     * Returns the pre-rename counter name for a rate-converted metric.
     *
     * @param metricName the metric name to convert
     * @return the {@code _total} spelling, or null if the name is not rate-converted
     */
    public static String toTotal(final String metricName) {
        if (metricName == null || !metricName.endsWith(RATE_PER_SECOND)) {
            return null;
        }
        return metricName.substring(0, metricName.length() - RATE_PER_SECOND.length()) + TOTAL;
    }

    /**
     * Returns the post-rename name for a counter.
     *
     * @param metricName the metric name to convert
     * @return the {@code _rate_per_second} spelling, or null if the name is not a counter
     */
    public static String toRate(final String metricName) {
        if (metricName == null || !metricName.endsWith(TOTAL)) {
            return null;
        }
        return metricName.substring(0, metricName.length() - TOTAL.length()) + RATE_PER_SECOND;
    }
}
