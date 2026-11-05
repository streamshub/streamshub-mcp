/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.resource;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.Watch;
import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.kubernetes.client.WatcherException;
import io.fabric8.kubernetes.client.dsl.AnyNamespaceOperation;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.quarkiverse.mcp.server.ResourceManager;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.streamshub.mcp.strimzi.config.StrimziConstants;
import io.streamshub.mcp.strimzi.dto.kafka.KafkaClusterResponse;
import io.streamshub.mcp.strimzi.service.kafka.KafkaService;
import io.streamshub.mcp.strimzi.service.kafkanodepool.KafkaNodePoolService;
import io.strimzi.api.kafka.model.kafka.Kafka;
import io.strimzi.api.kafka.model.kafka.KafkaBuilder;
import io.strimzi.api.kafka.model.nodepool.KafkaNodePool;
import io.strimzi.api.kafka.model.nodepool.KafkaNodePoolBuilder;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests the Kubernetes watch event handling of {@link ResourceSubscriptionManager}: how ADDED,
 * MODIFIED and DELETED events become registered, updated or removed MCP resources, and how a
 * failing service or a closed watch is handled. The registered {@link Watcher} is captured from
 * the mocked Kubernetes client and driven directly.
 */
@QuarkusTest
class ResourceSubscriptionEventsTest {

    private static final String NS = "kafka";

    @InjectMock
    KubernetesClient kubernetesClient;

    @InjectMock
    KafkaService kafkaService;

    @InjectMock
    KafkaNodePoolService nodePoolService;

    @Inject
    ResourceSubscriptionManager manager;

    @Inject
    ResourceManager resourceManager;

    private ArgumentCaptor<Watcher<Kafka>> kafkaWatcher;
    private ArgumentCaptor<Watcher<KafkaNodePool>> nodePoolWatcher;

    ResourceSubscriptionEventsTest() {
    }

    @BeforeEach
    @SuppressWarnings({"unchecked", "rawtypes"})
    void setUp() {
        manager.setShuttingDown(true);
        manager.setWatchesEnabled(true);
        manager.clearWatches();
        manager.clearState();
        manager.clearHealthState();

        kafkaWatcher = ArgumentCaptor.forClass(Watcher.class);
        nodePoolWatcher = ArgumentCaptor.forClass(Watcher.class);
        stubWatch(Kafka.class, kafkaWatcher);
        stubWatch(KafkaNodePool.class, nodePoolWatcher);

        // The other watches fail to start against the mock; shuttingDown suppresses their reconnects.
        manager.startAllWatches();
    }

