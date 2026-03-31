/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.metrics.prometheus.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for {@link PrometheusMetricsProvider} PromQL query building.
 */
class PrometheusMetricsProviderTest {

    PrometheusMetricsProviderTest() {
    }

    private PrometheusMetricsProvider provider;

    @BeforeEach
    void setUp() {
        provider = new PrometheusMetricsProvider();
    }

    @Test
    void buildPromQLSingleMetricNoLabels() {
        String query = provider.buildPromQL(List.of("metric_a"), null);
        assertEquals("metric_a", query);
    }

    @Test
    void buildPromQLSingleMetricWithLabels() {
        String query = provider.buildPromQL(
            List.of("metric_a"), Map.of("namespace", "kafka"));
        assertEquals("metric_a{namespace=\"kafka\"}", query);
    }

    @Test
    void buildPromQLMultipleMetricsNoLabels() {
        String query = provider.buildPromQL(
            List.of("metric_a", "metric_b"), null);
        assertEquals("{__name__=~\"metric_a|metric_b\"}", query);
    }

    @Test
    void buildPromQLMultipleMetricsWithLabels() {
        String query = provider.buildPromQL(
            List.of("metric_a", "metric_b"),
            Map.of("namespace", "kafka"));
        assertEquals(
            "{__name__=~\"metric_a|metric_b\",namespace=\"kafka\"}",
            query);
    }

    @Test
    void buildPromQLEmptyMetricNames() {
        String query = provider.buildPromQL(List.of(), null);
        assertEquals("{}", query);
    }

    @Test
    void buildPromQLNullMetricNames() {
        String query = provider.buildPromQL(null, null);
        assertEquals("{}", query);
    }

    @Test
    void buildPromQLSingleMetricWithEmptyLabels() {
        String query = provider.buildPromQL(
            List.of("metric_a"), Map.of());
        assertEquals("metric_a", query);
    }

    @Test
    void buildPromQLMultipleMetricsWithEmptyLabels() {
        String query = provider.buildPromQL(
            List.of("metric_a", "metric_b"), Map.of());
        assertEquals("{__name__=~\"metric_a|metric_b\"}", query);
    }

    @Test
    void buildPromQLSingleMetricMultipleLabels() {
        Map<String, String> labels = new java.util.LinkedHashMap<>();
        labels.put("namespace", "kafka");
        labels.put("pod", "broker-0");

        String query = provider.buildPromQL(
            List.of("metric_a"), labels);
        assertEquals(
            "metric_a{namespace=\"kafka\",pod=\"broker-0\"}",
            query);
    }
}
