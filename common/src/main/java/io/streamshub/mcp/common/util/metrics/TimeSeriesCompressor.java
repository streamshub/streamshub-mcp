/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.util.metrics;

import java.util.ArrayList;
import java.util.List;

/**
 * Compresses metric time series data by collapsing consecutive runs of identical values.
 * For each run of constant values, only the first and last data points are kept,
 * significantly reducing the number of data points for stable metrics.
 */
public final class TimeSeriesCompressor {

    private static final double EPSILON = 1e-9;

    private TimeSeriesCompressor() {
        // Utility class — no instantiation
    }

    /**
     * Compresses a list of time-value data points by collapsing constant-value runs.
     * Each data point is a {@code [epochSeconds, value]} list. Consecutive points with
     * the same value (within epsilon {@value EPSILON}) are reduced to just the first
     * and last point of each run.
     *
     * @param dataPoints the raw data points to compress
     * @return a compressed list with constant runs collapsed to first+last
     */
    public static List<List<Object>> compress(final List<List<Object>> dataPoints) {
        if (dataPoints.size() <= 2) {
            return dataPoints;
        }

        List<List<Object>> compressed = new ArrayList<>();
        compressed.add(dataPoints.getFirst());

        int runStart = 0;

        for (int i = 1; i < dataPoints.size(); i++) {
            double currentVal = extractValue(dataPoints.get(i));
            double runStartVal = extractValue(dataPoints.get(runStart));

            if (Math.abs(currentVal - runStartVal) > EPSILON) {
                // Value changed — end the previous run
                if (runStart != i - 1) {
                    // Add the last point of the previous constant run
                    compressed.add(dataPoints.get(i - 1));
                }
                // Start a new run with the current point
                compressed.add(dataPoints.get(i));
                runStart = i;
            } else if (i == dataPoints.size() - 1) {
                // Last point — always include to close the final run
                compressed.add(dataPoints.get(i));
            }
        }

        return compressed;
    }

    private static double extractValue(final List<Object> dataPoint) {
        return ((Number) dataPoint.get(1)).doubleValue();
    }
}
