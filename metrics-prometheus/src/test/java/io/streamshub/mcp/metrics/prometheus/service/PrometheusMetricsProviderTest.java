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
    void buildPromQLAddsRegexMatchersToSingleMetric() {
        String query = provider.buildPromQL(List.of("metric_a"), Map.of("namespace", "kafka"),
            Map.of("quantile", "0\\.50*|0\\.99"));
        assertEquals("metric_a{namespace=\"kafka\",quantile=~\"0\\\\.50*|0\\\\.99\"}", query);
    }

    @Test
    void buildPromQLAddsRegexMatchersToMultipleMetrics() {
        String query = provider.buildPromQL(List.of("metric_a", "metric_b"), null,
            Map.of("request", "Produce|FetchConsumer"));
        assertEquals("{__name__=~\"metric_a|metric_b\",request=~\"Produce|FetchConsumer\"}", query);
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

    @Test
    @SuppressWarnings("unchecked")
    void queryMetricsCapsEachGaugeSeparately() throws Exception {
        injectMockClient();
        org.mockito.Mockito.when(prometheusClientInstance.isUnsatisfied()).thenReturn(false);
        org.mockito.Mockito.when(prometheusClientInstance.get()).thenReturn(prometheusClient);
        PrometheusResponse response = new PrometheusResponse("success",
            new PrometheusData("vector", List.of(
                new PrometheusResult(Map.of("__name__", "large_gauge", "pod", "pod-0"),
                    List.of(1719792000.0, "1"), null),
                new PrometheusResult(Map.of("__name__", "large_gauge", "pod", "pod-1"),
                    List.of(1719792000.0, "2"), null),
                new PrometheusResult(Map.of("__name__", "large_gauge", "pod", "pod-2"),
                    List.of(1719792000.0, "3"), null),
                new PrometheusResult(Map.of("__name__", "small_gauge", "pod", "pod-0"),
                    List.of(1719792000.0, "4"), null))));
        org.mockito.Mockito.when(prometheusClient.instantQuery(org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.isNull())).thenReturn(response);

        MetricsQueryParams params = MetricsQueryParams.instant(
            List.of("large_gauge", "small_gauge"), null, null, 2);

        List<MetricSample> result = provider.queryMetrics(params);

        assertEquals(List.of("large_gauge", "large_gauge", "small_gauge"),
            result.stream().map(MetricSample::name).toList());
    }

    @Test
    @SuppressWarnings("unchecked")
    void rangeQueryCapsEachGaugeSeparatelyAcrossSeries() throws Exception {
        injectMockClient();
        org.mockito.Mockito.when(prometheusClientInstance.isUnsatisfied()).thenReturn(false);
        org.mockito.Mockito.when(prometheusClientInstance.get()).thenReturn(prometheusClient);
        PrometheusResponse response = new PrometheusResponse("success",
            new PrometheusData("matrix", List.of(
                new PrometheusResult(Map.of("__name__", "large_gauge", "pod", "pod-0"), null,
                    List.of(List.of(1719792000.0, "1"), List.of(1719792060.0, "2"))),
                new PrometheusResult(Map.of("__name__", "large_gauge", "pod", "pod-1"), null,
                    List.of(List.of(1719792000.0, "3"), List.of(1719792060.0, "4"))),
                new PrometheusResult(Map.of("__name__", "small_gauge", "pod", "pod-0"), null,
                    List.of(List.of(1719792000.0, "5"))))));
        org.mockito.Mockito.when(prometheusClient.rangeQuery(org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString())).thenReturn(response);

        MetricsQueryParams params = MetricsQueryParams.range(List.of("large_gauge", "small_gauge"),
            null, null, java.time.Instant.ofEpochSecond(1719791000L),
            java.time.Instant.ofEpochSecond(1719792060L), 60, 3);

        List<MetricSample> result = provider.queryMetrics(params);

        assertEquals(List.of(1.0, 2.0, 3.0, 5.0), result.stream().map(MetricSample::value).toList());
    }

    @Test
    @SuppressWarnings("unchecked")
    void unparseableValuesDoNotUseTheSampleLimit() throws Exception {
        injectMockClient();
        org.mockito.Mockito.when(prometheusClientInstance.isUnsatisfied()).thenReturn(false);
        org.mockito.Mockito.when(prometheusClientInstance.get()).thenReturn(prometheusClient);
        PrometheusResponse response = new PrometheusResponse("success",
            new PrometheusData("vector", List.of(
                new PrometheusResult(Map.of("__name__", "test_gauge", "pod", "pod-0"),
                    List.of(1719792000.0, "+Inf"), null),
                new PrometheusResult(Map.of("__name__", "test_gauge", "pod", "pod-1"),
                    List.of(1719792000.0, "1"), null),
                new PrometheusResult(Map.of("__name__", "test_gauge", "pod", "pod-2"),
                    List.of(1719792000.0, "2"), null))));
        org.mockito.Mockito.when(prometheusClient.instantQuery(org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.isNull())).thenReturn(response);

        List<MetricSample> result = provider.queryMetrics(
            MetricsQueryParams.instant(List.of("test_gauge"), null, null, 2));

        assertEquals(List.of(1.0, 2.0), result.stream().map(MetricSample::value).toList());
    }

    @Test
    @SuppressWarnings("unchecked")
    void counterQueryCapsSamplesPerMetric() throws Exception {
        injectMockClient();
        org.mockito.Mockito.when(prometheusClientInstance.isUnsatisfied()).thenReturn(false);
        org.mockito.Mockito.when(prometheusClientInstance.get()).thenReturn(prometheusClient);
        PrometheusResponse response = new PrometheusResponse("success",
            new PrometheusData("vector", List.of(
                new PrometheusResult(Map.of("pod", "pod-0"), List.of(1719792000.0, "1"), null),
                new PrometheusResult(Map.of("pod", "pod-1"), List.of(1719792000.0, "2"), null),
                new PrometheusResult(Map.of("pod", "pod-2"), List.of(1719792000.0, "3"), null))));
        org.mockito.Mockito.when(prometheusClient.instantQuery(org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.isNull())).thenReturn(response);

        List<MetricSample> result = provider.queryMetrics(
            MetricsQueryParams.instant(List.of("test_requests_total"), null, null, 2));

        assertEquals(List.of(1.0, 2.0), result.stream().map(MetricSample::value).toList());
    }

    @Test
    void rateWindowIsAtLeastFiveMinutesAndTwoSteps() {
        java.time.Instant end = java.time.Instant.parse("2026-01-01T01:00:00Z");
        java.time.Instant start = end.minusSeconds(1800);
        assertEquals(300, PrometheusMetricsProvider.rateWindowSeconds(
            MetricsQueryParams.instant(List.of("c_total"), null, null, 0)));
        assertEquals(List.of(300, 300, 600, 7200), List.of(1, 60, 300, 3600).stream()
            .map(step -> PrometheusMetricsProvider.rateWindowSeconds(
                MetricsQueryParams.range(List.of("c_total"), null, null, start, end, step, 0)))
            .toList());
    }

    @Test
    @SuppressWarnings("unchecked")
    void counterRangeQueryUsesRateOverTheWidenedWindow() throws Exception {
        injectMockClient();
        org.mockito.Mockito.when(prometheusClientInstance.isUnsatisfied()).thenReturn(false);
        org.mockito.Mockito.when(prometheusClientInstance.get()).thenReturn(prometheusClient);
        org.mockito.Mockito.when(prometheusClient.rangeQuery(org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString()))
            .thenReturn(new PrometheusResponse("success", new PrometheusData("matrix", List.of())));
        java.time.Instant end = java.time.Instant.ofEpochSecond(1719792000L);

        provider.queryMetrics(MetricsQueryParams.range(List.of("c_total"), Map.of("namespace", "kafka"), null,
            end.minusSeconds(3600), end, 300, 0));

        org.mockito.Mockito.verify(prometheusClient).rangeQuery("rate(c_total{namespace=\"kafka\"}[600s])",
            "1719788400", "1719792000", "300s");
    }

    @Test
    @SuppressWarnings("unchecked")
    void counterInstantQueryUsesRateNotIrate() throws Exception {
        injectMockClient();
        org.mockito.Mockito.when(prometheusClientInstance.isUnsatisfied()).thenReturn(false);
        org.mockito.Mockito.when(prometheusClientInstance.get()).thenReturn(prometheusClient);
        org.mockito.Mockito.when(prometheusClient.instantQuery(org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.isNull()))
            .thenReturn(new PrometheusResponse("success", new PrometheusData("vector", List.of())));

        provider.queryMetrics(MetricsQueryParams.instant(List.of("c_total"), Map.of("namespace", "kafka"), null, 0));

        org.mockito.Mockito.verify(prometheusClient).instantQuery(
            "rate(c_total{namespace=\"kafka\"}[300s])", null);
    }

    private static PrometheusResult series(final Map<String, String> labels) {
        return new PrometheusResult(labels, List.of(1719792000.0, "1"), null);
    }

    @Test
    void withoutDuplicateScrapesDropsCopiesThatDifferOnlyInScrapeConfig() {
        List<PrometheusResult> results = List.of(
            series(Map.of("__name__", "m", "pod", "p-0", "container", "kafka", "job", "a",
                "instance", "10.0.0.1:9404", "endpoint", "x")),
            series(Map.of("__name__", "m", "pod", "p-0", "container", "kafka", "job", "b",
                "instance", "10.0.0.1:9405", "endpoint", "y", "service", "kafka-metrics")),
            series(Map.of("__name__", "m", "pod", "p-1", "container", "kafka", "job", "a",
                "instance", "10.0.0.2:9404", "endpoint", "x")));

        assertEquals(List.of(results.get(0), results.get(2)),
            PrometheusMetricsProvider.withoutDuplicateScrapes(results));
    }

    @Test
    void withoutDuplicateScrapesKeepsContainersOfOnePod() {
        List<PrometheusResult> results = List.of(
            series(Map.of("__name__", "jvm", "pod", "eo-0", "container", "topic-operator", "job", "a")),
            series(Map.of("__name__", "jvm", "pod", "eo-0", "container", "user-operator", "job", "a")));

        assertEquals(results, PrometheusMetricsProvider.withoutDuplicateScrapes(results));
    }

    @Test
    void withoutDuplicateScrapesUsesInstanceWhenThereIsNoContainerLabel() {
        List<PrometheusResult> results = List.of(
            series(Map.of("__name__", "jvm", "pod", "eo-0", "job", "a", "instance", "10.0.0.5:8080")),
            series(Map.of("__name__", "jvm", "pod", "eo-0", "job", "a", "instance", "10.0.0.5:8081")));

        assertEquals(results, PrometheusMetricsProvider.withoutDuplicateScrapes(results));
    }

    @Test
    void withoutDuplicateScrapesKeepsBothHalvesOfARestartedPod() {
        Map<String, String> before = Map.of("__name__", "m", "pod", "p-0", "container", "kafka", "instance", "10.0.0.1:9404");
        Map<String, String> after = Map.of("__name__", "m", "pod", "p-0", "container", "kafka", "instance", "10.0.0.9:9404");
        List<PrometheusResult> results = List.of(
            new PrometheusResult(before, null, List.of(List.of(1000.0, "1"), List.of(1060.0, "2"))),
            new PrometheusResult(after, null, List.of(List.of(1120.0, "3"), List.of(1180.0, "4"))));

        assertEquals(results, PrometheusMetricsProvider.withoutDuplicateScrapes(results));
    }

    @Test
    void withoutDuplicateScrapesDropsOverlappingRangeSamplesOnly() {
        Map<String, String> first = Map.of("__name__", "m", "pod", "p-0", "container", "kafka", "job", "a");
        Map<String, String> second = Map.of("__name__", "m", "pod", "p-0", "container", "kafka", "job", "b");
        List<PrometheusResult> results = List.of(
            new PrometheusResult(first, null, List.of(List.of(1000.0, "1"), List.of(1060.0, "2"))),
            new PrometheusResult(second, null, List.of(List.of(1060.0, "2"), List.of(1120.0, "3"))));

        List<PrometheusResult> unique = PrometheusMetricsProvider.withoutDuplicateScrapes(results);

        assertEquals(2, unique.size());
        assertEquals(List.of(List.of(1120.0, "3")), unique.get(1).values());
    }

    @Test
    void withoutDuplicateScrapesUsesInstanceWhenThereIsNoPodLabel() {
        List<PrometheusResult> results = List.of(
            series(Map.of("__name__", "m", "job", "a", "instance", "10.0.0.1:9404")),
            series(Map.of("__name__", "m", "job", "a", "instance", "10.0.0.2:9404")),
            series(Map.of("__name__", "m", "job", "b", "instance", "10.0.0.2:9404")));

        assertEquals(List.of(results.get(0), results.get(1)),
            PrometheusMetricsProvider.withoutDuplicateScrapes(results));
    }

    @SuppressWarnings("unchecked")
    private void injectMockClient() throws Exception {
        Field field = PrometheusMetricsProvider.class.getDeclaredField("prometheusClientInstance");
        field.setAccessible(true);
        field.set(provider, prometheusClientInstance);
    }
}
