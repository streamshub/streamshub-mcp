/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.util.metrics;

/**
 * Shared wording for metrics responses that were cut at {@code mcp.metrics.max-samples}.
 */
public final class MetricTruncation {

    private MetricTruncation() {
    }

    /**
     * Builds the message appended to a metrics response whose samples were truncated.
     *
     * @param limit the sample limit that was reached, or 0 or less when it is not known
     * @return a sentence telling the caller the data is incomplete and how to narrow the query
     */
    public static String note(final int limit) {
        String where;
        if (limit > 0) {
            where = String.format("at %d samples ", limit);
        } else {
            where = "";
        }
        return "Results truncated " + where + "(mcp.metrics.max-samples): data is incomplete."
            + " Narrow the query (fewer categories or metricNames; for Prometheus range queries also"
            + " a shorter rangeMinutes or a larger stepSeconds) or raise the limit.";
    }

    /**
     * Value of a response's {@code truncated} flag.
     *
     * @param truncatedAtLimit the sample limit that was reached, or null when the result is complete
     * @return {@code Boolean.TRUE} when truncated, otherwise null so the flag is omitted
     */
    public static Boolean flag(final Integer truncatedAtLimit) {
        return truncatedAtLimit != null ? Boolean.TRUE : null;
    }

    /**
     * Appends the truncation note to a response message when the result was truncated.
     *
     * @param message          the response message
     * @param truncatedAtLimit the sample limit that was reached, or null when the result is complete
     * @return the message, followed by {@link #note(int)} when truncated
     */
    public static String appendNote(final String message, final Integer truncatedAtLimit) {
        return truncatedAtLimit != null ? message + " " + note(truncatedAtLimit) : message;
    }
}
