/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.dto.metrics;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.streamshub.mcp.common.dto.metrics.AggregatedTimeSeries;
import io.streamshub.mcp.common.dto.metrics.AggregationLevel;
import io.streamshub.mcp.common.dto.metrics.MetricSample;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * Response containing metrics data from a Kafka cluster.
 * Metrics are always grouped and aggregated into time series based on the
 * configured {@link AggregationLevel}.
 *
 * @param clusterName    the Kafka cluster name
 * @param namespace      the Kubernetes namespace
 * @param provider       the metrics provider used
 * @param categories     the metric categories requested
 * @param metricCount    the number of distinct metric names in the response
 * @param sampleCount    the total number of metric samples before aggregation
 * @param aggregation    the aggregation level used
 * @param timeSeries     the aggregated time series
 * @param interpretation brief guide for interpreting the returned metrics
 * @param timestamp      the time this result was generated
 * @param message        a human-readable summary of the result
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record KafkaMetricsResponse(
    @JsonProperty("cluster_name") String clusterName,
    @JsonProperty("namespace") String namespace,
    @JsonProperty("provider") String provider,
    @JsonProperty("categories") List<String> categories,
    @JsonProperty("metric_count") long metricCount,
    @JsonProperty("sample_count") int sampleCount,
    @JsonProperty("aggregation") String aggregation,
    @JsonProperty("time_series") List<AggregatedTimeSeries> timeSeries,
    @JsonProperty("interpretation") String interpretation,
    @JsonProperty("timestamp") Instant timestamp,
    @JsonProperty("message") String message
) {

    /**
     * Creates a response with aggregated metric data.
     *
     * @param clusterName    the Kafka cluster name
     * @param namespace      the Kubernetes namespace
     * @param provider       the metrics provider name
     * @param categories     the requested categories
     * @param samples        the metric samples
     * @param interpretation brief guide for interpreting the returned metrics
     * @param level          the aggregation level
     * @return a response with the aggregated metric data
     */
    public static KafkaMetricsResponse of(final String clusterName, final String namespace,
                                           final String provider, final List<String> categories,
                                           final List<MetricSample> samples,
                                           final String interpretation,
                                           final AggregationLevel level) {
        long metricCount = samples.stream()
            .map(MetricSample::name)
            .distinct()
            .count();

        String msg = String.format("Retrieved %d samples across %d metrics from cluster '%s'",
            samples.size(), metricCount, clusterName);

        List<AggregatedTimeSeries> series = samples.isEmpty()
            ? List.of() : AggregatedTimeSeries.fromSamples(samples, level);

        return new KafkaMetricsResponse(clusterName, namespace, provider, categories,
            metricCount, samples.size(), level.name().toLowerCase(Locale.ROOT),
            series, interpretation, Instant.now(), msg);
    }

    /**
     * Creates a response from pre-grouped time series (avoids re-aggregating).
     *
     * @param clusterName    the Kafka cluster name
     * @param namespace      the Kubernetes namespace
     * @param provider       the metrics provider name
     * @param categories     the requested categories
     * @param series         the pre-grouped time series
     * @param interpretation brief guide for interpreting the returned metrics
     * @return a response wrapping the provided time series
     */
    public static KafkaMetricsResponse ofTimeSeries(final String clusterName, final String namespace,
                                                     final String provider, final List<String> categories,
                                                     final List<AggregatedTimeSeries> series,
                                                     final String interpretation) {
        long metricCount = series.stream()
            .map(AggregatedTimeSeries::name)
            .distinct()
            .count();
        int sampleCount = series.stream()
            .mapToInt(ts -> ts.summary() != null
                ? ts.summary().originalDataPointCount() : ts.dataPoints().size())
            .sum();

        String msg = String.format("Retrieved %d samples across %d metrics from cluster '%s'",
            sampleCount, metricCount, clusterName);

        return new KafkaMetricsResponse(clusterName, namespace, provider, categories,
            metricCount, sampleCount, null, series, interpretation, Instant.now(), msg);
    }

    /**
     * Creates an empty response when no metrics are available.
     *
     * @param clusterName the Kafka cluster name
     * @param namespace   the Kubernetes namespace
     * @param message     descriptive message explaining why no metrics are available
     * @return an empty response
     */
    public static KafkaMetricsResponse empty(final String clusterName, final String namespace,
                                              final String message) {
        return new KafkaMetricsResponse(clusterName, namespace, null, List.of(),
            0, 0, null, List.of(), null, Instant.now(), message);
    }
}
