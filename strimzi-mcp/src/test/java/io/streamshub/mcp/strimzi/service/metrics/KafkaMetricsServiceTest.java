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
import io.streamshub.mcp.strimzi.dto.metrics.KafkaMetricsResponse;
import io.streamshub.mcp.strimzi.service.KafkaService;
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

    @Mock
    KafkaService kafkaService;

    private KafkaMetricsService kafkaMetricsService;

    @BeforeEach
    void setUp() throws Exception {
        kafkaMetricsService = new KafkaMetricsService();
        setField(kafkaMetricsService, "k8sService", k8sService);
        setField(kafkaMetricsService, "metricsQueryService", metricsQueryService);
        setField(kafkaMetricsService, "kafkaService", kafkaService);

        when(metricsQueryService.providerName()).thenReturn("pod-scraping");
        when(metricsQueryService.queryMetrics(anyList(), anyMap(), anyList(), any(), any(), any(), any()))
            .thenReturn(List.of());
    }

    @Test
    void missingClusterNameThrows() {
        ToolCallException ex = assertThrows(ToolCallException.class,
            () -> kafkaMetricsService.getKafkaMetrics("kafka", null, null, null, null, null, null, null, null, null));
        assertTrue(ex.getMessage().contains("Cluster name is required"));
    }

    @Test
    void clusterNotFoundInNamespaceThrows() {
        when(kafkaService.findKafkaCluster("kafka", "missing"))
            .thenThrow(new ToolCallException("Kafka cluster 'missing' not found in namespace kafka"));

        ToolCallException ex = assertThrows(ToolCallException.class,
            () -> kafkaMetricsService.getKafkaMetrics("kafka", "missing", null, null, null, null, null, null, null, null));
        assertTrue(ex.getMessage().contains("not found in namespace"));
    }

    @Test
    void clusterNotFoundInAnyNamespaceThrows() {
        when(kafkaService.findKafkaCluster(null, "missing"))
            .thenThrow(new ToolCallException("Kafka cluster 'missing' not found in any namespace"));

        ToolCallException ex = assertThrows(ToolCallException.class,
            () -> kafkaMetricsService.getKafkaMetrics(null, "missing", null, null, null, null, null, null, null, null));
        assertTrue(ex.getMessage().contains("not found in any namespace"));
    }

    @Test
    void unknownCategoryThrows() {
        Kafka kafka = createKafka("my-cluster", "kafka");
        when(kafkaService.findKafkaCluster("kafka", "my-cluster"))
            .thenReturn(kafka);

        ToolCallException ex = assertThrows(ToolCallException.class,
            () -> kafkaMetricsService.getKafkaMetrics("kafka", "my-cluster", "invalid", null, null, null, null, null, null, null));
        assertTrue(ex.getMessage().contains("Unknown metric category"));
    }

    @Test
    void emptyPodsReturnsEmptyResponse() {
        Kafka kafka = createKafka("my-cluster", "kafka");
        when(kafkaService.findKafkaCluster("kafka", "my-cluster"))
            .thenReturn(kafka);
        when(k8sService.queryResourcesByLabel(eq(Pod.class), eq("kafka"),
            eq(ResourceLabels.STRIMZI_CLUSTER_LABEL), eq("my-cluster")))
            .thenReturn(List.of());

        KafkaMetricsResponse response = kafkaMetricsService.getKafkaMetrics(
            "kafka", "my-cluster", null, null, null, null, null, null, null, null);

        assertNotNull(response);
        assertEquals(0, response.sampleCount());
        assertTrue(response.message().contains("No Kafka pods"));
    }

    @Test
    void successfulMetricsRetrieval() {
        Kafka kafka = createKafka("my-cluster", "kafka");
        Pod pod = createPod("my-cluster-kafka-0", "kafka");

        when(kafkaService.findKafkaCluster("kafka", "my-cluster"))
            .thenReturn(kafka);
        when(k8sService.queryResourcesByLabel(eq(Pod.class), eq("kafka"),
            eq(ResourceLabels.STRIMZI_CLUSTER_LABEL), eq("my-cluster")))
            .thenReturn(List.of(pod));

        List<MetricSample> samples = List.of(
            MetricSample.of("kafka_server_replicamanager_underreplicatedpartitions",
                Map.of("namespace", "kafka"), 0.0));
        when(metricsQueryService.queryMetrics(anyList(), anyMap(), anyList(), isNull(), isNull(), isNull(), isNull()))
            .thenReturn(samples);

        KafkaMetricsResponse response = kafkaMetricsService.getKafkaMetrics(
            "kafka", "my-cluster", "replication", null, null, null, null, null, null, null);

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

        when(kafkaService.findKafkaCluster("kafka", "my-cluster"))
            .thenReturn(kafka);
        when(k8sService.queryResourcesByLabel(eq(Pod.class), eq("kafka"),
            eq(ResourceLabels.STRIMZI_CLUSTER_LABEL), eq("my-cluster")))
            .thenReturn(List.of(pod));

        KafkaMetricsResponse response = kafkaMetricsService.getKafkaMetrics(
            "kafka", "my-cluster", null, null, null, null, null, null, null, null);

        assertNotNull(response);
        assertEquals("my-cluster", response.clusterName());
    }

    @Test
    void explicitMetricNamesAreAccepted() {
        Kafka kafka = createKafka("my-cluster", "kafka");
        Pod pod = createPod("my-cluster-kafka-0", "kafka");

        when(kafkaService.findKafkaCluster("kafka", "my-cluster"))
            .thenReturn(kafka);
        when(k8sService.queryResourcesByLabel(eq(Pod.class), eq("kafka"),
            eq(ResourceLabels.STRIMZI_CLUSTER_LABEL), eq("my-cluster")))
            .thenReturn(List.of(pod));

        KafkaMetricsResponse response = kafkaMetricsService.getKafkaMetrics(
            "kafka", "my-cluster", null, "custom_metric_a,custom_metric_b", null, null, null, null, null, null);

        assertNotNull(response);
    }

    @Test
    void multipleClustersWithSameNameThrows() {
        when(kafkaService.findKafkaCluster(null, "my-cluster"))
            .thenThrow(new ToolCallException(
                "Multiple clusters named 'my-cluster' found in namespaces: kafka-a, kafka-b. Please specify namespace."));

        ToolCallException ex = assertThrows(ToolCallException.class,
            () -> kafkaMetricsService.getKafkaMetrics(null, "my-cluster", null, null, null, null, null, null, null, null));
        assertTrue(ex.getMessage().contains("Multiple clusters"));
    }

    @Test
    void rangeQueryParametersPassedThrough() {
        Kafka kafka = createKafka("my-cluster", "kafka");
        Pod pod = createPod("my-cluster-kafka-0", "kafka");

        when(kafkaService.findKafkaCluster("kafka", "my-cluster"))
            .thenReturn(kafka);
        when(k8sService.queryResourcesByLabel(eq(Pod.class), eq("kafka"),
            eq(ResourceLabels.STRIMZI_CLUSTER_LABEL), eq("my-cluster")))
            .thenReturn(List.of(pod));
        when(metricsQueryService.queryMetrics(anyList(), anyMap(), anyList(), eq(60), isNull(), isNull(), eq(15)))
            .thenReturn(List.of());

        KafkaMetricsResponse response = kafkaMetricsService.getKafkaMetrics(
            "kafka", "my-cluster", "replication", null, 60, null, null, 15, null, null);

        assertNotNull(response);
    }

    @Test
    void categoryAndExplicitMetricsMergedWithoutDuplicates() {
        Kafka kafka = createKafka("my-cluster", "kafka");
        Pod pod = createPod("my-cluster-kafka-0", "kafka");

        when(kafkaService.findKafkaCluster("kafka", "my-cluster"))
            .thenReturn(kafka);
        when(k8sService.queryResourcesByLabel(eq(Pod.class), eq("kafka"),
            eq(ResourceLabels.STRIMZI_CLUSTER_LABEL), eq("my-cluster")))
            .thenReturn(List.of(pod));

        // Provide a metric name that's already in the replication category
        KafkaMetricsResponse response = kafkaMetricsService.getKafkaMetrics(
            "kafka", "my-cluster", "replication",
            "kafka_server_replicamanager_underreplicatedpartitions,custom_metric", null, null, null, null, null, null);

        assertNotNull(response);
    }

    @Test
    void nonKafkaPodsAreFilteredOut() {
        Kafka kafka = createKafka("my-cluster", "kafka");
        Pod kafkaPod = createPod("my-cluster-kafka-0", "kafka");
        Pod ccPod = createPod("my-cluster-cruise-control-0", "kafka", "cruise-control");
        Pod nolabelPod = createPod("my-cluster-unknown-0", "kafka", null);

        when(kafkaService.findKafkaCluster("kafka", "my-cluster"))
            .thenReturn(kafka);
        when(k8sService.queryResourcesByLabel(eq(Pod.class), eq("kafka"),
            eq(ResourceLabels.STRIMZI_CLUSTER_LABEL), eq("my-cluster")))
            .thenReturn(List.of(kafkaPod, ccPod, nolabelPod));

        List<MetricSample> samples = List.of(
            MetricSample.of("kafka_server_replicamanager_underreplicatedpartitions",
                Map.of("namespace", "kafka"), 0.0));
        when(metricsQueryService.queryMetrics(anyList(), anyMap(), anyList(), isNull(), isNull(), isNull(), isNull()))
            .thenReturn(samples);

        KafkaMetricsResponse response = kafkaMetricsService.getKafkaMetrics(
            "kafka", "my-cluster", "replication", null, null, null, null, null, null, null);

        assertNotNull(response);
        assertEquals(1, response.sampleCount());
    }

    @Test
    void requestTypesFilterKeepsMatchingAndNonRequestSamples() {
        Kafka kafka = createKafka("my-cluster", "kafka");
        Pod pod = createPod("my-cluster-kafka-0", "kafka");

        when(kafkaService.findKafkaCluster("kafka", "my-cluster"))
            .thenReturn(kafka);
        when(k8sService.queryResourcesByLabel(eq(Pod.class), eq("kafka"),
            eq(ResourceLabels.STRIMZI_CLUSTER_LABEL), eq("my-cluster")))
            .thenReturn(List.of(pod));

        List<MetricSample> samples = List.of(
            MetricSample.of("kafka_network_requestmetrics_totaltimems",
                Map.of("request", "Produce", "quantile", "0.99"), 5.0),
            MetricSample.of("kafka_network_requestmetrics_totaltimems",
                Map.of("request", "Fetch", "quantile", "0.99"), 3.0),
            MetricSample.of("kafka_network_requestmetrics_totaltimems",
                Map.of("request", "Metadata", "quantile", "0.99"), 1.0),
            MetricSample.of("kafka_server_replicamanager_underreplicatedpartitions",
                Map.of("namespace", "kafka"), 0.0));
        when(metricsQueryService.queryMetrics(anyList(), anyMap(), anyList(), isNull(), isNull(), isNull(), isNull()))
            .thenReturn(samples);

        KafkaMetricsResponse response = kafkaMetricsService.getKafkaMetrics(
            "kafka", "my-cluster", "performance", null, null, null, null, null, null, "Produce,Fetch");

        assertNotNull(response);
        assertEquals(3, response.sampleCount());
    }

    @Test
    void requestTypesFilterNullPassesAllThrough() {
        Kafka kafka = createKafka("my-cluster", "kafka");
        Pod pod = createPod("my-cluster-kafka-0", "kafka");

        when(kafkaService.findKafkaCluster("kafka", "my-cluster"))
            .thenReturn(kafka);
        when(k8sService.queryResourcesByLabel(eq(Pod.class), eq("kafka"),
            eq(ResourceLabels.STRIMZI_CLUSTER_LABEL), eq("my-cluster")))
            .thenReturn(List.of(pod));

        List<MetricSample> samples = List.of(
            MetricSample.of("kafka_network_requestmetrics_totaltimems",
                Map.of("request", "Produce"), 5.0),
            MetricSample.of("kafka_network_requestmetrics_totaltimems",
                Map.of("request", "Metadata"), 1.0));
        when(metricsQueryService.queryMetrics(anyList(), anyMap(), anyList(), isNull(), isNull(), isNull(), isNull()))
            .thenReturn(samples);

        KafkaMetricsResponse response = kafkaMetricsService.getKafkaMetrics(
            "kafka", "my-cluster", "performance", null, null, null, null, null, null, null);

        assertNotNull(response);
        assertEquals(2, response.sampleCount());
    }

    @Test
    void aggregationClampedToBrokerForReplicationCategory() {
        Kafka kafka = createKafka("my-cluster", "kafka");
        Pod pod = createPod("my-cluster-kafka-0", "kafka");

        when(kafkaService.findKafkaCluster("kafka", "my-cluster"))
            .thenReturn(kafka);
        when(k8sService.queryResourcesByLabel(eq(Pod.class), eq("kafka"),
            eq(ResourceLabels.STRIMZI_CLUSTER_LABEL), eq("my-cluster")))
            .thenReturn(List.of(pod));

        KafkaMetricsResponse response = kafkaMetricsService.getKafkaMetrics(
            "kafka", "my-cluster", "replication", null, null, null, null, null, "partition", null);

        assertEquals("broker", response.aggregation());
    }

    @Test
    void aggregationNotClampedForThroughputAtTopicLevel() {
        Kafka kafka = createKafka("my-cluster", "kafka");
        Pod pod = createPod("my-cluster-kafka-0", "kafka");

        when(kafkaService.findKafkaCluster("kafka", "my-cluster"))
            .thenReturn(kafka);
        when(k8sService.queryResourcesByLabel(eq(Pod.class), eq("kafka"),
            eq(ResourceLabels.STRIMZI_CLUSTER_LABEL), eq("my-cluster")))
            .thenReturn(List.of(pod));

        KafkaMetricsResponse response = kafkaMetricsService.getKafkaMetrics(
            "kafka", "my-cluster", "throughput", null, null, null, null, null, "topic", null);

        assertEquals("topic", response.aggregation());
    }

    @Test
    void aggregationClampedToTopicForThroughputAtPartitionLevel() {
        Kafka kafka = createKafka("my-cluster", "kafka");
        Pod pod = createPod("my-cluster-kafka-0", "kafka");

        when(kafkaService.findKafkaCluster("kafka", "my-cluster"))
            .thenReturn(kafka);
        when(k8sService.queryResourcesByLabel(eq(Pod.class), eq("kafka"),
            eq(ResourceLabels.STRIMZI_CLUSTER_LABEL), eq("my-cluster")))
            .thenReturn(List.of(pod));

        KafkaMetricsResponse response = kafkaMetricsService.getKafkaMetrics(
            "kafka", "my-cluster", "throughput", null, null, null, null, null, "partition", null);

        assertEquals("topic", response.aggregation());
    }

    @Test
    void aggregationNotClampedWhenExplicitMetricNamesOnly() {
        Kafka kafka = createKafka("my-cluster", "kafka");
        Pod pod = createPod("my-cluster-kafka-0", "kafka");

        when(kafkaService.findKafkaCluster("kafka", "my-cluster"))
            .thenReturn(kafka);
        when(k8sService.queryResourcesByLabel(eq(Pod.class), eq("kafka"),
            eq(ResourceLabels.STRIMZI_CLUSTER_LABEL), eq("my-cluster")))
            .thenReturn(List.of(pod));

        KafkaMetricsResponse response = kafkaMetricsService.getKafkaMetrics(
            "kafka", "my-cluster", null, "custom_metric", null, null, null, null, "partition", null);

        assertEquals("partition", response.aggregation());
    }

    @Test
    void aggregationClusterAlwaysPassesThrough() {
        Kafka kafka = createKafka("my-cluster", "kafka");
        Pod pod = createPod("my-cluster-kafka-0", "kafka");

        when(kafkaService.findKafkaCluster("kafka", "my-cluster"))
            .thenReturn(kafka);
        when(k8sService.queryResourcesByLabel(eq(Pod.class), eq("kafka"),
            eq(ResourceLabels.STRIMZI_CLUSTER_LABEL), eq("my-cluster")))
            .thenReturn(List.of(pod));

        KafkaMetricsResponse response = kafkaMetricsService.getKafkaMetrics(
            "kafka", "my-cluster", "replication", null, null, null, null, null, "cluster", null);

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

    private Pod createPod(final String name, final String namespace) {
        return createPod(name, namespace, StrimziConstants.ComponentTypes.KAFKA);
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
