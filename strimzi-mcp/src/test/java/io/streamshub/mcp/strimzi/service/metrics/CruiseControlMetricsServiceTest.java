/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.service.metrics;

import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.Pod;
import io.quarkiverse.mcp.server.JsonRpcErrorCodes;
import io.quarkiverse.mcp.server.McpException;
import io.streamshub.mcp.common.dto.metrics.MetricSample;
import io.streamshub.mcp.common.service.KubernetesResourceService;
import io.streamshub.mcp.common.service.metrics.MetricsQueryService;
import io.streamshub.mcp.strimzi.config.StrimziConstants;
import io.streamshub.mcp.strimzi.dto.metrics.CruiseControlMetricsResponse;
import io.streamshub.mcp.strimzi.service.kafka.KafkaService;
import io.strimzi.api.ResourceLabels;
import io.strimzi.api.kafka.model.kafka.Kafka;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link CruiseControlMetricsService}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CruiseControlMetricsServiceTest {

    CruiseControlMetricsServiceTest() {
        // default constructor for checkstyle
    }

    @Mock
    KubernetesResourceService k8sService;

    @Mock
    MetricsQueryService metricsQueryService;

    @Mock
    KafkaService kafkaService;

    private CruiseControlMetricsService cruiseControlMetricsService;

    @BeforeEach
    void setUp() throws Exception {
        cruiseControlMetricsService = new CruiseControlMetricsService();
        setField(cruiseControlMetricsService, "k8sService", k8sService);
        setField(cruiseControlMetricsService, "metricsQueryService", metricsQueryService);
        setField(cruiseControlMetricsService, "kafkaService", kafkaService);

        when(metricsQueryService.providerName()).thenReturn("pod-scraping");
        when(metricsQueryService.queryMetrics(anyList(), anyMap(), anyList(), any(), any(), any(), any()))
            .thenReturn(List.of());
    }

    @Test
    void missingClusterNameThrows() {
        McpException ex = assertThrows(McpException.class,
            () -> cruiseControlMetricsService.getCruiseControlMetrics(
                "kafka", null, null, null, null, null, null, null, null));
        assertTrue(ex.getMessage().contains("Cluster name is required"));
    }

    @Test
    void clusterNotFoundThrows() {
        when(kafkaService.findKafkaCluster("kafka", "missing"))
            .thenThrow(new McpException("Kafka cluster 'missing' not found in namespace kafka", JsonRpcErrorCodes.RESOURCE_NOT_FOUND));

        McpException ex = assertThrows(McpException.class,
            () -> cruiseControlMetricsService.getCruiseControlMetrics(
                "kafka", "missing", null, null, null, null, null, null, null));
        assertTrue(ex.getMessage().contains("not found in namespace"));
    }

    @Test
    void emptyPodsReturnsEmptyResponse() {
        Kafka kafka = createKafka("my-cluster", "kafka");
        when(kafkaService.findKafkaCluster("kafka", "my-cluster"))
            .thenReturn(kafka);
        when(k8sService.queryResourcesByLabel(eq(Pod.class), eq("kafka"),
            eq(ResourceLabels.STRIMZI_CLUSTER_LABEL), eq("my-cluster")))
            .thenReturn(List.of());

        CruiseControlMetricsResponse response = cruiseControlMetricsService.getCruiseControlMetrics(
            "kafka", "my-cluster", null, null, null, null, null, null, null);

        assertNotNull(response);
        assertEquals(0, response.sampleCount());
        assertTrue(response.message().contains("No Cruise Control pods"));
    }

    @Test
    void successfulMetricsRetrieval() {
        Kafka kafka = createKafka("my-cluster", "kafka");
        Pod ccPod = createCruiseControlPod("my-cluster-cruise-control-0", "kafka");

        when(kafkaService.findKafkaCluster("kafka", "my-cluster"))
            .thenReturn(kafka);
        when(k8sService.queryResourcesByLabel(eq(Pod.class), eq("kafka"),
            eq(ResourceLabels.STRIMZI_CLUSTER_LABEL), eq("my-cluster")))
            .thenReturn(List.of(ccPod));

        List<MetricSample> samples = List.of(
            MetricSample.of("kafka_cruisecontrol_loadmonitor_monitored_partitions_percentage_value",
                Map.of("namespace", "kafka"), 0.99));
        when(metricsQueryService.queryMetrics(anyList(), anyMap(), anyList(), isNull(), isNull(), isNull(), isNull()))
            .thenReturn(samples);

        CruiseControlMetricsResponse response = cruiseControlMetricsService.getCruiseControlMetrics(
            "kafka", "my-cluster", "sampling", null, null, null, null, null, null);

        assertNotNull(response);
        assertEquals("my-cluster", response.clusterName());
        assertEquals("kafka", response.namespace());
        assertEquals(1, response.sampleCount());
        assertEquals(1, response.metricCount());
    }

    @Test
    void defaultsToSamplingCategory() {
        Kafka kafka = createKafka("my-cluster", "kafka");
        Pod ccPod = createCruiseControlPod("my-cluster-cruise-control-0", "kafka");

        when(kafkaService.findKafkaCluster("kafka", "my-cluster"))
            .thenReturn(kafka);
        when(k8sService.queryResourcesByLabel(eq(Pod.class), eq("kafka"),
            eq(ResourceLabels.STRIMZI_CLUSTER_LABEL), eq("my-cluster")))
            .thenReturn(List.of(ccPod));

        CruiseControlMetricsResponse response = cruiseControlMetricsService.getCruiseControlMetrics(
            "kafka", "my-cluster", null, null, null, null, null, null, null);

        assertNotNull(response);
        assertEquals("my-cluster", response.clusterName());
    }

    @Test
    void unknownCategoryThrows() {
        Kafka kafka = createKafka("my-cluster", "kafka");
        when(kafkaService.findKafkaCluster("kafka", "my-cluster"))
            .thenReturn(kafka);

        McpException ex = assertThrows(McpException.class,
            () -> cruiseControlMetricsService.getCruiseControlMetrics(
                "kafka", "my-cluster", "invalid", null, null, null, null, null, null));
        assertTrue(ex.getMessage().contains("Unknown metric category"));
    }

    @Test
    void explicitMetricNamesAreAccepted() {
        Kafka kafka = createKafka("my-cluster", "kafka");
        Pod ccPod = createCruiseControlPod("my-cluster-cruise-control-0", "kafka");

        when(kafkaService.findKafkaCluster("kafka", "my-cluster"))
            .thenReturn(kafka);
        when(k8sService.queryResourcesByLabel(eq(Pod.class), eq("kafka"),
            eq(ResourceLabels.STRIMZI_CLUSTER_LABEL), eq("my-cluster")))
            .thenReturn(List.of(ccPod));

        CruiseControlMetricsResponse response = cruiseControlMetricsService.getCruiseControlMetrics(
            "kafka", "my-cluster", null, "kafka_cruisecontrol_anomalydetector_balancedness_score_value",
            null, null, null, null, null);

        assertNotNull(response);
    }

    @Test
    void rangeQueryParametersPassedThrough() {
        Kafka kafka = createKafka("my-cluster", "kafka");
        Pod ccPod = createCruiseControlPod("my-cluster-cruise-control-0", "kafka");

        when(kafkaService.findKafkaCluster("kafka", "my-cluster"))
            .thenReturn(kafka);
        when(k8sService.queryResourcesByLabel(eq(Pod.class), eq("kafka"),
            eq(ResourceLabels.STRIMZI_CLUSTER_LABEL), eq("my-cluster")))
            .thenReturn(List.of(ccPod));
        when(metricsQueryService.queryMetrics(anyList(), anyMap(), anyList(), eq(60), isNull(), isNull(), eq(15)))
            .thenReturn(List.of());

        CruiseControlMetricsResponse response = cruiseControlMetricsService.getCruiseControlMetrics(
            "kafka", "my-cluster", "sampling", null, 60, null, null, 15, null);

        assertNotNull(response);
        verify(metricsQueryService).queryMetrics(anyList(), anyMap(), anyList(), eq(60), isNull(), isNull(), eq(15));
    }

    @Test
    void nonCruiseControlPodsAreFilteredOut() {
        Kafka kafka = createKafka("my-cluster", "kafka");
        Pod ccPod = createCruiseControlPod("my-cluster-cruise-control-0", "kafka");
        Pod brokerPod = createPod("my-cluster-kafka-0", "kafka", StrimziConstants.ComponentTypes.KAFKA);

        when(kafkaService.findKafkaCluster("kafka", "my-cluster"))
            .thenReturn(kafka);
        when(k8sService.queryResourcesByLabel(eq(Pod.class), eq("kafka"),
            eq(ResourceLabels.STRIMZI_CLUSTER_LABEL), eq("my-cluster")))
            .thenReturn(List.of(ccPod, brokerPod));

        List<MetricSample> samples = List.of(
            MetricSample.of("kafka_cruisecontrol_loadmonitor_monitored_partitions_percentage_value",
                Map.of("namespace", "kafka"), 0.99));
        when(metricsQueryService.queryMetrics(anyList(), anyMap(), anyList(), isNull(), isNull(), isNull(), isNull()))
            .thenReturn(samples);

        CruiseControlMetricsResponse response = cruiseControlMetricsService.getCruiseControlMetrics(
            "kafka", "my-cluster", "sampling", null, null, null, null, null, null);

        assertNotNull(response);
        assertEquals(1, response.sampleCount());
    }

    @Test
    void aggregationAlwaysClampedToCluster() {
        Kafka kafka = createKafka("my-cluster", "kafka");
        Pod pod = createCruiseControlPod("my-cluster-cruise-control-0", "kafka");

        when(kafkaService.findKafkaCluster("kafka", "my-cluster"))
            .thenReturn(kafka);
        when(k8sService.queryResourcesByLabel(eq(Pod.class), eq("kafka"),
            eq(ResourceLabels.STRIMZI_CLUSTER_LABEL), eq("my-cluster")))
            .thenReturn(List.of(pod));

        CruiseControlMetricsResponse response = cruiseControlMetricsService.getCruiseControlMetrics(
            "kafka", "my-cluster", "sampling", null, null, null, null, null, "partition");

        assertEquals("cluster", response.aggregation());
    }

    private Kafka createKafka(final String name, final String namespace) {
        Kafka kafka = new Kafka();
        ObjectMeta meta = new ObjectMeta();
        meta.setName(name);
        meta.setNamespace(namespace);
        kafka.setMetadata(meta);
        return kafka;
    }

    private Pod createCruiseControlPod(final String name, final String namespace) {
        return createPod(name, namespace, StrimziConstants.ComponentTypes.KAFKA_CRUISE_CONTROL);
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
