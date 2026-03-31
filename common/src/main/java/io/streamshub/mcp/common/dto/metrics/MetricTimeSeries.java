/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.dto.metrics;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A compact representation of a metric time series, grouping data points that share
 * the same metric name and labels. This avoids repeating label maps for every data point,
 * significantly reducing response size for range queries.
 *
 * @param name       the metric name
 * @param labels     the metric labels (shared across all data points)
 * @param dataPoints the time-value pairs as [epochSeconds, value] arrays
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MetricTimeSeries(
    @JsonProperty("name") String name,
    @JsonProperty("labels") Map<String, String> labels,
    @JsonProperty("data_points") List<List<Object>> dataPoints
) {

    /**
     * Groups a list of metric samples by name and labels into compact time series.
     * Samples without timestamps are returned as-is (not grouped).
     *
     * @param samples the metric samples to group
     * @return a list of grouped time series
     */
    public static List<MetricTimeSeries> fromSamples(final List<MetricSample> samples) {
        Map<String, MetricTimeSeries> seriesMap = new LinkedHashMap<>();

        for (MetricSample sample : samples) {
            String key = sample.name() + "|" + sample.labels();
            MetricTimeSeries series = seriesMap.get(key);

            if (series == null) {
                series = new MetricTimeSeries(sample.name(), sample.labels(), new ArrayList<>());
                seriesMap.put(key, series);
            }

            long epochSeconds = sample.timestamp() != null
                ? sample.timestamp().getEpochSecond() : 0L;
            series.dataPoints().add(List.of(epochSeconds, sample.value()));
        }

        return List.copyOf(seriesMap.values());
    }
}
