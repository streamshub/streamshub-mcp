/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.service;

import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.Pod;
import io.quarkiverse.mcp.server.ToolCallException;
import io.streamshub.mcp.common.dto.LogCollectionParams;
import io.streamshub.mcp.common.dto.PodLogsResult;
import io.streamshub.mcp.common.service.KubernetesResourceService;
import io.streamshub.mcp.common.service.log.LogCollectionService;
import io.streamshub.mcp.strimzi.dto.KafkaClusterLogsResponse;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for Kafka log collection via {@link KafkaService#getClusterLogs}.
 * Verifies that the service correctly delegates to {@link LogCollectionService}
 * regardless of the underlying log provider (Kubernetes or Loki).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class KafkaLogCollectionTest {

    @Mock
    KubernetesResourceService k8sService;

    @Mock
    LogCollectionService logCollectionService;

    @Mock
    KafkaNodePoolService nodePoolService;

    private KafkaService kafkaService;

    KafkaLogCollectionTest() {
    }

    @BeforeEach
    void setUp() throws Exception {
        kafkaService = new KafkaService();
        setField(kafkaService, "k8sService", k8sService);
        setField(kafkaService, "logCollectionService", logCollectionService);
        setField(kafkaService, "nodePoolService", nodePoolService);
    }

    @Test
    void testMissingClusterNameThrows() {
        LogCollectionParams options = LogCollectionParams.of(null, null, 200, null);

        assertThrows(ToolCallException.class,
            () -> kafkaService.getClusterLogs("kafka", null, options));

        verifyNoInteractions(logCollectionService);
    }

    @Test
    void testEmptyPodsReturnsEmptyResponse() {
        when(k8sService.queryResourcesByLabel(eq(Pod.class), eq("kafka"),
            eq(ResourceLabels.STRIMZI_CLUSTER_LABEL), eq("my-cluster")))
            .thenReturn(List.of());

        LogCollectionParams options = LogCollectionParams.of(null, null, 200, null);

        KafkaClusterLogsResponse response = kafkaService.getClusterLogs("kafka", "my-cluster", options);

        assertNotNull(response);
        assertEquals("my-cluster", response.clusterName());
        assertEquals("kafka", response.namespace());
        verifyNoInteractions(logCollectionService);
    }

    @Test
    void testSuccessfulLogCollection() {
        Pod pod = createPod("my-cluster-kafka-0", "kafka");

        when(k8sService.queryResourcesByLabel(eq(Pod.class), eq("kafka"),
            eq(ResourceLabels.STRIMZI_CLUSTER_LABEL), eq("my-cluster")))
            .thenReturn(List.of(pod));

        PodLogsResult logsResult = new PodLogsResult(
            List.of("my-cluster-kafka-0"),
            "=== Pod: my-cluster-kafka-0 ===\nERROR Something failed\n",
            1, 10, 1, false);

        when(logCollectionService.collectLogs(eq("kafka"), any(), any()))
            .thenReturn(logsResult);

        LogCollectionParams options = LogCollectionParams.of("errors", null, 200, null);

        KafkaClusterLogsResponse response = kafkaService.getClusterLogs("kafka", "my-cluster", options);

        assertNotNull(response);
        assertEquals("my-cluster", response.clusterName());
        assertEquals("kafka", response.namespace());
        assertTrue(response.hasErrors());
        assertEquals(1, response.errorCount());
        assertEquals(10, response.logLines());
        assertTrue(response.logs().contains("ERROR Something failed"));

        verify(logCollectionService).collectLogs(eq("kafka"), any(), any());
    }

    @Test
    void testLogCollectionWithMultiplePods() {
        Pod pod1 = createPod("my-cluster-kafka-0", "kafka");
        Pod pod2 = createPod("my-cluster-kafka-1", "kafka");
        Pod pod3 = createPod("my-cluster-kafka-2", "kafka");

        when(k8sService.queryResourcesByLabel(eq(Pod.class), eq("kafka"),
            eq(ResourceLabels.STRIMZI_CLUSTER_LABEL), eq("my-cluster")))
            .thenReturn(List.of(pod1, pod2, pod3));

        PodLogsResult logsResult = new PodLogsResult(
            List.of("my-cluster-kafka-0", "my-cluster-kafka-1", "my-cluster-kafka-2"),
            "logs from 3 pods", 0, 30, 30, false);

        when(logCollectionService.collectLogs(eq("kafka"), any(), any()))
            .thenReturn(logsResult);

        LogCollectionParams options = LogCollectionParams.of(null, null, 200, null);

        KafkaClusterLogsResponse response = kafkaService.getClusterLogs("kafka", "my-cluster", options);

        assertNotNull(response);
        assertEquals(3, response.pods().size());
        assertFalse(response.hasErrors());
        assertEquals(30, response.logLines());
    }

    @Test
    void testLogCollectionWithHasMore() {
        Pod pod = createPod("my-cluster-kafka-0", "kafka");

        when(k8sService.queryResourcesByLabel(eq(Pod.class), eq("kafka"),
            eq(ResourceLabels.STRIMZI_CLUSTER_LABEL), eq("my-cluster")))
            .thenReturn(List.of(pod));

        PodLogsResult logsResult = new PodLogsResult(
            List.of("my-cluster-kafka-0"),
            "truncated logs", 0, 200, 200, true);

        when(logCollectionService.collectLogs(eq("kafka"), any(), any()))
            .thenReturn(logsResult);

        LogCollectionParams options = LogCollectionParams.of(null, null, 200, null);

        KafkaClusterLogsResponse response = kafkaService.getClusterLogs("kafka", "my-cluster", options);

        assertTrue(response.hasMore());
    }

    @Test
    void testLogCollectionPassesOptionsThrough() {
        Pod pod = createPod("my-cluster-kafka-0", "kafka");

        when(k8sService.queryResourcesByLabel(eq(Pod.class), eq("kafka"),
            eq(ResourceLabels.STRIMZI_CLUSTER_LABEL), eq("my-cluster")))
            .thenReturn(List.of(pod));

        when(logCollectionService.collectLogs(any(), any(), any()))
            .thenReturn(new PodLogsResult(List.of(), "", 0, 0, 0, false));

        LogCollectionParams options = LogCollectionParams.builder(100)
            .filter("errors")
            .sinceSeconds(300)
            .previous(true)
            .build();

        kafkaService.getClusterLogs("kafka", "my-cluster", options);

        verify(logCollectionService).collectLogs(eq("kafka"), any(), eq(options));
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
