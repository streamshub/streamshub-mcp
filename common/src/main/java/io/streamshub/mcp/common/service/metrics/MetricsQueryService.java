/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.service.metrics;

import io.streamshub.mcp.common.dto.metrics.MetricSample;
import io.streamshub.mcp.common.dto.metrics.MetricsQueryParams;
import io.streamshub.mcp.common.dto.metrics.PodTarget;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * General-purpose metrics query service that delegates to a pluggable {@link MetricsProvider}.
 * Domain-specific services (Kafka, operator, etc.) use this service to execute metric queries
 * without needing to manage provider lookup or query parameter construction.
 */
@ApplicationScoped
public class MetricsQueryService {

    private static final Logger LOG = Logger.getLogger(MetricsQueryService.class);
    private static final int SECONDS_PER_MINUTE = 60;

    @Inject
    Instance<MetricsProvider> metricsProviderInstance;

    @ConfigProperty(name = "mcp.metrics.provider", defaultValue = "streamshub-pod-scraping")
    String providerName;

    @ConfigProperty(name = "mcp.metrics.default-step-seconds", defaultValue = "60")
    int defaultStepSeconds;

    MetricsQueryService() {
        // package-private no-arg constructor for CDI
    }

    /**
     * Returns the configured metrics provider name.
     *
     * @return the provider name (e.g. "streamshub-pod-scraping", "streamshub-prometheus")
     */
    public String providerName() {
        return providerName;
    }

    /**
     * Queries metrics using the configured provider.
     *
     * @param podTargets    pod endpoints to scrape (used by pod-scraping provider)
     * @param labelMatchers label key-value pairs for Prometheus query filtering
     * @param metricNames   the metric names to retrieve
     * @param rangeMinutes  range query duration in minutes (null for instant query)
     * @param stepSeconds   range query step interval in seconds (null for default)
     * @return a list of metric samples matching the query
     */
    public List<MetricSample> queryMetrics(final List<PodTarget> podTargets,
                                            final Map<String, String> labelMatchers,
                                            final List<String> metricNames,
                                            final Integer rangeMinutes,
                                            final Integer stepSeconds) {
        requireProvider();

        MetricsQueryParams params = buildParams(podTargets, labelMatchers, metricNames,
            rangeMinutes, stepSeconds);

        LOG.debugf("Querying metrics: %d metric names, %d pod targets (provider=%s)",
            metricNames.size(), podTargets.size(), providerName);

        return metricsProviderInstance.get().queryMetrics(params);
    }

    private void requireProvider() {
        if (metricsProviderInstance.isUnsatisfied()) {
            throw new IllegalStateException(
                "No metrics provider configured."
                    + " Set 'mcp.metrics.provider' to 'streamshub-pod-scraping' or 'streamshub-prometheus'.");
        }
    }

    private MetricsQueryParams buildParams(final List<PodTarget> podTargets,
                                            final Map<String, String> labelMatchers,
                                            final List<String> metricNames,
                                            final Integer rangeMinutes,
                                            final Integer stepSeconds) {
        if (rangeMinutes != null && rangeMinutes > 0) {
            Instant end = Instant.now();
            Instant start = end.minusSeconds((long) rangeMinutes * SECONDS_PER_MINUTE);
            int step = stepSeconds != null ? stepSeconds : defaultStepSeconds;
            return MetricsQueryParams.range(metricNames, labelMatchers, start, end, step);
        }
        return MetricsQueryParams.instant(metricNames, labelMatchers, podTargets);
    }
}
