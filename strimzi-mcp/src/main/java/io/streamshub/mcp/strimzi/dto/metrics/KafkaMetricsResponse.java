/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.dto.metrics;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.streamshub.mcp.common.dto.metrics.MetricSample;

import java.time.Instant;
import java.util.List;

/**
 * Response containing metrics data from a Kafka cluster.
 *
 * @param clusterName the Kafka cluster name
 * @param namespace   the Kubernetes namespace
 * @param provider    the metrics provider used (e.g. "pod-scraping", "prometheus")
 * @param categories  the metric categories requested
 * @param metricCount the number of distinct metric names in the response
 * @param sampleCount the total number of metric samples
 * @param metrics     the list of metric samples
 * @param timestamp   the time this result was generated
 * @param message     a human-readable summary of the result
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record KafkaMetricsResponse(
    @JsonProperty("cluster_name") String clusterName,
    @JsonProperty("namespace") String namespace,
    @JsonProperty("provider") String provider,
    @JsonProperty("categories") List<String> categories,
    @JsonProperty("metric_count") long metricCount,
    @JsonProperty("sample_count") int sampleCount,
    @JsonProperty("metrics") List<MetricSample> metrics,
    @JsonProperty("timestamp") Instant timestamp,
    @JsonProperty("message") String message
) {

    /**
     * Creates a response with metric data.
     *
     * @param clusterName the Kafka cluster name
     * @param namespace   the Kubernetes namespace
     * @param provider    the metrics provider name
     * @param categories  the requested categories
     * @param metrics     the metric samples
     * @return a response with the metric data
     */
    public static KafkaMetricsResponse of(final String clusterName, final String namespace,
                                           final String provider, final List<String> categories,
                                           final List<MetricSample> metrics) {
        long metricCount = metrics.stream()
            .map(MetricSample::name)
            .distinct()
            .count();

        String msg = String.format("Retrieved %d samples across %d metrics from cluster '%s'",
            metrics.size(), metricCount, clusterName);

        return new KafkaMetricsResponse(clusterName, namespace, provider, categories,
            metricCount, metrics.size(), metrics, Instant.now(), msg);
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
            0, 0, List.of(), Instant.now(), message);
    }
}
