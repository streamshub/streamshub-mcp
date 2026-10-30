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
            + " Narrow the query (fewer categories, shorter rangeMinutes, larger stepSeconds)"
            + " or raise the limit.";
    }
}
