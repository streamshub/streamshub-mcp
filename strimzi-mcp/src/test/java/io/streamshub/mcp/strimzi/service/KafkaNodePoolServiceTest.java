/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.service;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.quarkiverse.mcp.server.ToolCallException;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.streamshub.mcp.strimzi.dto.KafkaNodePoolResponse;
import io.strimzi.api.kafka.model.nodepool.KafkaNodePool;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Service-level tests for {@link KafkaNodePoolService} with mocked Kubernetes.
 */
@QuarkusTest
class KafkaNodePoolServiceTest {

    @InjectMock
    KubernetesClient kubernetesClient;

    @Inject
    KafkaNodePoolService nodePoolService;

    KafkaNodePoolServiceTest() {
    }

    @BeforeEach
    void setUp() {
        KubernetesMockHelper.setupEmptyResourceQuery(kubernetesClient, KafkaNodePool.class);
    }

    /**
     * Verify list returns empty when no node pools exist for the cluster.
     */
    @Test
    void testListNodePoolsReturnsEmptyWhenNoneExist() {
        List<KafkaNodePoolResponse> result = nodePoolService.listNodePools("kafka", "my-cluster");

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    /**
     * Verify list searches all namespaces when namespace is null.
     */
    @Test
    void testListNodePoolsAllNamespaces() {
        List<KafkaNodePoolResponse> result = nodePoolService.listNodePools(null, "my-cluster");

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    /**
     * Verify list throws when cluster name is null.
     */
    @Test
    void testListNodePoolsThrowsWhenClusterNameNull() {
        ToolCallException ex = assertThrows(ToolCallException.class,
            () -> nodePoolService.listNodePools("kafka", null));

        assertTrue(ex.getMessage().contains("required"));
    }

    /**
     * Verify get throws when node pool name is null.
     */
    @Test
    void testGetNodePoolThrowsWhenNameNull() {
        ToolCallException ex = assertThrows(ToolCallException.class,
            () -> nodePoolService.getNodePool("kafka", "my-cluster", null));

        assertTrue(ex.getMessage().contains("required"));
    }

    /**
     * Verify get throws when node pool is not found in namespace.
     */
    @Test
    void testGetNodePoolThrowsWhenNotFoundInNamespace() {
        ToolCallException ex = assertThrows(ToolCallException.class,
            () -> nodePoolService.getNodePool("kafka", "my-cluster", "nonexistent"));

        assertTrue(ex.getMessage().contains("not found"));
    }

    /**
     * Verify get throws when node pool is not found in any namespace.
     */
    @Test
    void testGetNodePoolThrowsWhenNotFoundInAnyNamespace() {
        ToolCallException ex = assertThrows(ToolCallException.class,
            () -> nodePoolService.getNodePool(null, "my-cluster", "nonexistent"));

        assertTrue(ex.getMessage().contains("not found"));
    }
}
