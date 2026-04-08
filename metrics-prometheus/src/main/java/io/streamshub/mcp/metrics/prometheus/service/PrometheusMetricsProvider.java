/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.metrics.prometheus.service;

import io.quarkus.arc.lookup.LookupIfProperty;
import io.streamshub.mcp.common.dto.metrics.MetricSample;
import io.streamshub.mcp.common.dto.metrics.MetricsQueryParams;
import io.streamshub.mcp.common.service.metrics.MetricsProvider;
import io.streamshub.mcp.common.util.metrics.MetricLabelFilter;
import io.streamshub.mcp.metrics.prometheus.dto.PrometheusResponse;
import io.streamshub.mcp.metrics.prometheus.util.PromQLSanitizer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Metrics provider that queries a Prometheus-compatible API (Prometheus, Thanos, etc.)
 * using PromQL. Supports both instant and range queries.
 */
@ApplicationScoped
@LookupIfProperty(name = "mcp.metrics.provider", stringValue = "streamshub-prometheus")
public class PrometheusMetricsProvider implements MetricsProvider {

    private static final Logger LOG = Logger.getLogger(PrometheusMetricsProvider.class);

    @Inject
    @RestClient
    Instance<PrometheusClient> prometheusClientInstance;

    PrometheusMetricsProvider() {
        // package-private no-arg constructor for CDI
    }

    @Override
    public List<MetricSample> queryMetrics(final MetricsQueryParams params) {
        if (prometheusClientInstance.isUnsatisfied()) {
            throw new IllegalStateException(
                "Prometheus REST client is not available. Check REST client configuration.");
        }

        PrometheusClient client = prometheusClientInstance.get();
        String promql = buildPromQL(params.metricNames(), params.labelMatchers());

        LOG.debugf("Executing PromQL query: %s", promql);

        PrometheusResponse response;
        if (params.isRangeQuery()) {
            String start = String.valueOf(params.startTime().getEpochSecond());
            String end = String.valueOf(params.endTime().getEpochSecond());
            String step = params.stepSeconds() + "s";
            response = client.rangeQuery(promql, start, end, step);
        } else {
            response = client.instantQuery(promql, null);
        }

        return convertResponse(response);
    }

    /**
     * Builds a PromQL query from metric names and label matchers.
     * Single metric: {@code metric_name{label1="val1"}}
     * Multiple metrics: {@code {__name__=~"m1|m2",label1="val1"}}
     *
     * @param metricNames   the metric names to query
     * @param labelMatchers the label matchers to apply
     * @return the PromQL query string
     */
    String buildPromQL(final List<String> metricNames, final Map<String, String> labelMatchers) {
        StringBuilder query = new StringBuilder();

        if (metricNames == null || metricNames.isEmpty()) {
            query.append("{}");
            return query.toString();
        }

        if (metricNames.size() == 1) {
            query.append(PromQLSanitizer.sanitizeMetricName(metricNames.get(0)));
            appendLabelMatchers(query, labelMatchers);
        } else {
            String nameRegex = metricNames.stream()
                .map(PromQLSanitizer::sanitizeMetricName)
                .collect(Collectors.joining("|"));
            query.append("{__name__=~\"").append(nameRegex).append("\"");
            if (labelMatchers != null && !labelMatchers.isEmpty()) {
                appendLabelMatchersInline(query, labelMatchers);
            }
            query.append("}");
        }

        return query.toString();
    }

    private void appendLabelMatchers(final StringBuilder query,
                                      final Map<String, String> labelMatchers) {
        if (labelMatchers == null || labelMatchers.isEmpty()) {
            return;
        }

        query.append("{");
        boolean first = true;
        for (Map.Entry<String, String> entry : labelMatchers.entrySet()) {
            if (!first) {
                query.append(",");
            }
            query.append(PromQLSanitizer.sanitizeLabelName(entry.getKey()));
            query.append("=\"");
            query.append(PromQLSanitizer.sanitizeLabelValue(entry.getValue()));
            query.append("\"");
            first = false;
        }
        query.append("}");
    }

    private void appendLabelMatchersInline(final StringBuilder query,
                                            final Map<String, String> labelMatchers) {
        for (Map.Entry<String, String> entry : labelMatchers.entrySet()) {
            query.append(",");
            query.append(PromQLSanitizer.sanitizeLabelName(entry.getKey()));
            query.append("=\"");
            query.append(PromQLSanitizer.sanitizeLabelValue(entry.getValue()));
            query.append("\"");
        }
    }

    private List<MetricSample> convertResponse(final PrometheusResponse response) {
        if (response == null || response.data() == null || response.data().result() == null) {
            return List.of();
        }

        if (!"success".equals(response.status())) {
            LOG.warnf("Prometheus query returned non-success status: %s", response.status());
            return List.of();
        }

        List<MetricSample> samples = new ArrayList<>();

        for (PrometheusResponse.PrometheusResult result : response.data().result()) {
            Map<String, String> labels = result.metric();
            String metricName = labels != null ? labels.getOrDefault("__name__", "") : "";

            if (result.values() != null) {
                // Range query result (matrix)
                for (List<Object> valueEntry : result.values()) {
                    addSample(samples, metricName, labels, valueEntry);
                }
            } else if (result.value() != null) {
                // Instant query result (vector)
                addSample(samples, metricName, labels, result.value());
            }
        }

        return samples;
    }

    private void addSample(final List<MetricSample> samples, final String metricName,
                            final Map<String, String> labels, final List<Object> valueEntry) {
        if (valueEntry.size() < 2) {
            return;
        }

        try {
            double timestamp = ((Number) valueEntry.get(0)).doubleValue();
            double value = Double.parseDouble(String.valueOf(valueEntry.get(1)));
            Instant instant = Instant.ofEpochSecond((long) timestamp);
            samples.add(MetricSample.of(metricName, MetricLabelFilter.filterLabels(labels),
                value, instant));
        } catch (Exception e) {
            LOG.debugf("Failed to parse Prometheus value entry: %s", e.getMessage());
        }
    }
}
