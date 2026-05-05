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
import io.streamshub.mcp.strimzi.dto.kafkabridge.KafkaBridgeLogsResponse;
import io.streamshub.mcp.strimzi.dto.kafkabridge.KafkaBridgePodsResponse;
import io.streamshub.mcp.strimzi.dto.kafkabridge.KafkaBridgeResponse;
import io.streamshub.mcp.strimzi.service.kafkabridge.KafkaBridgeService;
import io.strimzi.api.kafka.model.bridge.KafkaBridge;
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
 * Service-level tests for {@link KafkaBridgeService} with mocked Kubernetes.
 */
@QuarkusTest
class KafkaBridgeServiceTest {

    @InjectMock
    KubernetesClient kubernetesClient;

    @Inject
    KafkaBridgeService bridgeService;

    KafkaBridgeServiceTest() {
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        MixedOperation<Pod, PodList, PodResource> podOp = Mockito.mock(MixedOperation.class);
        Mockito.lenient().when(kubernetesClient.pods()).thenReturn(podOp);

        KubernetesMockHelper.setupEmptyResourceQuery(kubernetesClient, KafkaBridge.class);
        KubernetesMockHelper.setupEmptyResourceQuery(kubernetesClient, Pod.class);
    }

    /**
     * Verify list returns empty when no KafkaBridge resources exist.
     */
    @Test
    void testListBridgesReturnsEmptyWhenNoneExist() {
        List<KafkaBridgeResponse> result = bridgeService.listBridges("production");

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    /**
     * Verify get throws when bridge name is null.
     */
    @Test
    void testGetBridgeThrowsWhenNameIsNull() {
        ToolCallException ex = assertThrows(ToolCallException.class,
            () -> bridgeService.getBridge("kafka", null));

        assertTrue(ex.getMessage().contains("required"));
    }

    /**
     * Verify pods returns empty for a missing KafkaBridge.
     */
    @Test
    void testGetBridgePodsReturnsEmptyForMissingBridge() {
        KafkaBridgePodsResponse result = bridgeService.getBridgePods("kafka", "my-bridge");

        assertNotNull(result);
        assertEquals("kafka", result.namespace());
        assertEquals("my-bridge", result.bridgeName());
        assertNotNull(result.podSummary());
        assertEquals(0, result.podSummary().totalPods());
    }

    /**
     * Verify logs returns empty when no pods are found.
     */
    @Test
    void testGetBridgeLogsReturnsEmptyWhenNoPods() {
        io.streamshub.mcp.common.dto.LogCollectionParams params =
            io.streamshub.mcp.common.dto.LogCollectionParams.builder(200).build();
        KafkaBridgeLogsResponse result = bridgeService.getBridgeLogs("kafka", "my-bridge", params);

        assertNotNull(result);
        assertEquals("kafka", result.namespace());
        assertEquals("my-bridge", result.bridgeName());
        assertTrue(result.message().contains("No KafkaBridge pods found"));
    }
}
