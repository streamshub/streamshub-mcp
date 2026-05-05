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
import io.streamshub.mcp.strimzi.dto.metrics.KafkaBridgeMetricsResponse;
import io.streamshub.mcp.strimzi.service.kafkabridge.KafkaBridgeService;
import io.strimzi.api.ResourceLabels;
import io.strimzi.api.kafka.model.bridge.KafkaBridge;
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
 * Unit tests for {@link KafkaBridgeMetricsService}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class KafkaBridgeMetricsServiceTest {

    KafkaBridgeMetricsServiceTest() {
    }

    @Mock
    KubernetesResourceService k8sService;

    @Mock
    MetricsQueryService metricsQueryService;

    @Mock
    KafkaBridgeService kafkaBridgeService;

    private KafkaBridgeMetricsService kafkaBridgeMetricsService;

    @BeforeEach
    void setUp() throws Exception {
        kafkaBridgeMetricsService = new KafkaBridgeMetricsService();
        setField(kafkaBridgeMetricsService, "k8sService", k8sService);
        setField(kafkaBridgeMetricsService, "metricsQueryService", metricsQueryService);
        setField(kafkaBridgeMetricsService, "kafkaBridgeService", kafkaBridgeService);

        when(metricsQueryService.providerName()).thenReturn("pod-scraping");
        when(metricsQueryService.queryMetrics(anyList(), anyMap(), anyList(), any(), any(), any(), any()))
            .thenReturn(List.of());
    }

    /**
     * Verify missing bridge name throws.
     */
    @Test
    void missingBridgeNameThrows() {
        ToolCallException ex = assertThrows(ToolCallException.class,
            () -> kafkaBridgeMetricsService.getKafkaBridgeMetrics(
                "kafka", null, null, null, null, null, null, null, null));
        assertTrue(ex.getMessage().contains("KafkaBridge name is required"));
    }

    /**
     * Verify bridge not found throws.
     */
    @Test
    void bridgeNotFoundThrows() {
        when(kafkaBridgeService.findKafkaBridge("kafka", "missing"))
            .thenThrow(new ToolCallException("KafkaBridge 'missing' not found in namespace kafka"));

        ToolCallException ex = assertThrows(ToolCallException.class,
            () -> kafkaBridgeMetricsService.getKafkaBridgeMetrics(
                "kafka", "missing", null, null, null, null, null, null, null));
        assertTrue(ex.getMessage().contains("not found in namespace"));
    }

    /**
     * Verify empty pods returns empty response.
     */
    @Test
    void emptyPodsReturnsEmptyResponse() {
        KafkaBridge bridge = createBridge("my-bridge", "kafka");
        when(kafkaBridgeService.findKafkaBridge("kafka", "my-bridge"))
            .thenReturn(bridge);
        when(k8sService.queryResourcesByLabel(eq(Pod.class), eq("kafka"),
            eq(ResourceLabels.STRIMZI_CLUSTER_LABEL), eq("my-bridge")))
            .thenReturn(List.of());

        KafkaBridgeMetricsResponse response = kafkaBridgeMetricsService.getKafkaBridgeMetrics(
            "kafka", "my-bridge", null, null, null, null, null, null, null);

        assertNotNull(response);
        assertEquals(0, response.sampleCount());
        assertTrue(response.message().contains("No KafkaBridge pods"));
    }

    /**
     * Verify successful metrics retrieval.
     */
    @Test
    void successfulMetricsRetrieval() {
        KafkaBridge bridge = createBridge("my-bridge", "kafka");
        Pod bridgePod = createBridgePod("my-bridge-bridge-0", "kafka");

        when(kafkaBridgeService.findKafkaBridge("kafka", "my-bridge"))
            .thenReturn(bridge);
        when(k8sService.queryResourcesByLabel(eq(Pod.class), eq("kafka"),
            eq(ResourceLabels.STRIMZI_CLUSTER_LABEL), eq("my-bridge")))
            .thenReturn(List.of(bridgePod));

        List<MetricSample> samples = List.of(
            MetricSample.of("strimzi_bridge_http_server_requestCount_total",
                Map.of("namespace", "kafka"), 42.0));
        when(metricsQueryService.queryMetrics(anyList(), anyMap(), anyList(), isNull(), isNull(), isNull(), isNull()))
            .thenReturn(samples);

        KafkaBridgeMetricsResponse response = kafkaBridgeMetricsService.getKafkaBridgeMetrics(
            "kafka", "my-bridge", "http", null, null, null, null, null, null);

        assertNotNull(response);
        assertEquals("my-bridge", response.bridgeName());
        assertEquals("kafka", response.namespace());
        assertEquals(1, response.sampleCount());
        assertEquals(1, response.metricCount());
    }

    /**
     * Verify defaults to HTTP category.
     */
    @Test
    void defaultsToHttpCategory() {
        KafkaBridge bridge = createBridge("my-bridge", "kafka");
        Pod bridgePod = createBridgePod("my-bridge-bridge-0", "kafka");

        when(kafkaBridgeService.findKafkaBridge("kafka", "my-bridge"))
            .thenReturn(bridge);
        when(k8sService.queryResourcesByLabel(eq(Pod.class), eq("kafka"),
            eq(ResourceLabels.STRIMZI_CLUSTER_LABEL), eq("my-bridge")))
            .thenReturn(List.of(bridgePod));

        KafkaBridgeMetricsResponse response = kafkaBridgeMetricsService.getKafkaBridgeMetrics(
            "kafka", "my-bridge", null, null, null, null, null, null, null);

        assertNotNull(response);
        assertEquals("my-bridge", response.bridgeName());
    }

    /**
     * Verify unknown category throws.
     */
    @Test
    void unknownCategoryThrows() {
        KafkaBridge bridge = createBridge("my-bridge", "kafka");
        when(kafkaBridgeService.findKafkaBridge("kafka", "my-bridge"))
            .thenReturn(bridge);

        ToolCallException ex = assertThrows(ToolCallException.class,
            () -> kafkaBridgeMetricsService.getKafkaBridgeMetrics(
                "kafka", "my-bridge", "invalid", null, null, null, null, null, null));
        assertTrue(ex.getMessage().contains("Unknown metric category"));
    }

    /**
     * Verify explicit metric names are accepted.
     */
    @Test
    void explicitMetricNamesAreAccepted() {
        KafkaBridge bridge = createBridge("my-bridge", "kafka");
        Pod bridgePod = createBridgePod("my-bridge-bridge-0", "kafka");

        when(kafkaBridgeService.findKafkaBridge("kafka", "my-bridge"))
            .thenReturn(bridge);
        when(k8sService.queryResourcesByLabel(eq(Pod.class), eq("kafka"),
            eq(ResourceLabels.STRIMZI_CLUSTER_LABEL), eq("my-bridge")))
            .thenReturn(List.of(bridgePod));

        KafkaBridgeMetricsResponse response = kafkaBridgeMetricsService.getKafkaBridgeMetrics(
            "kafka", "my-bridge", null, "strimzi_bridge_http_server_requestCount_total,custom_metric",
            null, null, null, null, null);

        assertNotNull(response);
    }

    /**
     * Verify range query parameters are passed through.
     */
    @Test
    void rangeQueryParametersPassedThrough() {
        KafkaBridge bridge = createBridge("my-bridge", "kafka");
        Pod bridgePod = createBridgePod("my-bridge-bridge-0", "kafka");

        when(kafkaBridgeService.findKafkaBridge("kafka", "my-bridge"))
            .thenReturn(bridge);
        when(k8sService.queryResourcesByLabel(eq(Pod.class), eq("kafka"),
            eq(ResourceLabels.STRIMZI_CLUSTER_LABEL), eq("my-bridge")))
            .thenReturn(List.of(bridgePod));
        when(metricsQueryService.queryMetrics(anyList(), anyMap(), anyList(), eq(60), isNull(), isNull(), eq(15)))
            .thenReturn(List.of());

        KafkaBridgeMetricsResponse response = kafkaBridgeMetricsService.getKafkaBridgeMetrics(
            "kafka", "my-bridge", "http", null, 60, null, null, 15, null);

        assertNotNull(response);
    }

    /**
     * Verify non-bridge pods are filtered out.
     */
    @Test
    void nonBridgePodsAreFilteredOut() {
        KafkaBridge bridge = createBridge("my-bridge", "kafka");
        Pod bridgePod = createBridgePod("my-bridge-bridge-0", "kafka");
        Pod brokerPod = createPod("my-bridge-kafka-0", "kafka", StrimziConstants.ComponentTypes.KAFKA);

        when(kafkaBridgeService.findKafkaBridge("kafka", "my-bridge"))
            .thenReturn(bridge);
        when(k8sService.queryResourcesByLabel(eq(Pod.class), eq("kafka"),
            eq(ResourceLabels.STRIMZI_CLUSTER_LABEL), eq("my-bridge")))
            .thenReturn(List.of(bridgePod, brokerPod));

        List<MetricSample> samples = List.of(
            MetricSample.of("strimzi_bridge_http_server_requestCount_total",
                Map.of("namespace", "kafka"), 10.0));
        when(metricsQueryService.queryMetrics(anyList(), anyMap(), anyList(), isNull(), isNull(), isNull(), isNull()))
            .thenReturn(samples);

        KafkaBridgeMetricsResponse response = kafkaBridgeMetricsService.getKafkaBridgeMetrics(
            "kafka", "my-bridge", "http", null, null, null, null, null, null);

        assertNotNull(response);
        assertEquals(1, response.sampleCount());
    }

    @Test
    void aggregationClampedToClusterForHttpCategory() {
        KafkaBridge bridge = createBridge("my-bridge", "kafka");
        Pod bridgePod = createBridgePod("my-bridge-bridge-0", "kafka");

        when(kafkaBridgeService.findKafkaBridge("kafka", "my-bridge"))
            .thenReturn(bridge);
        when(k8sService.queryResourcesByLabel(eq(Pod.class), eq("kafka"),
            eq(ResourceLabels.STRIMZI_CLUSTER_LABEL), eq("my-bridge")))
            .thenReturn(List.of(bridgePod));

        KafkaBridgeMetricsResponse response = kafkaBridgeMetricsService.getKafkaBridgeMetrics(
            "kafka", "my-bridge", "http", null, null, null, null, null, "broker");

        assertEquals("cluster", response.aggregation());
    }

    @Test
    void aggregationClampedToClusterForDefaultCategory() {
        KafkaBridge bridge = createBridge("my-bridge", "kafka");
        Pod bridgePod = createBridgePod("my-bridge-bridge-0", "kafka");

        when(kafkaBridgeService.findKafkaBridge("kafka", "my-bridge"))
            .thenReturn(bridge);
        when(k8sService.queryResourcesByLabel(eq(Pod.class), eq("kafka"),
            eq(ResourceLabels.STRIMZI_CLUSTER_LABEL), eq("my-bridge")))
            .thenReturn(List.of(bridgePod));

        KafkaBridgeMetricsResponse response = kafkaBridgeMetricsService.getKafkaBridgeMetrics(
            "kafka", "my-bridge", null, null, null, null, null, null, null);

        assertEquals("cluster", response.aggregation());
    }

    private KafkaBridge createBridge(final String name, final String namespace) {
        KafkaBridge bridge = new KafkaBridge();
        ObjectMeta meta = new ObjectMeta();
        meta.setName(name);
        meta.setNamespace(namespace);
        bridge.setMetadata(meta);
        return bridge;
    }

    private Pod createBridgePod(final String name, final String namespace) {
        return createPod(name, namespace, StrimziConstants.ComponentTypes.KAFKA_BRIDGE);
    }

    private Pod createPod(final String name, final String namespace, final String componentType) {
        Pod pod = new Pod();
        ObjectMeta meta = new ObjectMeta();
        meta.setName(name);
        meta.setNamespace(namespace);
        if (componentType != null) {
            meta.setLabels(Map.of(
                ResourceLabels.STRIMZI_COMPONENT_TYPE_LABEL, componentType));
        }
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
