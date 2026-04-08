/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.service.metrics;

import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.Pod;
import io.quarkiverse.mcp.server.ToolCallException;
import io.streamshub.mcp.common.dto.metrics.MetricSample;
import io.streamshub.mcp.common.dto.metrics.PodTarget;
import io.streamshub.mcp.common.service.metrics.MetricsQueryService;
import io.streamshub.mcp.strimzi.config.StrimziConstants;
import io.streamshub.mcp.strimzi.dto.metrics.StrimziOperatorMetricsResponse;
import io.streamshub.mcp.strimzi.service.StrimziOperatorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link StrimziOperatorMetricsService}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StrimziOperatorMetricsServiceTest {

    StrimziOperatorMetricsServiceTest() {
        // default constructor for checkstyle
    }

    @Mock
    MetricsQueryService metricsQueryService;

    @Mock
    StrimziOperatorService strimziOperatorService;

    private StrimziOperatorMetricsService strimziOperatorMetricsService;

    @BeforeEach
    void setUp() throws Exception {
        strimziOperatorMetricsService = new StrimziOperatorMetricsService();
        setField(strimziOperatorMetricsService, "metricsQueryService", metricsQueryService);
        setField(strimziOperatorMetricsService, "strimziOperatorService", strimziOperatorService);

        when(metricsQueryService.providerName()).thenReturn("pod-scraping");
        when(metricsQueryService.queryMetrics(anyList(), anyMap(), anyList(), any(), any(), any(), any()))
            .thenReturn(List.of());
    }

    @Test
    void noOperatorPodsFoundInNamespaceThrows() {
        when(strimziOperatorService.findClusterOperatorPods("kafka-system", null))
            .thenReturn(List.of());

        ToolCallException ex = assertThrows(ToolCallException.class,
            () -> strimziOperatorMetricsService.getOperatorMetrics(
                "kafka-system", null, null, null, null, null, null, null, null));
        assertTrue(ex.getMessage().contains("No Strimzi operator pods found"));
    }

    @Test
    void noOperatorPodsFoundInAnyNamespaceThrows() {
        when(strimziOperatorService.findClusterOperatorPods(null, null))
            .thenReturn(List.of());

        ToolCallException ex = assertThrows(ToolCallException.class,
            () -> strimziOperatorMetricsService.getOperatorMetrics(
                null, null, null, null, null, null, null, null, null));
        assertTrue(ex.getMessage().contains("No Strimzi operator pods found"));
    }

    @Test
    void namedOperatorNotFoundThrows() {
        when(strimziOperatorService.findClusterOperatorPods("kafka-system", "strimzi-cluster-operator"))
            .thenReturn(List.of());

        ToolCallException ex = assertThrows(ToolCallException.class,
            () -> strimziOperatorMetricsService.getOperatorMetrics(
                "kafka-system", "strimzi-cluster-operator", null, null, null, null, null, null, null));
        assertTrue(ex.getMessage().contains("No Strimzi operator pods found"));
    }

    @Test
    void unknownCategoryThrows() {
        Pod pod = createPod("strimzi-cluster-operator-abc123", "kafka-system");
        when(strimziOperatorService.findClusterOperatorPods("kafka-system", null))
            .thenReturn(List.of(pod));

        ToolCallException ex = assertThrows(ToolCallException.class,
            () -> strimziOperatorMetricsService.getOperatorMetrics(
                "kafka-system", null, null, "invalid", null, null, null, null, null));
        assertTrue(ex.getMessage().contains("Unknown metric category"));
    }

    @Test
    void successfulMetricsRetrieval() {
        Pod pod = createPod("strimzi-cluster-operator-abc123", "kafka-system");
        when(strimziOperatorService.findClusterOperatorPods("kafka-system", null))
            .thenReturn(List.of(pod));

        List<MetricSample> samples = List.of(
            MetricSample.of("strimzi_reconciliations_successful_total",
                Map.of("namespace", "kafka-system"), 42.0));
        when(metricsQueryService.queryMetrics(anyList(), anyMap(), anyList(), isNull(), isNull(), isNull(), isNull()))
            .thenReturn(samples);

        StrimziOperatorMetricsResponse response = strimziOperatorMetricsService.getOperatorMetrics(
            "kafka-system", null, null, "reconciliation", null, null, null, null, null);

        assertNotNull(response);
        assertEquals("cluster-operator", response.operatorName());
        assertNull(response.clusterName());
        assertEquals("kafka-system", response.namespace());
        assertEquals(1, response.sampleCount());
        assertEquals(1, response.metricCount());
    }

    @Test
    void defaultsToReconciliationCategoryWhenNoneSpecified() {
        Pod pod = createPod("strimzi-cluster-operator-abc123", "kafka-system");
        when(strimziOperatorService.findClusterOperatorPods("kafka-system", null))
            .thenReturn(List.of(pod));

        StrimziOperatorMetricsResponse response = strimziOperatorMetricsService.getOperatorMetrics(
            "kafka-system", null, null, null, null, null, null, null, null);

        assertNotNull(response);
        assertEquals("kafka-system", response.namespace());
    }

    @Test
    void explicitMetricNamesAreAccepted() {
        Pod pod = createPod("strimzi-cluster-operator-abc123", "kafka-system");
        when(strimziOperatorService.findClusterOperatorPods("kafka-system", null))
            .thenReturn(List.of(pod));

        StrimziOperatorMetricsResponse response = strimziOperatorMetricsService.getOperatorMetrics(
            "kafka-system", null, null, null, "strimzi_resources,strimzi_resource_state", null, null, null, null);

        assertNotNull(response);
    }

    @Test
    void operatorPodsInMultipleNamespacesWithoutClusterName() {
        Pod pod1 = createPod("strimzi-cluster-operator-abc", "ns-a");
        Pod pod2 = createPod("strimzi-cluster-operator-def", "ns-b");
        when(strimziOperatorService.findClusterOperatorPods(null, null))
            .thenReturn(List.of(pod1, pod2));

        StrimziOperatorMetricsResponse response = strimziOperatorMetricsService.getOperatorMetrics(
            null, null, null, null, null, null, null, null, null);

        assertNotNull(response);
    }

    @Test
    void namedOperatorFilterMatchesPodNamePrefix() {
        Pod matchingPod = createPod("my-operator-abc123", "kafka-system");
        when(strimziOperatorService.findClusterOperatorPods("kafka-system", "my-operator"))
            .thenReturn(List.of(matchingPod));

        StrimziOperatorMetricsResponse response = strimziOperatorMetricsService.getOperatorMetrics(
            "kafka-system", "my-operator", null, null, null, null, null, null, null);

        assertNotNull(response);
        assertEquals("my-operator", response.operatorName());
    }

    @Test
    void rangeQueryParametersPassedThrough() {
        Pod pod = createPod("strimzi-cluster-operator-abc123", "kafka-system");
        when(strimziOperatorService.findClusterOperatorPods("kafka-system", null))
            .thenReturn(List.of(pod));
        when(metricsQueryService.queryMetrics(anyList(), anyMap(), anyList(), eq(30), isNull(), isNull(), eq(10)))
            .thenReturn(List.of());

        StrimziOperatorMetricsResponse response = strimziOperatorMetricsService.getOperatorMetrics(
            "kafka-system", null, null, "reconciliation", null, 30, null, null, 10);

        assertNotNull(response);
    }

    // Entity operator tests

    @Test
    @SuppressWarnings("unchecked")
    void clusterNameIncludesEntityOperatorPods() {
        Pod coPod = createPod("strimzi-cluster-operator-abc", "strimzi-system");
        when(strimziOperatorService.findClusterOperatorPods(null, null))
            .thenReturn(List.of(coPod));

        Pod eoPod = createPod("my-cluster-entity-operator-xyz", "kafka-prod");
        when(strimziOperatorService.findEntityOperatorPods(null, "my-cluster"))
            .thenReturn(List.of(eoPod));

        StrimziOperatorMetricsResponse response = strimziOperatorMetricsService.getOperatorMetrics(
            null, null, "my-cluster", null, null, null, null, null, null);

        assertNotNull(response);
        assertEquals("my-cluster", response.clusterName());

        // Verify pod targets include CO (1 target) + EO (2 targets: UO port + TO port)
        ArgumentCaptor<List<PodTarget>> targetsCaptor = ArgumentCaptor.forClass(List.class);
        verify(metricsQueryService).queryMetrics(targetsCaptor.capture(), anyMap(), anyList(),
            any(), any(), any(), any());
        List<PodTarget> targets = targetsCaptor.getValue();
        assertEquals(3, targets.size());

        // CO target has no explicit port
        assertNull(targets.get(0).port());
        assertEquals("strimzi-system", targets.get(0).namespace());

        // EO targets have explicit ports
        assertEquals(StrimziConstants.EntityOperator.USER_OPERATOR_PORT, targets.get(1).port());
        assertEquals(StrimziConstants.EntityOperator.TOPIC_OPERATOR_PORT, targets.get(2).port());
        assertEquals("kafka-prod", targets.get(1).namespace());
    }

    @Test
    void clusterNameWithNoEntityOperatorPodsReturnsCOOnly() {
        Pod coPod = createPod("strimzi-cluster-operator-abc", "kafka-system");
        when(strimziOperatorService.findClusterOperatorPods("kafka-system", null))
            .thenReturn(List.of(coPod));
        when(strimziOperatorService.findEntityOperatorPods("kafka-system", "my-cluster"))
            .thenReturn(List.of());

        StrimziOperatorMetricsResponse response = strimziOperatorMetricsService.getOperatorMetrics(
            "kafka-system", null, "my-cluster", null, null, null, null, null, null);

        assertNotNull(response);
        assertEquals("my-cluster", response.clusterName());
        assertEquals("kafka-system", response.namespace());
    }

    @Test
    void clusterNameWithDifferentNamespacesCOandEO() {
        Pod coPod = createPod("strimzi-cluster-operator-abc", "strimzi-system");
        when(strimziOperatorService.findClusterOperatorPods(null, null))
            .thenReturn(List.of(coPod));

        Pod eoPod = createPod("my-cluster-entity-operator-xyz", "kafka-prod");
        when(strimziOperatorService.findEntityOperatorPods(null, "my-cluster"))
            .thenReturn(List.of(eoPod));

        StrimziOperatorMetricsResponse response = strimziOperatorMetricsService.getOperatorMetrics(
            null, null, "my-cluster", null, null, null, null, null, null);

        assertNotNull(response);
        assertEquals("strimzi-system", response.namespace());
    }

    @Test
    @SuppressWarnings("unchecked")
    void clusterNameAddsLabelMatcherForPrometheus() {
        Pod coPod = createPod("strimzi-cluster-operator-abc", "kafka-system");
        when(strimziOperatorService.findClusterOperatorPods("kafka-system", null))
            .thenReturn(List.of(coPod));
        when(strimziOperatorService.findEntityOperatorPods("kafka-system", "my-cluster"))
            .thenReturn(List.of());

        strimziOperatorMetricsService.getOperatorMetrics(
            "kafka-system", null, "my-cluster", null, null, null, null, null, null);

        ArgumentCaptor<Map<String, String>> matchersCaptor = ArgumentCaptor.forClass(Map.class);
        verify(metricsQueryService).queryMetrics(anyList(), matchersCaptor.capture(), anyList(),
            any(), any(), any(), any());
        Map<String, String> matchers = matchersCaptor.getValue();
        assertEquals("my-cluster", matchers.get("strimzi_io_cluster"));
    }

    @Test
    void entityOperatorOnlyWhenNoCOPodsFound() {
        when(strimziOperatorService.findClusterOperatorPods(null, null))
            .thenReturn(List.of());

        Pod eoPod = createPod("my-cluster-entity-operator-xyz", "kafka-prod");
        when(strimziOperatorService.findEntityOperatorPods(null, "my-cluster"))
            .thenReturn(List.of(eoPod));

        StrimziOperatorMetricsResponse response = strimziOperatorMetricsService.getOperatorMetrics(
            null, null, "my-cluster", null, null, null, null, null, null);

        assertNotNull(response);
        assertEquals("kafka-prod", response.namespace());
    }

    private Pod createPod(final String name, final String namespace) {
        Pod pod = new Pod();
        ObjectMeta meta = new ObjectMeta();
        meta.setName(name);
        meta.setNamespace(namespace);
        pod.setMetadata(meta);
        return pod;
    }

    private static void setField(final Object target, final String fieldName,
                                  final Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
