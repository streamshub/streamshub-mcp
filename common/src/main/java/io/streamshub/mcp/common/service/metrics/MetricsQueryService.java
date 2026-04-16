/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.service.metrics;

import io.streamshub.mcp.common.dto.metrics.MetricSample;
import io.streamshub.mcp.common.dto.metrics.MetricsQueryParams;
import io.streamshub.mcp.common.dto.metrics.PodTarget;
import jakarta.annotation.PostConstruct;
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

    @ConfigProperty(name = "mcp.metrics.max-range-minutes", defaultValue = "10080")  // 7 days
    int maxRangeMinutes;

    MetricsQueryService() {
        // package-private no-arg constructor for CDI
    }

    /**
     * Validates metrics provider configuration at startup.
     * Logs a warning if no provider is configured.
     */
    @PostConstruct
    void validateConfiguration() {
        if (metricsProviderInstance.isUnsatisfied()) {
            LOG.warnf("No metrics provider configured. Set 'mcp.metrics.provider' to "
                + "'streamshub-pod-scraping' or 'streamshub-prometheus'. Provider name: %s", providerName);
        } else {
            LOG.infof("Metrics provider initialized: %s", providerName);
        }
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
     * @param startTime     absolute start time in ISO 8601 format (null for relative or instant query)
     * @param endTime       absolute end time in ISO 8601 format (null for relative or instant query)
     * @param stepSeconds   range query step interval in seconds (null for default)
     * @return a list of metric samples matching the query
     */
    public List<MetricSample> queryMetrics(final List<PodTarget> podTargets,
                                            final Map<String, String> labelMatchers,
                                            final List<String> metricNames,
                                            final Integer rangeMinutes,
                                            final String startTime,
                                            final String endTime,
                                            final Integer stepSeconds) {
        requireProvider();

        MetricsQueryParams params = buildParams(podTargets, labelMatchers, metricNames,
            rangeMinutes, startTime, endTime, stepSeconds);

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
                                            final String startTimeStr,
                                            final String endTimeStr,
                                            final Integer stepSeconds) {
        // Absolute time range
        if (startTimeStr != null && endTimeStr != null) {
            try {
                Instant start = Instant.parse(startTimeStr);
                Instant end = Instant.parse(endTimeStr);

                if (start.isAfter(end)) {
                    throw new IllegalArgumentException("startTime must be before endTime");
                }

                long durationMinutes = java.time.Duration.between(start, end).toMinutes();
                if (durationMinutes > maxRangeMinutes) {
                    throw new IllegalArgumentException(
                        "Time range exceeds maximum of " + maxRangeMinutes + " minutes (" + durationMinutes + " minutes requested)");
                }

                int step = stepSeconds != null ? stepSeconds : defaultStepSeconds;
                return MetricsQueryParams.range(metricNames, labelMatchers, start, end, step);
            } catch (IllegalArgumentException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalArgumentException(
                    "Invalid time format. Use ISO 8601 format (e.g., '2026-04-05T10:00:00Z'): "
                        + e.getMessage(), e);
            }
        }

        // Relative time range
        if (rangeMinutes != null && rangeMinutes > 0) {
            if (rangeMinutes > maxRangeMinutes) {
                throw new IllegalArgumentException(
                    "rangeMinutes exceeds maximum of " + maxRangeMinutes + " minutes (" + rangeMinutes + " minutes requested)");
            }
            Instant end = Instant.now();
            Instant start = end.minusSeconds((long) rangeMinutes * SECONDS_PER_MINUTE);
            int step = stepSeconds != null ? stepSeconds : defaultStepSeconds;
            return MetricsQueryParams.range(metricNames, labelMatchers, start, end, step);
        }

        // Instant query
        return MetricsQueryParams.instant(metricNames, labelMatchers, podTargets);
    }
}
