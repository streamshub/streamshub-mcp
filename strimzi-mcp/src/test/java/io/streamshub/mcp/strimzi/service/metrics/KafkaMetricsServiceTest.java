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
import io.streamshub.mcp.strimzi.dto.metrics.KafkaMetricsResponse;
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
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link KafkaMetricsService}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class KafkaMetricsServiceTest {

    KafkaMetricsServiceTest() {
        // default constructor for checkstyle
    }

    @Mock
    KubernetesResourceService k8sService;

    @Mock
    MetricsQueryService metricsQueryService;

    private KafkaMetricsService kafkaMetricsService;

    @BeforeEach
    void setUp() throws Exception {
        kafkaMetricsService = new KafkaMetricsService();
        setField(kafkaMetricsService, "k8sService", k8sService);
        setField(kafkaMetricsService, "metricsQueryService", metricsQueryService);

        when(metricsQueryService.providerName()).thenReturn("pod-scraping");
        when(metricsQueryService.queryMetrics(anyList(), anyMap(), anyList(), any(), any()))
            .thenReturn(List.of());
    }

    @Test
    void missingClusterNameThrows() {
        ToolCallException ex = assertThrows(ToolCallException.class,
            () -> kafkaMetricsService.getKafkaMetrics("kafka", null, null, null, null, null));
        assertTrue(ex.getMessage().contains("Cluster name is required"));
    }

    @Test
    void clusterNotFoundInNamespaceThrows() {
        when(k8sService.getResource(eq(Kafka.class), eq("kafka"), eq("missing")))
            .thenReturn(null);

        ToolCallException ex = assertThrows(ToolCallException.class,
            () -> kafkaMetricsService.getKafkaMetrics("kafka", "missing", null, null, null, null));
        assertTrue(ex.getMessage().contains("not found in namespace"));
    }

    @Test
    void clusterNotFoundInAnyNamespaceThrows() {
        when(k8sService.queryResourcesInAnyNamespace(Kafka.class))
            .thenReturn(List.of());

        ToolCallException ex = assertThrows(ToolCallException.class,
            () -> kafkaMetricsService.getKafkaMetrics(null, "missing", null, null, null, null));
        assertTrue(ex.getMessage().contains("not found in any namespace"));
    }

    @Test
    void unknownCategoryThrows() {
        Kafka kafka = createKafka("my-cluster", "kafka");
        when(k8sService.getResource(eq(Kafka.class), eq("kafka"), eq("my-cluster")))
            .thenReturn(kafka);

        ToolCallException ex = assertThrows(ToolCallException.class,
            () -> kafkaMetricsService.getKafkaMetrics("kafka", "my-cluster", "invalid", null, null, null));
        assertTrue(ex.getMessage().contains("Unknown metric category"));
    }

    @Test
    void emptyPodsReturnsEmptyResponse() {
        Kafka kafka = createKafka("my-cluster", "kafka");
        when(k8sService.getResource(eq(Kafka.class), eq("kafka"), eq("my-cluster")))
            .thenReturn(kafka);
        when(k8sService.queryResourcesByLabel(eq(Pod.class), eq("kafka"),
            eq(ResourceLabels.STRIMZI_CLUSTER_LABEL), eq("my-cluster")))
            .thenReturn(List.of());

        KafkaMetricsResponse response = kafkaMetricsService.getKafkaMetrics(
            "kafka", "my-cluster", null, null, null, null);

        assertNotNull(response);
        assertEquals(0, response.sampleCount());
        assertTrue(response.message().contains("No Kafka pods"));
    }

    @Test
    void successfulMetricsRetrieval() {
        Kafka kafka = createKafka("my-cluster", "kafka");
        Pod pod = createPod("my-cluster-kafka-0", "kafka");

        when(k8sService.getResource(eq(Kafka.class), eq("kafka"), eq("my-cluster")))
            .thenReturn(kafka);
        when(k8sService.queryResourcesByLabel(eq(Pod.class), eq("kafka"),
            eq(ResourceLabels.STRIMZI_CLUSTER_LABEL), eq("my-cluster")))
            .thenReturn(List.of(pod));

        List<MetricSample> samples = List.of(
            MetricSample.of("kafka_server_replicamanager_underreplicatedpartitions",
                Map.of("namespace", "kafka"), 0.0));
        when(metricsQueryService.queryMetrics(anyList(), anyMap(), anyList(), isNull(), isNull()))
            .thenReturn(samples);

        KafkaMetricsResponse response = kafkaMetricsService.getKafkaMetrics(
            "kafka", "my-cluster", "replication", null, null, null);

        assertNotNull(response);
        assertEquals("my-cluster", response.clusterName());
        assertEquals("kafka", response.namespace());
        assertEquals(1, response.sampleCount());
        assertEquals(1, response.metricCount());
    }

    @Test
    void defaultsToReplicationCategoryWhenNoneSpecified() {
        Kafka kafka = createKafka("my-cluster", "kafka");
        Pod pod = createPod("my-cluster-kafka-0", "kafka");

        when(k8sService.getResource(eq(Kafka.class), eq("kafka"), eq("my-cluster")))
            .thenReturn(kafka);
        when(k8sService.queryResourcesByLabel(eq(Pod.class), eq("kafka"),
            eq(ResourceLabels.STRIMZI_CLUSTER_LABEL), eq("my-cluster")))
            .thenReturn(List.of(pod));

        KafkaMetricsResponse response = kafkaMetricsService.getKafkaMetrics(
            "kafka", "my-cluster", null, null, null, null);

        assertNotNull(response);
        assertEquals("my-cluster", response.clusterName());
    }

    @Test
    void explicitMetricNamesAreAccepted() {
        Kafka kafka = createKafka("my-cluster", "kafka");
        Pod pod = createPod("my-cluster-kafka-0", "kafka");

        when(k8sService.getResource(eq(Kafka.class), eq("kafka"), eq("my-cluster")))
            .thenReturn(kafka);
        when(k8sService.queryResourcesByLabel(eq(Pod.class), eq("kafka"),
            eq(ResourceLabels.STRIMZI_CLUSTER_LABEL), eq("my-cluster")))
            .thenReturn(List.of(pod));

        KafkaMetricsResponse response = kafkaMetricsService.getKafkaMetrics(
            "kafka", "my-cluster", null, "custom_metric_a,custom_metric_b", null, null);

        assertNotNull(response);
    }

    @Test
    void multipleClustersWithSameNameThrows() {
        Kafka kafka1 = createKafka("my-cluster", "kafka-a");
        Kafka kafka2 = createKafka("my-cluster", "kafka-b");
        when(k8sService.queryResourcesInAnyNamespace(Kafka.class))
            .thenReturn(List.of(kafka1, kafka2));

        ToolCallException ex = assertThrows(ToolCallException.class,
            () -> kafkaMetricsService.getKafkaMetrics(null, "my-cluster", null, null, null, null));
        assertTrue(ex.getMessage().contains("Multiple clusters"));
        assertTrue(ex.getMessage().contains("Specify a namespace"));
    }

    @Test
    void rangeQueryParametersPassedThrough() {
        Kafka kafka = createKafka("my-cluster", "kafka");
        Pod pod = createPod("my-cluster-kafka-0", "kafka");

        when(k8sService.getResource(eq(Kafka.class), eq("kafka"), eq("my-cluster")))
            .thenReturn(kafka);
        when(k8sService.queryResourcesByLabel(eq(Pod.class), eq("kafka"),
            eq(ResourceLabels.STRIMZI_CLUSTER_LABEL), eq("my-cluster")))
            .thenReturn(List.of(pod));
        when(metricsQueryService.queryMetrics(anyList(), anyMap(), anyList(), eq(60), eq(15)))
            .thenReturn(List.of());

        KafkaMetricsResponse response = kafkaMetricsService.getKafkaMetrics(
            "kafka", "my-cluster", "replication", null, 60, 15);

        assertNotNull(response);
    }

    @Test
    void categoryAndExplicitMetricsMergedWithoutDuplicates() {
        Kafka kafka = createKafka("my-cluster", "kafka");
        Pod pod = createPod("my-cluster-kafka-0", "kafka");

        when(k8sService.getResource(eq(Kafka.class), eq("kafka"), eq("my-cluster")))
            .thenReturn(kafka);
        when(k8sService.queryResourcesByLabel(eq(Pod.class), eq("kafka"),
            eq(ResourceLabels.STRIMZI_CLUSTER_LABEL), eq("my-cluster")))
            .thenReturn(List.of(pod));

        // Provide a metric name that's already in the replication category
        KafkaMetricsResponse response = kafkaMetricsService.getKafkaMetrics(
            "kafka", "my-cluster", "replication",
            "kafka_server_replicamanager_underreplicatedpartitions,custom_metric", null, null);

        assertNotNull(response);
    }

    private Kafka createKafka(final String name, final String namespace) {
        Kafka kafka = new Kafka();
        ObjectMeta meta = new ObjectMeta();
        meta.setName(name);
        meta.setNamespace(namespace);
        kafka.setMetadata(meta);
        return kafka;
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
