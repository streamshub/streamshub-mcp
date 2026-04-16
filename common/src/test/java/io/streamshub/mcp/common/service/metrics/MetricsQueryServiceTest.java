/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.service.metrics;

import io.streamshub.mcp.common.dto.metrics.MetricSample;
import io.streamshub.mcp.common.dto.metrics.MetricsQueryParams;
import io.streamshub.mcp.common.dto.metrics.PodTarget;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link MetricsQueryService}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MetricsQueryServiceTest {

    MetricsQueryServiceTest() {
        // default constructor for checkstyle
    }

    @Mock
    Instance<MetricsProvider> metricsProviderInstance;

    @Mock
    MetricsProvider metricsProvider;

    private MetricsQueryService metricsQueryService;

    @BeforeEach
    void setUp() throws Exception {
        metricsQueryService = new MetricsQueryService();
        setField(metricsQueryService, "metricsProviderInstance", metricsProviderInstance);
        setField(metricsQueryService, "providerName", "pod-scraping");
        setField(metricsQueryService, "defaultStepSeconds", 60);
        setField(metricsQueryService, "maxRangeMinutes", 10080);

        when(metricsProviderInstance.isUnsatisfied()).thenReturn(false);
        when(metricsProviderInstance.get()).thenReturn(metricsProvider);
    }

    @Test
    void providerUnsatisfiedThrows() {
        when(metricsProviderInstance.isUnsatisfied()).thenReturn(true);

        List<PodTarget> targets = List.of(PodTarget.of("ns", "pod"));
        Map<String, String> labels = Map.of("namespace", "ns");
        List<String> metrics = List.of("metric_a");

        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> metricsQueryService.queryMetrics(targets, labels, metrics, null, null, null, null));
        assertTrue(ex.getMessage().contains("No metrics provider configured"));
    }

    @Test
    void instantQueryDelegatesToProvider() {
        List<MetricSample> expected = List.of(
            MetricSample.of("metric_a", Map.of("namespace", "ns"), 1.0));
        when(metricsProvider.queryMetrics(any(MetricsQueryParams.class)))
            .thenReturn(expected);

        List<PodTarget> targets = List.of(PodTarget.of("ns", "pod"));
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("namespace", "ns");
        List<String> metrics = List.of("metric_a");

        List<MetricSample> result = metricsQueryService.queryMetrics(
            targets, labels, metrics, null, null, null, null);

        assertEquals(expected, result);

        ArgumentCaptor<MetricsQueryParams> captor = ArgumentCaptor.forClass(MetricsQueryParams.class);
        verify(metricsProvider).queryMetrics(captor.capture());
        MetricsQueryParams params = captor.getValue();
        assertFalse(params.isRangeQuery());
        assertEquals(metrics, params.metricNames());
        assertEquals(1, params.podTargets().size());
    }

    @Test
    void rangeQueryBuildsCorrectParams() {
        when(metricsProvider.queryMetrics(any(MetricsQueryParams.class)))
            .thenReturn(List.of());

        List<PodTarget> targets = List.of(PodTarget.of("ns", "pod"));
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("namespace", "ns");
        List<String> metrics = List.of("metric_a");

        metricsQueryService.queryMetrics(targets, labels, metrics, 5, null, null, 30);

        ArgumentCaptor<MetricsQueryParams> captor = ArgumentCaptor.forClass(MetricsQueryParams.class);
        verify(metricsProvider).queryMetrics(captor.capture());
        MetricsQueryParams params = captor.getValue();
        assertTrue(params.isRangeQuery());
        assertNotNull(params.startTime());
        assertNotNull(params.endTime());
        assertEquals(30, params.stepSeconds());
    }

    @Test
    void rangeQueryUsesDefaultStepWhenNotProvided() {
        when(metricsProvider.queryMetrics(any(MetricsQueryParams.class)))
            .thenReturn(List.of());

        metricsQueryService.queryMetrics(
            List.of(PodTarget.of("ns", "pod")),
            Map.of("namespace", "ns"),
            List.of("metric_a"), 5, null, null, null);

        ArgumentCaptor<MetricsQueryParams> captor = ArgumentCaptor.forClass(MetricsQueryParams.class);
        verify(metricsProvider).queryMetrics(captor.capture());
        assertEquals(60, captor.getValue().stepSeconds());
    }

    @Test
    void providerNameReturnsConfiguredValue() {
        assertEquals("pod-scraping", metricsQueryService.providerName());
    }

    private static void setField(final Object target, final String fieldName,
                                  final Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
