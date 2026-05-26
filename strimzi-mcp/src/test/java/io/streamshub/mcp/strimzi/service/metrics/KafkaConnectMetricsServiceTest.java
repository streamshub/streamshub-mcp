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
import io.streamshub.mcp.strimzi.dto.metrics.KafkaConnectMetricsResponse;
import io.streamshub.mcp.strimzi.service.kafkaconnect.KafkaConnectService;
import io.strimzi.api.ResourceLabels;
import io.strimzi.api.kafka.model.connect.KafkaConnect;
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
 * Unit tests for {@link KafkaConnectMetricsService}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class KafkaConnectMetricsServiceTest {

    KafkaConnectMetricsServiceTest() {
    }

    @Mock
    KubernetesResourceService k8sService;

    @Mock
    MetricsQueryService metricsQueryService;

    @Mock
    KafkaConnectService kafkaConnectService;

    private KafkaConnectMetricsService kafkaConnectMetricsService;

    @BeforeEach
    void setUp() throws Exception {
        kafkaConnectMetricsService = new KafkaConnectMetricsService();
        setField(kafkaConnectMetricsService, "k8sService", k8sService);
        setField(kafkaConnectMetricsService, "metricsQueryService", metricsQueryService);
        setField(kafkaConnectMetricsService, "kafkaConnectService", kafkaConnectService);

        when(metricsQueryService.providerName()).thenReturn("pod-scraping");
        when(metricsQueryService.queryMetrics(anyList(), anyMap(), anyList(), any(), any(), any(), any()))
            .thenReturn(List.of());
    }

    /**
     * Verify missing connect name throws.
     */
    @Test
    void missingConnectNameThrows() {
        ToolCallException ex = assertThrows(ToolCallException.class,
            () -> kafkaConnectMetricsService.getKafkaConnectMetrics(
                "kafka", null, null, null, null, null, null, null, null));
        assertTrue(ex.getMessage().contains("KafkaConnect name is required"));
    }

    /**
     * Verify connect not found throws.
     */
    @Test
    void connectNotFoundThrows() {
        when(kafkaConnectService.findKafkaConnect("kafka", "missing"))
            .thenThrow(new ToolCallException("KafkaConnect cluster 'missing' not found in namespace kafka"));

        ToolCallException ex = assertThrows(ToolCallException.class,
            () -> kafkaConnectMetricsService.getKafkaConnectMetrics(
                "kafka", "missing", null, null, null, null, null, null, null));
        assertTrue(ex.getMessage().contains("not found in namespace"));
    }

    /**
     * Verify empty pods returns empty response.
     */
    @Test
    void emptyPodsReturnsEmptyResponse() {
        KafkaConnect connect = createConnect("my-connect", "kafka");
        when(kafkaConnectService.findKafkaConnect("kafka", "my-connect"))
            .thenReturn(connect);
        when(k8sService.queryResourcesByLabels(eq(Pod.class), eq("kafka"), anyMap()))
            .thenReturn(List.of());

        KafkaConnectMetricsResponse response = kafkaConnectMetricsService.getKafkaConnectMetrics(
            "kafka", "my-connect", null, null, null, null, null, null, null);

        assertNotNull(response);
        assertEquals(0, response.sampleCount());
        assertTrue(response.message().contains("No KafkaConnect pods"));
    }

    /**
     * Verify successful metrics retrieval.
     */
    @Test
    void successfulMetricsRetrieval() {
        KafkaConnect connect = createConnect("my-connect", "kafka");
        Pod connectPod = createConnectPod("my-connect-connect-0", "kafka", "my-connect");

        when(kafkaConnectService.findKafkaConnect("kafka", "my-connect"))
            .thenReturn(connect);
        when(k8sService.queryResourcesByLabels(eq(Pod.class), eq("kafka"), anyMap()))
            .thenReturn(List.of(connectPod));

        List<MetricSample> samples = List.of(
            MetricSample.of("kafka_connect_worker_connector_count",
                Map.of("namespace", "kafka"), 2.0));
        when(metricsQueryService.queryMetrics(anyList(), anyMap(), anyList(), isNull(), isNull(), isNull(), isNull()))
            .thenReturn(samples);

        KafkaConnectMetricsResponse response = kafkaConnectMetricsService.getKafkaConnectMetrics(
            "kafka", "my-connect", "worker", null, null, null, null, null, null);

        assertNotNull(response);
        assertEquals("my-connect", response.connectName());
        assertEquals("kafka", response.namespace());
        assertEquals(1, response.sampleCount());
        assertEquals(1, response.metricCount());
    }

    /**
     * Verify defaults to worker category.
     */
    @Test
    void defaultsToWorkerCategory() {
        KafkaConnect connect = createConnect("my-connect", "kafka");
        Pod connectPod = createConnectPod("my-connect-connect-0", "kafka", "my-connect");

        when(kafkaConnectService.findKafkaConnect("kafka", "my-connect"))
            .thenReturn(connect);
        when(k8sService.queryResourcesByLabels(eq(Pod.class), eq("kafka"), anyMap()))
            .thenReturn(List.of(connectPod));

        KafkaConnectMetricsResponse response = kafkaConnectMetricsService.getKafkaConnectMetrics(
            "kafka", "my-connect", null, null, null, null, null, null, null);

        assertNotNull(response);
        assertEquals("my-connect", response.connectName());
    }

    /**
     * Verify unknown category throws.
     */
    @Test
    void unknownCategoryThrows() {
        KafkaConnect connect = createConnect("my-connect", "kafka");
        when(kafkaConnectService.findKafkaConnect("kafka", "my-connect"))
            .thenReturn(connect);

        ToolCallException ex = assertThrows(ToolCallException.class,
            () -> kafkaConnectMetricsService.getKafkaConnectMetrics(
                "kafka", "my-connect", "invalid", null, null, null, null, null, null));
        assertTrue(ex.getMessage().contains("Unknown metric category"));
    }

    /**
     * Verify explicit metric names are accepted.
     */
    @Test
    void explicitMetricNamesAreAccepted() {
        KafkaConnect connect = createConnect("my-connect", "kafka");
        Pod connectPod = createConnectPod("my-connect-connect-0", "kafka", "my-connect");

        when(kafkaConnectService.findKafkaConnect("kafka", "my-connect"))
            .thenReturn(connect);
        when(k8sService.queryResourcesByLabels(eq(Pod.class), eq("kafka"), anyMap()))
            .thenReturn(List.of(connectPod));

        KafkaConnectMetricsResponse response = kafkaConnectMetricsService.getKafkaConnectMetrics(
            "kafka", "my-connect", null, "kafka_connect_worker_connector_count,custom_metric",
            null, null, null, null, null);

        assertNotNull(response);
    }

    /**
     * Verify range query parameters are passed through.
     */
    @Test
    void rangeQueryParametersPassedThrough() {
        KafkaConnect connect = createConnect("my-connect", "kafka");
        Pod connectPod = createConnectPod("my-connect-connect-0", "kafka", "my-connect");

        when(kafkaConnectService.findKafkaConnect("kafka", "my-connect"))
            .thenReturn(connect);
        when(k8sService.queryResourcesByLabels(eq(Pod.class), eq("kafka"), anyMap()))
            .thenReturn(List.of(connectPod));
        when(metricsQueryService.queryMetrics(anyList(), anyMap(), anyList(), eq(60), isNull(), isNull(), eq(15)))
            .thenReturn(List.of());

        KafkaConnectMetricsResponse response = kafkaConnectMetricsService.getKafkaConnectMetrics(
            "kafka", "my-connect", "worker", null, 60, null, null, 15, null);

        assertNotNull(response);
    }

    /**
     * Verify aggregation is clamped to cluster for worker category.
     */
    @Test
    void aggregationClampedToClusterForWorkerCategory() {
        KafkaConnect connect = createConnect("my-connect", "kafka");
        Pod connectPod = createConnectPod("my-connect-connect-0", "kafka", "my-connect");

        when(kafkaConnectService.findKafkaConnect("kafka", "my-connect"))
            .thenReturn(connect);
        when(k8sService.queryResourcesByLabels(eq(Pod.class), eq("kafka"), anyMap()))
            .thenReturn(List.of(connectPod));

        KafkaConnectMetricsResponse response = kafkaConnectMetricsService.getKafkaConnectMetrics(
            "kafka", "my-connect", "worker", null, null, null, null, null, "broker");

        assertEquals("cluster", response.aggregation());
    }

    /**
     * Verify aggregation is clamped to cluster for default category.
     */
    @Test
    void aggregationClampedToClusterForDefaultCategory() {
        KafkaConnect connect = createConnect("my-connect", "kafka");
        Pod connectPod = createConnectPod("my-connect-connect-0", "kafka", "my-connect");

        when(kafkaConnectService.findKafkaConnect("kafka", "my-connect"))
            .thenReturn(connect);
        when(k8sService.queryResourcesByLabels(eq(Pod.class), eq("kafka"), anyMap()))
            .thenReturn(List.of(connectPod));

        KafkaConnectMetricsResponse response = kafkaConnectMetricsService.getKafkaConnectMetrics(
            "kafka", "my-connect", null, null, null, null, null, null, null);

        assertEquals("cluster", response.aggregation());
    }

    private KafkaConnect createConnect(final String name, final String namespace) {
        KafkaConnect connect = new KafkaConnect();
        ObjectMeta meta = new ObjectMeta();
        meta.setName(name);
        meta.setNamespace(namespace);
        connect.setMetadata(meta);
        return connect;
    }

    private Pod createConnectPod(final String name, final String namespace, final String clusterName) {
        Pod pod = new Pod();
        ObjectMeta meta = new ObjectMeta();
        meta.setName(name);
        meta.setNamespace(namespace);
        meta.setLabels(Map.of(
            ResourceLabels.STRIMZI_CLUSTER_LABEL, clusterName,
            ResourceLabels.STRIMZI_KIND_LABEL, StrimziConstants.KindValues.KAFKA_CONNECT));
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
