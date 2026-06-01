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
 * For single-point series (instant queries), only {@code latest} and
 * {@code originalDataPointCount} are populated; other fields are null to
 * reduce response size.
 *
 * @param min                    the minimum value in the series (null for single-point)
 * @param max                    the maximum value in the series (null for single-point)
 * @param avg                    the average value across all data points (null for single-point)
 * @param latest                 the most recent value in the series
 * @param delta                  the change from first to last value (null for single-point)
 * @param originalDataPointCount the number of data points before compression
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TimeSeriesSummary(
    @JsonProperty("min") Double min,
    @JsonProperty("max") Double max,
    @JsonProperty("avg") Double avg,
    @JsonProperty("latest") double latest,
    @JsonProperty("delta") Double delta,
    @JsonProperty("original_data_point_count") int originalDataPointCount
) {

    /**
     * Computes summary statistics from a list of time-value data points.
     * Each data point is a {@code [epochSeconds, value]} list.
     * For single-point series, only {@code latest} is populated.
     *
     * @param dataPoints the raw data points to summarize
     * @return the computed summary, or null if the list is empty
     */
    public static TimeSeriesSummary of(final List<List<Object>> dataPoints) {
        if (dataPoints == null || dataPoints.isEmpty()) {
            return null;
        }

        double lastVal = extractValue(dataPoints.getLast());

        if (dataPoints.size() == 1) {
            return new TimeSeriesSummary(null, null, null, lastVal, null, 1);
        }

        double minVal = Double.MAX_VALUE;
        double maxVal = -Double.MAX_VALUE;
        double sum = 0.0;
        double firstVal = extractValue(dataPoints.getFirst());

        for (List<Object> dp : dataPoints) {
            double val = extractValue(dp);
            if (val < minVal) {
                minVal = val;
            }
            if (val > maxVal) {
                maxVal = val;
            }
            sum += val;
        }

        double avg = sum / dataPoints.size();
        return new TimeSeriesSummary(minVal, maxVal, avg, lastVal, lastVal - firstVal,
            dataPoints.size());
    }

    private static double extractValue(final List<Object> dataPoint) {
        return ((Number) dataPoint.get(1)).doubleValue();
    }
}
