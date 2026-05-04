/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.dto.metrics;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.streamshub.mcp.common.util.metrics.MetricLabelFilter;
import io.streamshub.mcp.common.util.metrics.TimeSeriesCompressor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * A metric time series that aggregates samples across dimensions based on an
 * {@link AggregationLevel}. Samples that differ only in stripped labels (e.g.,
 * different pods at BROKER level) have their values averaged together.
 *
 * @param name        the metric name
 * @param labels      the remaining labels after aggregation
 * @param dataPoints  the averaged [epochSeconds, value] pairs (compressed)
 * @param summary     summary statistics computed from the averaged data
 * @param sourceCount the number of distinct source series that were averaged
 * @param compressed  true if constant-value runs were collapsed, null otherwise
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AggregatedTimeSeries(
    @JsonProperty("name") String name,
    @JsonProperty("labels") Map<String, String> labels,
    @JsonProperty("data_points") List<List<Object>> dataPoints,
    @JsonProperty("summary") TimeSeriesSummary summary,
    @JsonProperty("source_count") int sourceCount,
    @JsonProperty("compressed") Boolean compressed
) {

    /**
     * Groups and aggregates metric samples by name and labels at the given
     * aggregation level. Samples that share the same name and aggregated labels
     * have their values averaged per timestamp.
     *
     * @param samples the metric samples to aggregate
     * @param level   the aggregation level controlling which labels are stripped
     * @return a list of aggregated time series
     */
    public static List<AggregatedTimeSeries> fromSamples(final List<MetricSample> samples,
                                                          final AggregationLevel level) {
        if (samples == null || samples.isEmpty()) {
            return List.of();
        }

        // Group samples by aggregation key (name + filtered labels)
        Map<String, List<MetricSample>> groups = new LinkedHashMap<>();
        Map<String, Map<String, String>> groupLabels = new LinkedHashMap<>();

        for (MetricSample sample : samples) {
            Map<String, String> filtered = MetricLabelFilter.labelsForAggregation(sample.labels(), level);
            String key = sample.name() + "|" + filtered;
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(sample);
            groupLabels.putIfAbsent(key, filtered);
        }

        List<AggregatedTimeSeries> result = new ArrayList<>();

        for (Map.Entry<String, List<MetricSample>> entry : groups.entrySet()) {
            List<MetricSample> groupSamples = entry.getValue();
            Map<String, String> labels = groupLabels.get(entry.getKey());
            String metricName = groupSamples.getFirst().name();

            // Sub-group by timestamp, average values at each timestamp
            Map<Long, List<Double>> byTimestamp = new TreeMap<>();
            for (MetricSample s : groupSamples) {
                long epoch = s.timestamp() != null ? s.timestamp().getEpochSecond() : 0L;
                byTimestamp.computeIfAbsent(epoch, k -> new ArrayList<>()).add(s.value());
            }

            List<List<Object>> dataPoints = new ArrayList<>();
            int maxSources = 0;
            for (Map.Entry<Long, List<Double>> tsEntry : byTimestamp.entrySet()) {
                List<Double> values = tsEntry.getValue();
                double avg = values.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
                dataPoints.add(List.of(tsEntry.getKey(), avg));
                maxSources = Math.max(maxSources, values.size());
            }

            TimeSeriesSummary summary = TimeSeriesSummary.of(dataPoints);
            List<List<Object>> compressed = TimeSeriesCompressor.compress(dataPoints);
            Boolean wasCompressed = compressed.size() < dataPoints.size() ? Boolean.TRUE : null;

            result.add(new AggregatedTimeSeries(metricName, labels, compressed, summary,
                maxSources, wasCompressed));
        }

        return List.copyOf(result);
    }
}