    @AfterEach
    void tearDown() {
        manager.clearWatches();
        manager.clearState();
        manager.setShuttingDown(false);
        manager.setWatchesEnabled(false);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private <T extends io.fabric8.kubernetes.api.model.HasMetadata> void stubWatch(
        final Class<T> type, final ArgumentCaptor<? extends Watcher<T>> captor) {
        MixedOperation op = mock(MixedOperation.class);
        AnyNamespaceOperation anyNs = mock(AnyNamespaceOperation.class);
        Mockito.doReturn(op).when(kubernetesClient).resources(type);
        when(op.inAnyNamespace()).thenReturn(anyNs);
        when(anyNs.watch(captor.capture())).thenReturn(mock(Watch.class));
    }

    private static Kafka kafka(final String name) {
        return new KafkaBuilder().withNewMetadata().withName(name).withNamespace(NS).endMetadata().build();
    }

    private static KafkaNodePool nodePool(final String name, final String cluster) {
        return new KafkaNodePoolBuilder().withNewMetadata().withName(name).withNamespace(NS)
            .withLabels(cluster == null ? null : Map.of("strimzi.io/cluster", cluster))
            .endMetadata().build();
    }

    private static KafkaClusterResponse cluster(final String name, final String readiness) {
        return KafkaClusterResponse.of(name, NS, "Kafka", "4.2.0", "4.2.0",
            null, null, null, readiness, null, null, null, null,
            null, null, null, null, 1L, null, null, null, null, List.of());
    }

    @Test
    void testWatchersAreRegisteredForKafkaAndNodePool() {
        assertNotNull(kafkaWatcher.getValue());
        assertNotNull(nodePoolWatcher.getValue());
        assertEquals(2, manager.watchCount());
        assertEquals(Boolean.TRUE, manager.getWatchHealthState().get("Kafka"));
        assertEquals(Boolean.TRUE, manager.getWatchHealthState().get("KafkaNodePool"));
    }

    @Test
    void testKafkaAddedRegistersStatusAndTopologyResources() {
        String name = "added-cluster";
        when(kafkaService.getCluster(NS, name)).thenReturn(cluster(name, "Ready"));
        when(nodePoolService.listNodePools(NS, name)).thenReturn(List.of());
        String statusUri = StrimziConstants.ResourceUris.kafkaStatus(NS, name);
        String topologyUri = StrimziConstants.ResourceUris.kafkaTopology(NS, name);

        try {
            kafkaWatcher.getValue().eventReceived(Watcher.Action.ADDED, kafka(name));

            assertNotNull(resourceManager.getResource(statusUri), "status resource should be registered");
            assertNotNull(resourceManager.getResource(topologyUri), "topology resource should be registered");
            assertTrue(manager.containsState(statusUri));
            assertTrue(manager.containsState(topologyUri));
        } finally {
            resourceManager.removeResource(statusUri);
            resourceManager.removeResource(topologyUri);
        }
    }

    @Test
    void testKafkaModifiedIsDeduplicatedUntilPayloadChanges() {
        String name = "modified-cluster";
        when(kafkaService.getCluster(NS, name)).thenReturn(cluster(name, "Ready"));
        when(nodePoolService.listNodePools(NS, name)).thenReturn(List.of());
        String statusUri = StrimziConstants.ResourceUris.kafkaStatus(NS, name);
        String topologyUri = StrimziConstants.ResourceUris.kafkaTopology(NS, name);

        try {
            kafkaWatcher.getValue().eventReceived(Watcher.Action.ADDED, kafka(name));
            String initial = manager.getState(statusUri);
            assertNotNull(initial);
            assertTrue(initial.contains("Ready"), initial);

            // An unchanged payload leaves the stored state as is.
            kafkaWatcher.getValue().eventReceived(Watcher.Action.MODIFIED, kafka(name));
            assertEquals(initial, manager.getState(statusUri));

            // A changed payload replaces the stored state.
            when(kafkaService.getCluster(NS, name)).thenReturn(cluster(name, "NotReady"));
            kafkaWatcher.getValue().eventReceived(Watcher.Action.MODIFIED, kafka(name));
            String updated = manager.getState(statusUri);
            assertNotEquals(initial, updated);
            assertTrue(updated.contains("NotReady"), updated);

            verify(kafkaService, times(3)).getCluster(NS, name);
            assertNotNull(resourceManager.getResource(statusUri));
        } finally {
            resourceManager.removeResource(statusUri);
            resourceManager.removeResource(topologyUri);
        }
    }

    @Test
    void testKafkaDeletedRemovesResourcesAndState() {
        String name = "deleted-cluster";
        when(kafkaService.getCluster(NS, name)).thenReturn(cluster(name, "Ready"));
        when(nodePoolService.listNodePools(NS, name)).thenReturn(List.of());
        String statusUri = StrimziConstants.ResourceUris.kafkaStatus(NS, name);
        String topologyUri = StrimziConstants.ResourceUris.kafkaTopology(NS, name);

        kafkaWatcher.getValue().eventReceived(Watcher.Action.ADDED, kafka(name));
        assertNotNull(resourceManager.getResource(statusUri));

        kafkaWatcher.getValue().eventReceived(Watcher.Action.DELETED, kafka(name));

        assertNull(resourceManager.getResource(statusUri));
        assertNull(resourceManager.getResource(topologyUri));
        assertFalse(manager.containsState(statusUri));
        assertFalse(manager.containsState(topologyUri));
    }

    @Test
    void testServiceFailureDoesNotRegisterResourceOrBreakWatcher() {
        String name = "failing-cluster";
        when(kafkaService.getCluster(NS, name)).thenThrow(new RuntimeException("API unavailable"));
        when(nodePoolService.listNodePools(NS, name)).thenReturn(List.of());
        String statusUri = StrimziConstants.ResourceUris.kafkaStatus(NS, name);
        String topologyUri = StrimziConstants.ResourceUris.kafkaTopology(NS, name);

        try {
            kafkaWatcher.getValue().eventReceived(Watcher.Action.ADDED, kafka(name));

            assertNull(resourceManager.getResource(statusUri), "failed read must not register a resource");
            assertFalse(manager.containsState(statusUri));
            assertNotNull(resourceManager.getResource(topologyUri), "topology is independent of status");
        } finally {
            resourceManager.removeResource(topologyUri);
        }
    }

    @Test
    void testNodePoolEventRegistersPoolStatusAndRefreshesParentTopology() {
        String cluster = "pool-cluster";
        String pool = "pool-a";
        when(nodePoolService.getNodePool(NS, null, pool)).thenReturn(null);
        when(nodePoolService.listNodePools(NS, cluster)).thenReturn(List.of());
        String poolUri = StrimziConstants.ResourceUris.nodePoolStatus(NS, pool);
        String topologyUri = StrimziConstants.ResourceUris.kafkaTopology(NS, cluster);

        try {
            nodePoolWatcher.getValue().eventReceived(Watcher.Action.ADDED, nodePool(pool, cluster));

            verify(nodePoolService).getNodePool(NS, null, pool);
            assertNotNull(resourceManager.getResource(poolUri), "pool status should be registered");
            assertTrue(manager.containsState(poolUri));
            verify(nodePoolService).listNodePools(NS, cluster);
            assertNotNull(resourceManager.getResource(topologyUri), "parent topology should be refreshed");
        } finally {
            resourceManager.removeResource(poolUri);
            resourceManager.removeResource(topologyUri);
        }
    }

    @Test
    void testNodePoolWithoutClusterLabelDoesNotRefreshTopology() {
        String pool = "unlabelled-pool";
        String poolUri = StrimziConstants.ResourceUris.nodePoolStatus(NS, pool);

        try {
            nodePoolWatcher.getValue().eventReceived(Watcher.Action.MODIFIED, nodePool(pool, null));

            verify(nodePoolService, never()).listNodePools(anyString(), anyString());
        } finally {
            resourceManager.removeResource(poolUri);
        }
    }

    @Test
    void testNodePoolDeletedStillRefreshesParentTopology() {
        String cluster = "pool-del-cluster";
        String pool = "pool-del";
        when(nodePoolService.listNodePools(NS, cluster)).thenReturn(List.of());
        String topologyUri = StrimziConstants.ResourceUris.kafkaTopology(NS, cluster);

        try {
            nodePoolWatcher.getValue().eventReceived(Watcher.Action.DELETED, nodePool(pool, cluster));

            assertFalse(manager.containsState(StrimziConstants.ResourceUris.nodePoolStatus(NS, pool)));
            verify(nodePoolService).listNodePools(NS, cluster);
        } finally {
            resourceManager.removeResource(topologyUri);
        }
    }

    @Test
    void testWatchClosedWithErrorIsDroppedFromActiveWatches() {
        assertEquals(2, manager.watchCount());

        kafkaWatcher.getValue().onClose(new WatcherException("connection lost"));

        assertEquals(1, manager.watchCount());
        verify(kafkaService, never()).getCluster(any(), any());
    }

    @Test
    void testWatchClosedWithoutErrorKeepsActiveWatches() {
        kafkaWatcher.getValue().onClose();

        assertEquals(2, manager.watchCount());
    }
}
