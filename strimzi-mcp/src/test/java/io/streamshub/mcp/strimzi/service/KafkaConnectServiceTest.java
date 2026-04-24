/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.service;

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodList;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.PodResource;
import io.quarkiverse.mcp.server.ToolCallException;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.streamshub.mcp.strimzi.dto.kafkaconnect.KafkaConnectLogsResponse;
import io.streamshub.mcp.strimzi.dto.kafkaconnect.KafkaConnectPodsResponse;
import io.streamshub.mcp.strimzi.dto.kafkaconnect.KafkaConnectResponse;
import io.streamshub.mcp.strimzi.service.kafkaconnect.KafkaConnectService;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Service-level tests for {@link KafkaConnectService} with mocked Kubernetes.
 */
@QuarkusTest
class KafkaConnectServiceTest {

    @InjectMock
    KubernetesClient kubernetesClient;

    @Inject
    KafkaConnectService connectService;

    KafkaConnectServiceTest() {
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        MixedOperation<Pod, PodList, PodResource> podOp = Mockito.mock(MixedOperation.class);
        Mockito.lenient().when(kubernetesClient.pods()).thenReturn(podOp);
    }

    /**
     * Verify list returns empty when no KafkaConnect clusters exist.
     */
    @Test
    void testListConnectsReturnsEmptyWhenNoneExist() {
        List<KafkaConnectResponse> result = connectService.listConnects("production");

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    /**
     * Verify get throws when connect name is null.
     */
    @Test
    void testGetConnectThrowsWhenNameIsNull() {
        ToolCallException ex = assertThrows(ToolCallException.class,
            () -> connectService.getConnect("kafka", null));

        assertTrue(ex.getMessage().contains("required"));
    }

    /**
     * Verify pods returns empty for a missing KafkaConnect cluster.
     */
    @Test
    void testGetConnectPodsReturnsEmptyForMissingCluster() {
        KafkaConnectPodsResponse result = connectService.getConnectPods("kafka", "my-connect");

        assertNotNull(result);
        assertEquals("kafka", result.namespace());
        assertEquals("my-connect", result.connectName());
        assertNotNull(result.podSummary());
        assertEquals(0, result.podSummary().totalPods());
    }

    /**
     * Verify logs returns empty when no pods are found.
     */
    @Test
    void testGetConnectLogsReturnsEmptyWhenNoPods() {
        io.streamshub.mcp.common.dto.LogCollectionParams params =
            io.streamshub.mcp.common.dto.LogCollectionParams.builder(200).build();
        KafkaConnectLogsResponse result = connectService.getConnectLogs("kafka", "my-connect", params);

        assertNotNull(result);
        assertEquals("kafka", result.namespace());
        assertEquals("my-connect", result.connectName());
        assertTrue(result.message().contains("No KafkaConnect pods found"));
    }
}
