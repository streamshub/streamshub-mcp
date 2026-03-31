/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.service.metrics;

import io.streamshub.mcp.common.dto.metrics.MetricSample;
import io.streamshub.mcp.common.dto.metrics.MetricsQueryParams;

import java.util.List;

/**
 * Interface for metrics retrieval providers.
 * Implementations handle specific backends such as pod scraping or Prometheus.
 */
public interface MetricsProvider {

    /**
     * Queries metrics according to the given parameters.
     *
     * @param params the query parameters including metric names, labels, and pod targets
     * @return a list of metric samples matching the query
     */
    List<MetricSample> queryMetrics(MetricsQueryParams params);
}
