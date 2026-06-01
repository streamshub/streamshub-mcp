/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.util.metrics;

import io.streamshub.mcp.common.dto.metrics.AggregatedTimeSeries;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Extracts labels that are identical across all time series in a response,
 * factoring them into a shared {@code commonLabels} map and stripping them
 * from individual series to reduce response size.
 */
public final class CommonLabelExtractor {

    private CommonLabelExtractor() {
    }

    /**
     * Result of common label extraction.
     *
     * @param commonLabels   labels shared by all time series (key→value identical across every series)
     * @param strippedSeries the time series with common labels removed from each
     */
    public record Result(
        Map<String, String> commonLabels,
        List<AggregatedTimeSeries> strippedSeries
    ) {
    }

    /**
     * Extracts common labels from a list of aggregated time series.
     *
     * @param series the time series to process
     * @return the extraction result with common labels and stripped series
     */
    public static Result extract(final List<AggregatedTimeSeries> series) {
        if (series == null || series.isEmpty()) {
            return new Result(Map.of(), series == null ? List.of() : series);
        }

        if (series.size() == 1) {
            Map<String, String> labels = series.getFirst().labels();
            if (labels == null || labels.isEmpty()) {
                return new Result(Map.of(), series);
            }
            Map<String, String> common = new LinkedHashMap<>(labels);
            AggregatedTimeSeries stripped = new AggregatedTimeSeries(
                series.getFirst().name(), Map.of(),
                series.getFirst().dataPoints(), series.getFirst().summary(),
                series.getFirst().sourceCount(), series.getFirst().compressed());
            return new Result(common, List.of(stripped));
        }

        // Find labels present in ALL series with identical values
        Map<String, String> candidate = new LinkedHashMap<>();
        Map<String, String> firstLabels = series.getFirst().labels();
        if (firstLabels != null) {
            candidate.putAll(firstLabels);
        }

        for (int i = 1; i < series.size() && !candidate.isEmpty(); i++) {
            Map<String, String> labels = series.get(i).labels();
            if (labels == null || labels.isEmpty()) {
                candidate.clear();
                break;
            }
            candidate.entrySet().removeIf(entry ->
                !entry.getValue().equals(labels.get(entry.getKey())));
        }

        if (candidate.isEmpty()) {
            return new Result(Map.of(), series);
        }

        // Strip common labels from each series
        List<AggregatedTimeSeries> stripped = new ArrayList<>(series.size());
        for (AggregatedTimeSeries ts : series) {
            Map<String, String> remaining = new LinkedHashMap<>();
            if (ts.labels() != null) {
                for (Map.Entry<String, String> entry : ts.labels().entrySet()) {
                    if (!candidate.containsKey(entry.getKey())) {
                        remaining.put(entry.getKey(), entry.getValue());
                    }
                }
            }
            stripped.add(new AggregatedTimeSeries(
                ts.name(), remaining, ts.dataPoints(), ts.summary(),
                ts.sourceCount(), ts.compressed()));
        }

        return new Result(Map.copyOf(candidate), List.copyOf(stripped));
    }
}
