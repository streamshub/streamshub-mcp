/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.service;

import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.FilterWatchListDeletable;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.NonNamespaceOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.quarkiverse.mcp.server.ToolCallException;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.streamshub.mcp.strimzi.dto.KafkaConfigComparisonReport;
import io.strimzi.api.kafka.model.kafka.Kafka;
import io.strimzi.api.kafka.model.kafka.KafkaBuilder;
import io.strimzi.api.kafka.model.nodepool.KafkaNodePool;
import io.strimzi.api.kafka.model.nodepool.KafkaNodePoolList;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link KafkaConfigComparisonService}.
 */
@QuarkusTest
class KafkaConfigComparisonServiceTest {

    @InjectMock
    KubernetesClient kubernetesClient;

    @Inject
    KafkaConfigComparisonService comparisonService;

    KafkaConfigComparisonServiceTest() {
    }

    @BeforeEach
    void setUp() {
        setupEmptyNodePools("kafka");
        setupEmptyNodePools("kafka-prod");
    }

    @Test
    void testThrowsWhenCluster1NameMissing() {
        assertThrows(ToolCallException.class, () ->
            comparisonService.compare("kafka", null, "kafka", "cluster-b",
                null, null, null, null, null));
    }

    @Test
    void testThrowsWhenCluster2NameMissing() {
        assertThrows(ToolCallException.class, () ->
            comparisonService.compare("kafka", "cluster-a", "kafka", null,
                null, null, null, null, null));
    }

    @Test
    void testComparisonWithTwoClusters() {
        setupTwoKafkaClusters(
            "cluster-a", "kafka", Map.of("log.retention.hours", 168, "num.partitions", 3), "3.9.0",
            "cluster-b", "kafka", Map.of("log.retention.hours", 72, "num.partitions", 6), "3.8.0");

        KafkaConfigComparisonReport report = comparisonService.compare(
            "kafka", "cluster-a", "kafka", "cluster-b",
            null, null, null, null, null);

        assertNotNull(report);
        assertNotNull(report.cluster1Config());
        assertNotNull(report.cluster2Config());
        assertEquals("cluster-a", report.cluster1Config().name());
        assertEquals("cluster-b", report.cluster2Config().name());
        assertEquals("3.9.0", report.cluster1Config().kafkaVersion());
        assertEquals("3.8.0", report.cluster2Config().kafkaVersion());
        // No sampling → analysis should be null
        assertNull(report.analysis());
        assertNotNull(report.stepsCompleted());
        assertTrue(report.stepsCompleted().contains("cluster1_config"));
        assertTrue(report.stepsCompleted().contains("cluster2_config"));
        assertNotNull(report.timestamp());
        assertNotNull(report.message());
        assertTrue(report.message().contains("cluster-a"));
        assertTrue(report.message().contains("cluster-b"));
    }

    @Test
    void testReportMetadata() {
        setupTwoKafkaClusters(
            "cluster-a", "kafka", Map.of(), "3.9.0",
            "cluster-b", "kafka", Map.of(), "3.9.0");

        KafkaConfigComparisonReport report = comparisonService.compare(
            "kafka", "cluster-a", "kafka", "cluster-b",
            null, null, null, null, null);

        assertNotNull(report.timestamp());
        assertTrue(report.message().contains("2 steps succeeded"));
        assertNull(report.stepsFailed());
    }

    // ---- Test helpers ----

    @SuppressWarnings("unchecked")
    private void setupTwoKafkaClusters(final String name1, final String ns1,
                                       final Map<String, Object> config1, final String version1,
                                       final String name2, final String ns2,
                                       final Map<String, Object> config2, final String version2) {
        Kafka kafka1 = buildKafka(name1, ns1, config1, version1);
        Kafka kafka2 = buildKafka(name2, ns2, config2, version2);

        MixedOperation kafkaOp = Mockito.mock(MixedOperation.class);
        when(kubernetesClient.resources(Kafka.class)).thenReturn(kafkaOp);

        NonNamespaceOperation nsKafkaOp1 = Mockito.mock(NonNamespaceOperation.class);
        when(kafkaOp.inNamespace(ns1)).thenReturn(nsKafkaOp1);
        Resource kafkaResource1 = Mockito.mock(Resource.class);
        when(nsKafkaOp1.withName(name1)).thenReturn(kafkaResource1);
        when(kafkaResource1.get()).thenReturn(kafka1);

        if (!ns1.equals(ns2)) {
            NonNamespaceOperation nsKafkaOp2 = Mockito.mock(NonNamespaceOperation.class);
            when(kafkaOp.inNamespace(ns2)).thenReturn(nsKafkaOp2);
            Resource kafkaResource2 = Mockito.mock(Resource.class);
            when(nsKafkaOp2.withName(name2)).thenReturn(kafkaResource2);
            when(kafkaResource2.get()).thenReturn(kafka2);
        } else {
            Resource kafkaResource2 = Mockito.mock(Resource.class);
            when(nsKafkaOp1.withName(name2)).thenReturn(kafkaResource2);
            when(kafkaResource2.get()).thenReturn(kafka2);
        }
    }

    private Kafka buildKafka(final String name, final String namespace,
                             final Map<String, Object> config, final String version) {
        return new KafkaBuilder()
            .withMetadata(new ObjectMetaBuilder().withName(name).withNamespace(namespace).build())
            .withNewSpec()
                .withNewKafka()
                    .withVersion(version)
                    .withConfig(config)
                .endKafka()
            .endSpec()
            .build();
    }

    @SuppressWarnings("unchecked")
    private void setupEmptyNodePools(final String namespace) {
        MixedOperation nodePoolOp = Mockito.mock(MixedOperation.class);
        Mockito.lenient().when(kubernetesClient.resources(KafkaNodePool.class)).thenReturn(nodePoolOp);

        NonNamespaceOperation nsNodePoolOp = Mockito.mock(NonNamespaceOperation.class);
        Mockito.lenient().when(nodePoolOp.inNamespace(namespace)).thenReturn(nsNodePoolOp);

        FilterWatchListDeletable filteredNodePools = Mockito.mock(FilterWatchListDeletable.class);
        Mockito.lenient().when(nsNodePoolOp.withLabel(anyString(), anyString())).thenReturn(filteredNodePools);

        KafkaNodePoolList emptyList = new KafkaNodePoolList();
        emptyList.setItems(List.of());
        Mockito.lenient().when(filteredNodePools.list()).thenReturn(emptyList);
    }
}
