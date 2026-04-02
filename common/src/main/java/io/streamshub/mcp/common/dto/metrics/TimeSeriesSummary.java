/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.dto.metrics;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Summary statistics for a metric time series. Provides a compact overview of
 * the data without requiring the LLM to process every individual data point.
 *
 * @param min                    the minimum value in the series
 * @param max                    the maximum value in the series
 * @param avg                    the average value across all data points
 * @param latest                 the most recent value in the series
 * @param delta                  the change from first to last value (last - first)
 * @param originalDataPointCount the number of data points before compression
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TimeSeriesSummary(
    @JsonProperty("min") double min,
    @JsonProperty("max") double max,
    @JsonProperty("avg") double avg,
    @JsonProperty("latest") double latest,
    @JsonProperty("delta") double delta,
    @JsonProperty("original_data_point_count") int originalDataPointCount
) {

    /**
     * Computes summary statistics from a list of time-value data points.
     * Each data point is a {@code [epochSeconds, value]} list.
     *
     * @param dataPoints the raw data points to summarize
     * @return the computed summary, or null if the list is empty
     */
    public static TimeSeriesSummary of(final List<List<Object>> dataPoints) {
        if (dataPoints == null || dataPoints.isEmpty()) {
            return null;
        }

        double minVal = Double.MAX_VALUE;
        double maxVal = -Double.MAX_VALUE;
        double sum = 0.0;
        double firstVal = extractValue(dataPoints.getFirst());
        double lastVal = firstVal;

        for (List<Object> dp : dataPoints) {
            double val = extractValue(dp);
            if (val < minVal) {
                minVal = val;
            }
            if (val > maxVal) {
                maxVal = val;
            }
            sum += val;
            lastVal = val;
        }

        double avg = sum / dataPoints.size();
        return new TimeSeriesSummary(minVal, maxVal, avg, lastVal, lastVal - firstVal,
            dataPoints.size());
    }

    private static double extractValue(final List<Object> dataPoint) {
        return ((Number) dataPoint.get(1)).doubleValue();
    }
}
