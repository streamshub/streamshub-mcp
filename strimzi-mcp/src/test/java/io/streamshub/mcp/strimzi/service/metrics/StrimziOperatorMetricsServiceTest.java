/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.service.metrics;

import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.Pod;
import io.quarkiverse.mcp.server.ToolCallException;
import io.streamshub.mcp.common.dto.metrics.MetricSample;
import io.streamshub.mcp.common.service.KubernetesResourceService;
import io.streamshub.mcp.common.service.metrics.MetricsQueryService;
import io.streamshub.mcp.strimzi.config.StrimziConstants;
import io.streamshub.mcp.strimzi.dto.metrics.StrimziOperatorMetricsResponse;
import io.strimzi.api.ResourceLabels;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
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
    KubernetesResourceService k8sService;

    @Mock
    MetricsQueryService metricsQueryService;

    private StrimziOperatorMetricsService strimziOperatorMetricsService;

    @BeforeEach
    void setUp() throws Exception {
        strimziOperatorMetricsService = new StrimziOperatorMetricsService();
        setField(strimziOperatorMetricsService, "k8sService", k8sService);
        setField(strimziOperatorMetricsService, "metricsQueryService", metricsQueryService);

        when(metricsQueryService.providerName()).thenReturn("pod-scraping");
        when(metricsQueryService.queryMetrics(anyList(), anyMap(), anyList(), any(), any(), any(), any()))
            .thenReturn(List.of());
    }

    @Test
    void noOperatorPodsFoundInNamespaceThrows() {
        when(k8sService.queryResourcesByLabel(eq(Pod.class), eq("kafka-system"),
            eq(ResourceLabels.STRIMZI_KIND_LABEL), eq(StrimziConstants.KindValues.CLUSTER_OPERATOR)))
            .thenReturn(List.of());

        ToolCallException ex = assertThrows(ToolCallException.class,
            () -> strimziOperatorMetricsService.getOperatorMetrics(
                "kafka-system", null, null, null, null, null, null, null));
        assertTrue(ex.getMessage().contains("No Strimzi operator pods found"));
    }

    @Test
    void noOperatorPodsFoundInAnyNamespaceThrows() {
        when(k8sService.queryResourcesByLabelInAnyNamespace(eq(Pod.class),
            eq(ResourceLabels.STRIMZI_KIND_LABEL), eq(StrimziConstants.KindValues.CLUSTER_OPERATOR)))
            .thenReturn(List.of());

        ToolCallException ex = assertThrows(ToolCallException.class,
            () -> strimziOperatorMetricsService.getOperatorMetrics(
                null, null, null, null, null, null, null, null));
        assertTrue(ex.getMessage().contains("No Strimzi operator pods found"));
    }

    @Test
    void namedOperatorNotFoundThrows() {
        Pod pod = createPod("other-operator-abc123", "kafka-system");
        when(k8sService.queryResourcesByLabel(eq(Pod.class), eq("kafka-system"),
            eq(ResourceLabels.STRIMZI_KIND_LABEL), eq(StrimziConstants.KindValues.CLUSTER_OPERATOR)))
            .thenReturn(List.of(pod));

        ToolCallException ex = assertThrows(ToolCallException.class,
            () -> strimziOperatorMetricsService.getOperatorMetrics(
                "kafka-system", "strimzi-cluster-operator", null, null, null, null, null, null));
        assertTrue(ex.getMessage().contains("strimzi-cluster-operator"));
    }

    @Test
    void unknownCategoryThrows() {
        Pod pod = createPod("strimzi-cluster-operator-abc123", "kafka-system");
        when(k8sService.queryResourcesByLabel(eq(Pod.class), eq("kafka-system"),
            eq(ResourceLabels.STRIMZI_KIND_LABEL), eq(StrimziConstants.KindValues.CLUSTER_OPERATOR)))
            .thenReturn(List.of(pod));

        ToolCallException ex = assertThrows(ToolCallException.class,
            () -> strimziOperatorMetricsService.getOperatorMetrics(
                "kafka-system", null, "invalid", null, null, null, null, null));
        assertTrue(ex.getMessage().contains("Unknown metric category"));
    }

    @Test
    void successfulMetricsRetrieval() {
        Pod pod = createPod("strimzi-cluster-operator-abc123", "kafka-system");
        when(k8sService.queryResourcesByLabel(eq(Pod.class), eq("kafka-system"),
            eq(ResourceLabels.STRIMZI_KIND_LABEL), eq(StrimziConstants.KindValues.CLUSTER_OPERATOR)))
            .thenReturn(List.of(pod));

        List<MetricSample> samples = List.of(
            MetricSample.of("strimzi_reconciliations_successful_total",
                Map.of("namespace", "kafka-system"), 42.0));
        when(metricsQueryService.queryMetrics(anyList(), anyMap(), anyList(), isNull(), isNull(), isNull(), isNull()))
            .thenReturn(samples);

        StrimziOperatorMetricsResponse response = strimziOperatorMetricsService.getOperatorMetrics(
            "kafka-system", null, "reconciliation", null, null, null, null, null);

        assertNotNull(response);
        assertEquals("cluster-operator", response.operatorName());
        assertEquals("kafka-system", response.namespace());
        assertEquals(1, response.sampleCount());
        assertEquals(1, response.metricCount());
    }

    @Test
    void defaultsToReconciliationCategoryWhenNoneSpecified() {
        Pod pod = createPod("strimzi-cluster-operator-abc123", "kafka-system");
        when(k8sService.queryResourcesByLabel(eq(Pod.class), eq("kafka-system"),
            eq(ResourceLabels.STRIMZI_KIND_LABEL), eq(StrimziConstants.KindValues.CLUSTER_OPERATOR)))
            .thenReturn(List.of(pod));

        StrimziOperatorMetricsResponse response = strimziOperatorMetricsService.getOperatorMetrics(
            "kafka-system", null, null, null, null, null, null, null);

        assertNotNull(response);
        assertEquals("kafka-system", response.namespace());
    }

    @Test
    void explicitMetricNamesAreAccepted() {
        Pod pod = createPod("strimzi-cluster-operator-abc123", "kafka-system");
        when(k8sService.queryResourcesByLabel(eq(Pod.class), eq("kafka-system"),
            eq(ResourceLabels.STRIMZI_KIND_LABEL), eq(StrimziConstants.KindValues.CLUSTER_OPERATOR)))
            .thenReturn(List.of(pod));

        StrimziOperatorMetricsResponse response = strimziOperatorMetricsService.getOperatorMetrics(
            "kafka-system", null, null, "strimzi_resources,strimzi_resource_state", null, null, null, null);

        assertNotNull(response);
    }

    @Test
    void operatorPodsInMultipleNamespacesThrows() {
        Pod pod1 = createPod("strimzi-cluster-operator-abc", "ns-a");
        Pod pod2 = createPod("strimzi-cluster-operator-def", "ns-b");
        when(k8sService.queryResourcesByLabelInAnyNamespace(eq(Pod.class),
            eq(ResourceLabels.STRIMZI_KIND_LABEL), eq(StrimziConstants.KindValues.CLUSTER_OPERATOR)))
            .thenReturn(List.of(pod1, pod2));

        ToolCallException ex = assertThrows(ToolCallException.class,
            () -> strimziOperatorMetricsService.getOperatorMetrics(
                null, null, null, null, null, null, null, null));
        assertTrue(ex.getMessage().contains("multiple namespaces"));
    }

    @Test
    void namedOperatorFilterMatchesPodNamePrefix() {
        Pod matchingPod = createPod("my-operator-abc123", "kafka-system");
        Pod otherPod = createPod("strimzi-cluster-operator-def456", "kafka-system");
        when(k8sService.queryResourcesByLabel(eq(Pod.class), eq("kafka-system"),
            eq(ResourceLabels.STRIMZI_KIND_LABEL), eq(StrimziConstants.KindValues.CLUSTER_OPERATOR)))
            .thenReturn(List.of(matchingPod, otherPod));

        StrimziOperatorMetricsResponse response = strimziOperatorMetricsService.getOperatorMetrics(
            "kafka-system", "my-operator", null, null, null, null, null, null);

        assertNotNull(response);
        assertEquals("my-operator", response.operatorName());
    }

    @Test
    void rangeQueryParametersPassedThrough() {
        Pod pod = createPod("strimzi-cluster-operator-abc123", "kafka-system");
        when(k8sService.queryResourcesByLabel(eq(Pod.class), eq("kafka-system"),
            eq(ResourceLabels.STRIMZI_KIND_LABEL), eq(StrimziConstants.KindValues.CLUSTER_OPERATOR)))
            .thenReturn(List.of(pod));
        when(metricsQueryService.queryMetrics(anyList(), anyMap(), anyList(), eq(30), isNull(), isNull(), eq(10)))
            .thenReturn(List.of());

        StrimziOperatorMetricsResponse response = strimziOperatorMetricsService.getOperatorMetrics(
            "kafka-system", null, "reconciliation", null, 30, null, null, 10);

        assertNotNull(response);
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
