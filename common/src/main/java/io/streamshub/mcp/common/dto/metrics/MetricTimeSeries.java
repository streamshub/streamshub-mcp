/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.dto.metrics;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.streamshub.mcp.common.util.metrics.TimeSeriesCompressor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A compact representation of a metric time series, grouping data points that share
 * the same metric name and labels. Consecutive data points with the same value are
 * compressed to keep only the first and last of each constant run, and per-series
 * summary statistics are computed to give the LLM a quick overview.
 *
 * @param name       the metric name
 * @param labels     the metric labels (shared across all data points)
 * @param dataPoints the time-value pairs as [epochSeconds, value] arrays (compressed)
 * @param summary    summary statistics computed from the original (uncompressed) data
 * @param compressed true if constant-value runs were collapsed, null otherwise
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MetricTimeSeries(
    @JsonProperty("name") String name,
    @JsonProperty("labels") Map<String, String> labels,
    @JsonProperty("data_points") List<List<Object>> dataPoints,
    @JsonProperty("summary") TimeSeriesSummary summary,
    @JsonProperty("compressed") Boolean compressed
) {

    /**
     * Groups a list of metric samples by name and labels into compact time series.
     * Each series is compressed by collapsing constant-value runs and enriched with
     * summary statistics (min, max, avg, latest, delta).
     *
     * @param samples the metric samples to group
     * @return a list of grouped, compressed time series with summaries
     */
    public static List<MetricTimeSeries> fromSamples(final List<MetricSample> samples) {
        Map<String, List<List<Object>>> rawSeriesMap = new LinkedHashMap<>();
        Map<String, MetricSample> firstSampleMap = new LinkedHashMap<>();

        for (MetricSample sample : samples) {
            String key = sample.name() + "|" + sample.labels();
            rawSeriesMap.computeIfAbsent(key, k -> new ArrayList<>());
            firstSampleMap.putIfAbsent(key, sample);

            long epochSeconds = sample.timestamp() != null
                ? sample.timestamp().getEpochSecond() : 0L;
            rawSeriesMap.get(key).add(List.of(epochSeconds, sample.value()));
        }

        List<MetricTimeSeries> result = new ArrayList<>();
        for (Map.Entry<String, List<List<Object>>> entry : rawSeriesMap.entrySet()) {
            MetricSample firstSample = firstSampleMap.get(entry.getKey());
            List<List<Object>> originalPoints = entry.getValue();

            TimeSeriesSummary summary = TimeSeriesSummary.of(originalPoints);
            List<List<Object>> compressedPoints = TimeSeriesCompressor.compress(originalPoints);
            Boolean wasCompressed = compressedPoints.size() < originalPoints.size()
                ? Boolean.TRUE : null;

            result.add(new MetricTimeSeries(firstSample.name(), firstSample.labels(),
                compressedPoints, summary, wasCompressed));
        }

        return List.copyOf(result);
    }
}
