/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.dto.metrics;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.streamshub.mcp.common.util.metrics.MetricAggregation;
import io.streamshub.mcp.common.util.metrics.MetricLabelFilter;
import io.streamshub.mcp.common.util.metrics.TimeSeriesCompressor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * A metric time series that aggregates samples across dimensions based on an
 * {@link AggregationLevel}. Samples that differ only in stripped labels (e.g.,
 * different pods at BROKER level) are combined with the metric's
 * {@link MetricAggregation} function — the mean for most metrics, but a sum or a
 * maximum where averaging would contradict the metric's documented thresholds.
 *
 * @param name        the metric name
 * @param labels      the remaining labels after aggregation
 * @param dataPoints  the combined [epochSeconds, value] pairs (compressed)
 * @param summary     summary statistics computed from the combined data
 * @param sourceCount the number of distinct source series that were combined
 * @param compressed  true if constant-value runs were collapsed, null otherwise
 * @param aggregationFn the combining function, omitted when it is the default mean
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AggregatedTimeSeries(
    @JsonProperty("name") String name,
    @JsonProperty("labels") Map<String, String> labels,
    @JsonProperty("data_points") List<List<Object>> dataPoints,
    @JsonProperty("summary") TimeSeriesSummary summary,
    @JsonProperty("source_count") int sourceCount,
    @JsonProperty("compressed") Boolean compressed,
    @JsonProperty("aggregation_fn") String aggregationFn
) {

    /**
     * Builds a series combined with the default mean, leaving {@code aggregation_fn} off
     * the response.
     *
     * @param name        the metric name
     * @param labels      the remaining labels after aggregation
     * @param dataPoints  the combined [epochSeconds, value] pairs
     * @param summary     summary statistics
     * @param sourceCount the number of distinct source series combined
     * @param compressed  true if constant-value runs were collapsed, null otherwise
     */
    public AggregatedTimeSeries(final String name, final Map<String, String> labels,
                                 final List<List<Object>> dataPoints, final TimeSeriesSummary summary,
                                 final int sourceCount, final Boolean compressed) {
        this(name, labels, dataPoints, summary, sourceCount, compressed, null);
    }

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
            List<MetricSample> groupSamples = preferRollUp(entry.getValue(), level);
            Map<String, String> labels = groupLabels.get(entry.getKey());
            String metricName = groupSamples.getFirst().name();

            // Sub-group by timestamp, combine values at each timestamp
            Map<Long, List<Double>> byTimestamp = new TreeMap<>();
            for (MetricSample s : groupSamples) {
                long epoch = s.timestamp() != null ? s.timestamp().getEpochSecond() : 0L;
                byTimestamp.computeIfAbsent(epoch, k -> new ArrayList<>()).add(s.value());
            }

            MetricAggregation aggregation = MetricAggregation.forMetric(metricName);
            List<List<Object>> dataPoints = new ArrayList<>();
            int maxSources = 0;
            for (Map.Entry<Long, List<Double>> tsEntry : byTimestamp.entrySet()) {
                List<Double> values = tsEntry.getValue();
                dataPoints.add(List.of(tsEntry.getKey(), aggregation.reduce(values)));
                maxSources = Math.max(maxSources, values.size());
            }

            TimeSeriesSummary summary = TimeSeriesSummary.of(dataPoints);
            List<List<Object>> compressed = TimeSeriesCompressor.compress(dataPoints);
            Boolean wasCompressed = compressed.size() < dataPoints.size() ? Boolean.TRUE : null;

            // Only surface the function when it is not the default, so a client reading
            // a combined value across source_count series cannot mistake a sum for a mean.
            String fn = aggregation == MetricAggregation.AVG ? null : aggregation.name().toLowerCase(Locale.ROOT);

            result.add(new AggregatedTimeSeries(metricName, labels, compressed, summary,
                maxSources, wasCompressed, fn));
        }

        return List.copyOf(result);
    }

    /**
     * Drops a group's per-dimension parts when the source also publishes its own roll-up of them.
     *
     * <p>Kafka exposes {@code BrokerTopicMetrics} twice: once per topic and once as a broker
     * total with no {@code topic} label. Stripping {@code topic} gives both the same group key,
     * so without this the group combines a total with its own constituents — on one broker that
     * is eleven series where only one is the answer, and the mean of a total and its parts is
     * not a quantity at all.</p>
     *
     * <p>A sample missing the stripped dimension is by definition the coarser generation, so
     * when a group holds both, only the samples lacking the dimension are kept. Groups where
     * every sample carries the dimension (the normal case) or none does (pod, which every
     * scraped sample has) are left untouched.</p>
     *
     * @param groupSamples the samples sharing one aggregation key
     * @param level        the aggregation level, which decides the stripped dimensions
     * @return the samples to combine; the input list when no roll-up is present
     */
    private static List<MetricSample> preferRollUp(final List<MetricSample> groupSamples,
                                                    final AggregationLevel level) {
        List<MetricSample> remaining = groupSamples;
        for (String dimension : MetricLabelFilter.strippedDimensions(level)) {
            List<MetricSample> rollUps = remaining.stream()
                .filter(s -> s.labels() == null || !s.labels().containsKey(dimension))
                .toList();
            if (!rollUps.isEmpty() && rollUps.size() < remaining.size()) {
                remaining = rollUps;
            }
        }
        return remaining;
    }
}
