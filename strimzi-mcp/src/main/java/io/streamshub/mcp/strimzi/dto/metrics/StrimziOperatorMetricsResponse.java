/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.dto.metrics;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.streamshub.mcp.common.dto.metrics.MetricSample;
import io.streamshub.mcp.common.dto.metrics.MetricTimeSeries;

import java.time.Instant;
import java.util.List;

/**
 * Response containing metrics data from a Strimzi cluster operator.
 * For instant queries, metrics are returned as a flat list in {@code metrics}.
 * For range queries, metrics are grouped into compact time series in {@code timeSeries}.
 *
 * @param operatorName  the operator deployment name
 * @param namespace     the Kubernetes namespace
 * @param provider      the metrics provider used
 * @param categories    the metric categories requested
 * @param metricCount   the number of distinct metric names in the response
 * @param sampleCount   the total number of metric samples
 * @param metrics       the flat list of metric samples (instant queries)
 * @param timeSeries    the grouped time series (range queries)
 * @param interpretation brief guide for interpreting the returned metrics
 * @param timestamp     the time this result was generated
 * @param message       a human-readable summary of the result
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record StrimziOperatorMetricsResponse(
    @JsonProperty("operator_name") String operatorName,
    @JsonProperty("namespace") String namespace,
    @JsonProperty("provider") String provider,
    @JsonProperty("categories") List<String> categories,
    @JsonProperty("metric_count") long metricCount,
    @JsonProperty("sample_count") int sampleCount,
    @JsonProperty("metrics") List<MetricSample> metrics,
    @JsonProperty("time_series") List<MetricTimeSeries> timeSeries,
    @JsonProperty("interpretation") String interpretation,
    @JsonProperty("timestamp") Instant timestamp,
    @JsonProperty("message") String message
) {

    /**
     * Creates a response with metric data. If samples have timestamps (range query),
     * groups them into compact time series. Otherwise returns a flat metrics list.
     *
     * @param operatorName   the operator deployment name
     * @param namespace      the Kubernetes namespace
     * @param provider       the metrics provider name
     * @param categories     the requested categories
     * @param samples        the metric samples
     * @param interpretation brief guide for interpreting the returned metrics
     * @return a response with the metric data
     */
    public static StrimziOperatorMetricsResponse of(final String operatorName, final String namespace,
                                                     final String provider, final List<String> categories,
                                                     final List<MetricSample> samples,
                                                     final String interpretation) {
        long metricCount = samples.stream()
            .map(MetricSample::name)
            .distinct()
            .count();

        String msg = String.format("Retrieved %d samples across %d metrics from operator '%s'",
            samples.size(), metricCount, operatorName);

        boolean isRangeData = !samples.isEmpty() && samples.getFirst().timestamp() != null;

        if (isRangeData) {
            List<MetricTimeSeries> series = MetricTimeSeries.fromSamples(samples);
            return new StrimziOperatorMetricsResponse(operatorName, namespace, provider, categories,
                metricCount, samples.size(), null, series, interpretation, Instant.now(), msg);
        }

        return new StrimziOperatorMetricsResponse(operatorName, namespace, provider, categories,
            metricCount, samples.size(), samples, null, interpretation, Instant.now(), msg);
    }

    /**
     * Creates an empty response when no metrics are available.
     *
     * @param operatorName the operator deployment name
     * @param namespace    the Kubernetes namespace
     * @param message      descriptive message explaining why no metrics are available
     * @return an empty response
     */
    public static StrimziOperatorMetricsResponse empty(final String operatorName, final String namespace,
                                                        final String message) {
        return new StrimziOperatorMetricsResponse(operatorName, namespace, null, List.of(),
            0, 0, List.of(), null, null, Instant.now(), message);
    }
}
