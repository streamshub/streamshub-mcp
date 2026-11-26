/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.metrics.prometheus.service;

import io.quarkus.arc.lookup.LookupIfProperty;
import io.streamshub.mcp.common.dto.metrics.MetricSample;
import io.streamshub.mcp.common.dto.metrics.MetricsQueryParams;
import io.streamshub.mcp.common.service.metrics.MetricsProvider;
import io.streamshub.mcp.common.service.metrics.MetricsQueryException;
import io.streamshub.mcp.common.util.ExceptionUtils;
import io.streamshub.mcp.common.util.metrics.MetricLabelFilter;
import io.streamshub.mcp.common.util.metrics.MetricNameSuffixes;
import io.streamshub.mcp.common.util.metrics.PerMetricSampleLimit;
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

    private static final String DEFAULT_RATE_WINDOW = "5m";

    @Override
    public List<MetricSample> queryMetrics(final MetricsQueryParams params) {
        if (prometheusClientInstance.isUnsatisfied()) {
            throw new IllegalStateException(
                "Prometheus REST client is not available. Check REST client configuration.");
        }

        PrometheusClient client = prometheusClientInstance.get();

        List<String> counterMetrics = new ArrayList<>();
        List<String> gaugeMetrics = new ArrayList<>();
        partitionMetrics(params.metricNames(), counterMetrics, gaugeMetrics);

        List<MetricSample> allSamples = new ArrayList<>();

        if (!gaugeMetrics.isEmpty()) {
            String promql = buildPromQL(gaugeMetrics, params.labelMatchers(), params.labelRegexMatchers());
            LOG.debugf("Executing PromQL query (gauges): %s", promql);
            PrometheusResponse response = executeQuery(client, promql, params);
            allSamples.addAll(convertResponse(response, params.maxSamples(), null));
        }

        if (!counterMetrics.isEmpty()) {
            allSamples.addAll(queryCountersWithRate(client, counterMetrics, params));
        }

        return allSamples;
    }

    private List<MetricSample> queryCountersWithRate(final PrometheusClient client,
                                                      final List<String> counterMetrics,
                                                      final MetricsQueryParams params) {
        String rateWindow = params.isRangeQuery()
            ? params.stepSeconds() + "s"
            : DEFAULT_RATE_WINDOW;
        String rateFunc = params.isRangeQuery() ? "rate" : "irate";

        List<MetricSample> result = new ArrayList<>();
        for (String metric : counterMetrics) {
            String inner = buildPromQL(List.of(metric), params.labelMatchers(), params.labelRegexMatchers());
            String promql = rateFunc + "(" + inner + "[" + rateWindow + "])";
            LOG.debugf("Executing PromQL query (counter rate): %s", promql);

            PrometheusResponse response = executeQuery(client, promql, params);
            result.addAll(convertResponse(response, params.maxSamples(),
                MetricNameSuffixes.toRate(metric)));
        }
        return result;
    }

    private PrometheusResponse executeQuery(final PrometheusClient client,
                                             final String promql,
                                             final MetricsQueryParams params) {
        try {
            if (params.isRangeQuery()) {
                String start = String.valueOf(params.startTime().getEpochSecond());
                String end = String.valueOf(params.endTime().getEpochSecond());
                String step = params.stepSeconds() + "s";
                return client.rangeQuery(promql, start, end, step);
            }
            return client.instantQuery(promql, null);
        } catch (MetricsQueryException e) {
            throw e;
        } catch (Exception e) {
            LOG.warnf("Failed to query Prometheus: %s", e.getMessage());
            throw new MetricsQueryException(
                String.format("Failed to query Prometheus at configured URL: %s",
                    ExceptionUtils.rootCauseMessage(e)), e);
        }
    }

    static void partitionMetrics(final List<String> metricNames,
                                  final List<String> counters,
                                  final List<String> gauges) {
        if (metricNames == null) {
            return;
        }
        for (String name : metricNames) {
            if (name.endsWith(MetricNameSuffixes.TOTAL)) {
                counters.add(name);
            } else {
                gauges.add(name);
            }
        }
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
        return buildPromQL(metricNames, labelMatchers, Map.of());
    }

    /**
     * Builds a PromQL query from metric names, exact label matchers and regex label matchers
     * ({@code label=~"regex"}).
     *
     * @param metricNames   the metric names to query
     * @param labelMatchers the exact label matchers to apply
     * @param regexMatchers the regex label matchers to apply
     * @return the PromQL query string
     */
    String buildPromQL(final List<String> metricNames, final Map<String, String> labelMatchers,
                       final Map<String, String> regexMatchers) {
        Map<String, String> exact = labelMatchers != null ? labelMatchers : Map.of();
        Map<String, String> regex = regexMatchers != null ? regexMatchers : Map.of();
        StringBuilder query = new StringBuilder();

        if (metricNames == null || metricNames.isEmpty()) {
            query.append("{}");
            return query.toString();
        }

        List<String> matchers = new ArrayList<>();
        exact.forEach((label, value) -> matchers.add(matcher(label, "=", value)));
        regex.forEach((label, value) -> matchers.add(matcher(label, "=~", value)));

        if (metricNames.size() == 1) {
            query.append(PromQLSanitizer.sanitizeMetricName(metricNames.get(0)));
            if (!matchers.isEmpty()) {
                query.append("{").append(String.join(",", matchers)).append("}");
            }
        } else {
            String nameRegex = metricNames.stream()
                .map(PromQLSanitizer::sanitizeMetricName)
                .collect(Collectors.joining("|"));
            query.append("{__name__=~\"").append(nameRegex).append("\"");
            matchers.forEach(m -> query.append(",").append(m));
            query.append("}");
        }

        return query.toString();
    }

    private static String matcher(final String label, final String operator, final String value) {
        return PromQLSanitizer.sanitizeLabelName(label) + operator + "\""
            + PromQLSanitizer.sanitizeLabelValue(value) + "\"";
    }

    /**
     * Converts a query result, keeping at most {@code maxSamples} samples of each metric name.
     *
     * <p>One gauge query covers several metrics, so each name is capped separately: the first,
     * largest one cannot crowd out the rest. A value that cannot be parsed (for example
     * {@code +Inf}) does not count against the limit.</p>
     *
     * @param response   the Prometheus response
     * @param maxSamples the per-metric sample limit, or 0 or less for no limit
     * @param fixedName  the name to give every sample, or null to use each series' {@code __name__}
     * @return the converted samples
     */
    private List<MetricSample> convertResponse(final PrometheusResponse response,
                                                final int maxSamples,
                                                final String fixedName) {
        validateResponse(response);

        if (response.data().result() == null || response.data().result().isEmpty()) {
            return List.of();
        }

        List<MetricSample> samples = new ArrayList<>();
        PerMetricSampleLimit limit = new PerMetricSampleLimit(maxSamples);

        for (PrometheusResponse.PrometheusResult result : response.data().result()) {
            Map<String, String> labels = result.metric();
            String metricName = fixedName;
            if (metricName == null) {
                metricName = labels != null ? labels.getOrDefault("__name__", "") : "";
            }

            if (result.values() != null) {
                // Range query result (matrix)
                for (List<Object> valueEntry : result.values()) {
                    addSample(samples, limit, metricName, labels, valueEntry);
                }
            } else if (result.value() != null) {
                // Instant query result (vector)
                addSample(samples, limit, metricName, labels, result.value());
            }
        }

        if (!limit.truncatedMetrics().isEmpty()) {
            LOG.warnf("Prometheus query reached the per-metric max-samples limit (%d) for: %s",
                maxSamples, limit.truncatedMetrics());
        }
        return samples;
    }

    private void validateResponse(final PrometheusResponse response) {
        if (response == null || response.data() == null) {
            throw new MetricsQueryException("Prometheus returned no response data. "
                + "Check Prometheus URL configuration and connectivity.");
        }
        if (!"success".equals(response.status())) {
            throw new MetricsQueryException(
                String.format("Prometheus query failed with status '%s'", response.status()));
        }
    }

    private void addSample(final List<MetricSample> samples, final PerMetricSampleLimit limit,
                            final String metricName, final Map<String, String> labels,
                            final List<Object> valueEntry) {
        if (valueEntry.size() < 2) {
            return;
        }

        MetricSample sample;
        try {
            double timestamp = ((Number) valueEntry.get(0)).doubleValue();
            double value = Double.parseDouble(String.valueOf(valueEntry.get(1)));
            Instant instant = Instant.ofEpochSecond((long) timestamp);
            sample = MetricSample.of(metricName, MetricLabelFilter.filterLabels(labels), value, instant);
        } catch (Exception e) {
            LOG.debugf("Failed to parse Prometheus value entry: %s", e.getMessage());
            return;
        }
        if (limit.tryAccept(metricName)) {
            samples.add(sample);
        }
    }
}
