/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.metrics.prometheus.service;

import io.streamshub.mcp.common.dto.metrics.MetricSample;
import io.streamshub.mcp.common.dto.metrics.MetricsQueryParams;
import io.streamshub.mcp.common.service.metrics.MetricsQueryException;
import io.streamshub.mcp.metrics.prometheus.dto.PrometheusResponse;
import io.streamshub.mcp.metrics.prometheus.dto.PrometheusResponse.PrometheusData;
import io.streamshub.mcp.metrics.prometheus.dto.PrometheusResponse.PrometheusResult;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link PrometheusMetricsProvider} PromQL query building.
 */
@ExtendWith(MockitoExtension.class)
class PrometheusMetricsProviderTest {

    PrometheusMetricsProviderTest() {
    }

    @Mock
    PrometheusClient prometheusClient;

    @Mock
    @SuppressWarnings("rawtypes")
    Instance prometheusClientInstance;

    private PrometheusMetricsProvider provider;

    @BeforeEach
    @SuppressWarnings("unchecked")
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

    @Test
    void partitionMetricsSeparatesCountersAndGauges() {
        List<String> counters = new ArrayList<>();
        List<String> gauges = new ArrayList<>();

        PrometheusMetricsProvider.partitionMetrics(
            List.of("bytesin_total", "underreplicated", "messagesin_total", "jvm_heap"),
            counters, gauges);

        assertEquals(List.of("bytesin_total", "messagesin_total"), counters);
        assertEquals(List.of("underreplicated", "jvm_heap"), gauges);
    }

    @Test
    void partitionMetricsNullInput() {
        List<String> counters = new ArrayList<>();
        List<String> gauges = new ArrayList<>();

        PrometheusMetricsProvider.partitionMetrics(null, counters, gauges);

        assertTrue(counters.isEmpty());
        assertTrue(gauges.isEmpty());
    }

    @Test
    void partitionMetricsAllCounters() {
        List<String> counters = new ArrayList<>();
        List<String> gauges = new ArrayList<>();

        PrometheusMetricsProvider.partitionMetrics(
            List.of("a_total", "b_total"), counters, gauges);

        assertEquals(List.of("a_total", "b_total"), counters);
        assertTrue(gauges.isEmpty());
    }

    @Test
    void partitionMetricsAllGauges() {
        List<String> counters = new ArrayList<>();
        List<String> gauges = new ArrayList<>();

        PrometheusMetricsProvider.partitionMetrics(
            List.of("gauge_a", "gauge_b"), counters, gauges);

        assertTrue(counters.isEmpty());
        assertEquals(List.of("gauge_a", "gauge_b"), gauges);
    }

    // ---- Response validation tests ----

    @Test
    @SuppressWarnings("unchecked")
    void queryMetricsThrowsWhenPrometheusReturnsNull() throws Exception {
        injectMockClient();
        org.mockito.Mockito.when(prometheusClientInstance.isUnsatisfied()).thenReturn(false);
        org.mockito.Mockito.when(prometheusClientInstance.get()).thenReturn(prometheusClient);
        org.mockito.Mockito.when(prometheusClient.instantQuery(org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.isNull())).thenReturn(null);

        MetricsQueryParams params = MetricsQueryParams.instant(
            List.of("test_gauge"), null, null, 0);

        MetricsQueryException ex = assertThrows(MetricsQueryException.class,
            () -> provider.queryMetrics(params));
        assertTrue(ex.getMessage().contains("Prometheus returned no response data"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void queryMetricsThrowsWhenPrometheusReturnsNullData() throws Exception {
        injectMockClient();
        org.mockito.Mockito.when(prometheusClientInstance.isUnsatisfied()).thenReturn(false);
        org.mockito.Mockito.when(prometheusClientInstance.get()).thenReturn(prometheusClient);
        PrometheusResponse response = new PrometheusResponse("success", null);
        org.mockito.Mockito.when(prometheusClient.instantQuery(org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.isNull())).thenReturn(response);

        MetricsQueryParams params = MetricsQueryParams.instant(
            List.of("test_gauge"), null, null, 0);

        MetricsQueryException ex = assertThrows(MetricsQueryException.class,
            () -> provider.queryMetrics(params));
        assertTrue(ex.getMessage().contains("Prometheus returned no response data"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void queryMetricsThrowsWhenPrometheusReturnsErrorStatus() throws Exception {
        injectMockClient();
        org.mockito.Mockito.when(prometheusClientInstance.isUnsatisfied()).thenReturn(false);
        org.mockito.Mockito.when(prometheusClientInstance.get()).thenReturn(prometheusClient);
        PrometheusResponse response = new PrometheusResponse("error",
            new PrometheusData("vector", List.of()));
        org.mockito.Mockito.when(prometheusClient.instantQuery(org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.isNull())).thenReturn(response);

        MetricsQueryParams params = MetricsQueryParams.instant(
            List.of("test_gauge"), null, null, 0);

        MetricsQueryException ex = assertThrows(MetricsQueryException.class,
            () -> provider.queryMetrics(params));
        assertTrue(ex.getMessage().contains("Prometheus query failed with status 'error'"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void queryMetricsReturnsEmptyListForSuccessWithNoResults() throws Exception {
        injectMockClient();
        org.mockito.Mockito.when(prometheusClientInstance.isUnsatisfied()).thenReturn(false);
        org.mockito.Mockito.when(prometheusClientInstance.get()).thenReturn(prometheusClient);
        PrometheusResponse response = new PrometheusResponse("success",
            new PrometheusData("vector", List.of()));
        org.mockito.Mockito.when(prometheusClient.instantQuery(org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.isNull())).thenReturn(response);

        MetricsQueryParams params = MetricsQueryParams.instant(
            List.of("test_gauge"), null, null, 0);

        List<MetricSample> result = provider.queryMetrics(params);

        assertTrue(result.isEmpty());
    }

    @Test
    @SuppressWarnings("unchecked")
    void queryMetricsReturnsDataForSuccessfulResponse() throws Exception {
        injectMockClient();
        org.mockito.Mockito.when(prometheusClientInstance.isUnsatisfied()).thenReturn(false);
        org.mockito.Mockito.when(prometheusClientInstance.get()).thenReturn(prometheusClient);
        PrometheusResponse response = new PrometheusResponse("success",
            new PrometheusData("vector", List.of(
                new PrometheusResult(
                    Map.of("__name__", "test_gauge", "pod", "pod-0"),
                    List.of(1719792000.0, "42.5"),
                    null)
            )));
        org.mockito.Mockito.when(prometheusClient.instantQuery(org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.isNull())).thenReturn(response);

        MetricsQueryParams params = MetricsQueryParams.instant(
            List.of("test_gauge"), null, null, 0);

        List<MetricSample> result = provider.queryMetrics(params);

        assertEquals(1, result.size());
        assertEquals("test_gauge", result.getFirst().name());
        assertEquals(42.5, result.getFirst().value());
    }

    @SuppressWarnings("unchecked")
    private void injectMockClient() throws Exception {
        Field field = PrometheusMetricsProvider.class.getDeclaredField("prometheusClientInstance");
        field.setAccessible(true);
        field.set(provider, prometheusClientInstance);
    }
}
