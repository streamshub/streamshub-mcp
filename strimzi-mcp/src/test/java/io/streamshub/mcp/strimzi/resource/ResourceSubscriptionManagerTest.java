/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.resource;

import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.Watch;
import io.fabric8.kubernetes.client.dsl.AppsAPIGroupDSL;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.NonNamespaceOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.fabric8.kubernetes.client.dsl.RollableScalableResource;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.strimzi.api.kafka.model.kafka.Kafka;
import io.strimzi.api.kafka.model.topic.KafkaTopic;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link ResourceSubscriptionManager} watch resilience.
 */
@QuarkusTest
class ResourceSubscriptionManagerTest {

    @InjectMock
    KubernetesClient kubernetesClient;

    @Inject
    ResourceSubscriptionManager manager;

    ResourceSubscriptionManagerTest() {
    }

    @BeforeEach
    void setUp() {
        manager.setShuttingDown(false);
        manager.setWatchesEnabled(true);
        manager.clearWatches();
        manager.clearState();
    }

    @AfterEach
    void tearDown() {
        manager.setShuttingDown(false);
        manager.setWatchesEnabled(false);
    }

    // ---- 2a: Safe watch closure ----

    @Test
    void testCloseHandlesExceptionFromOneWatch() {
        Watch watch1 = mock(Watch.class);
        Watch watch2 = mock(Watch.class);
        Watch watch3 = mock(Watch.class);
        Mockito.doThrow(new RuntimeException("close failed")).when(watch2).close();

        manager.addWatch(watch1);
        manager.addWatch(watch2);
        manager.addWatch(watch3);

        manager.close();

        verify(watch1).close();
        verify(watch2).close();
        verify(watch3).close();
        assertEquals(0, manager.watchCount());
    }

    @Test
    void testCloseHandlesAllWatchesThrowingExceptions() {
        Watch watch1 = mock(Watch.class);
        Watch watch2 = mock(Watch.class);
        Mockito.doThrow(new RuntimeException("fail1")).when(watch1).close();
        Mockito.doThrow(new RuntimeException("fail2")).when(watch2).close();

        manager.addWatch(watch1);
        manager.addWatch(watch2);

        manager.close();

        verify(watch1).close();
        verify(watch2).close();
        assertEquals(0, manager.watchCount());
    }

    @Test
    void testCloseSetsShuttingDown() {
        manager.close();
        assertTrue(manager.isShuttingDown());
    }

    @Test
    void testCloseClearsLastKnownState() {
        manager.putState("strimzi://test/uri", "{}");
        manager.close();
        assertFalse(manager.containsState("strimzi://test/uri"));
    }

    // ---- 2b: Watch reconnection ----

    @Test
    void testNoReconnectDuringShutdown() {
        manager.setShuttingDown(true);
        AtomicInteger callCount = new AtomicInteger();

        manager.scheduleReconnect("Test", () -> {
            callCount.incrementAndGet();
            return true;
        }, 1);

        assertEquals(0, callCount.get());
    }

    @Test
    void testReconnectGivesUpAfterMaxAttempts() {
        AtomicInteger callCount = new AtomicInteger();

        manager.scheduleReconnect("Test", () -> {
            callCount.incrementAndGet();
            return false;
        }, ResourceSubscriptionManager.RECONNECT_MAX_ATTEMPTS + 1);

        assertEquals(0, callCount.get());
    }

    @Test
    void testReconnectBackoffDelayCalculation() {
        assertEquals(1000L,
            Math.min(ResourceSubscriptionManager.RECONNECT_INITIAL_DELAY_MS << 0,
                ResourceSubscriptionManager.RECONNECT_MAX_DELAY_MS));
        assertEquals(2000L,
            Math.min(ResourceSubscriptionManager.RECONNECT_INITIAL_DELAY_MS << 1,
                ResourceSubscriptionManager.RECONNECT_MAX_DELAY_MS));
        assertEquals(4000L,
            Math.min(ResourceSubscriptionManager.RECONNECT_INITIAL_DELAY_MS << 2,
                ResourceSubscriptionManager.RECONNECT_MAX_DELAY_MS));
        assertEquals(60_000L,
            Math.min(ResourceSubscriptionManager.RECONNECT_INITIAL_DELAY_MS << 6,
                ResourceSubscriptionManager.RECONNECT_MAX_DELAY_MS));
    }

    @Test
    void testSuccessfulReconnectDoesNotRetry() throws InterruptedException {
        AtomicInteger callCount = new AtomicInteger();

        manager.scheduleReconnect("Test", () -> {
            callCount.incrementAndGet();
            return true;
        }, 1);

        Thread.sleep(2000);
        assertEquals(1, callCount.get());
    }

    @Test
    void testFailedReconnectSchedulesRetry() throws InterruptedException {
        AtomicInteger callCount = new AtomicInteger();

        manager.scheduleReconnect("Test", () -> {
            callCount.incrementAndGet();
            return false;
        }, 1);

        Thread.sleep(4000);
        assertTrue(callCount.get() >= 2, "Expected at least 2 attempts, got " + callCount.get());
    }

    // ---- 2d: Reconciliation ----

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void testReconcileRemovesOrphanedKafkaEntry() {
        String uri = "strimzi://kafka.strimzi.io/namespaces/kafka/kafkas/my-cluster/status";
        manager.putState(uri, "{}");

        MixedOperation kafkaOp = mock(MixedOperation.class);
        Mockito.doReturn(kafkaOp).when(kubernetesClient).resources(Kafka.class);
        NonNamespaceOperation nsOp = mock(NonNamespaceOperation.class);
        when(kafkaOp.inNamespace("kafka")).thenReturn(nsOp);
        Resource resource = mock(Resource.class);
        when(nsOp.withName("my-cluster")).thenReturn(resource);
        when(resource.get()).thenReturn(null);

        manager.reconcileState();

        assertFalse(manager.containsState(uri));
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void testReconcileKeepsExistingResource() {
        String uri = "strimzi://kafka.strimzi.io/namespaces/kafka/kafkas/my-cluster/status";
        manager.putState(uri, "{}");

        MixedOperation kafkaOp = mock(MixedOperation.class);
        Mockito.doReturn(kafkaOp).when(kubernetesClient).resources(Kafka.class);
        NonNamespaceOperation nsOp = mock(NonNamespaceOperation.class);
        when(kafkaOp.inNamespace("kafka")).thenReturn(nsOp);
        Resource resource = mock(Resource.class);
        when(nsOp.withName("my-cluster")).thenReturn(resource);
        when(resource.get()).thenReturn(new Kafka());

        manager.reconcileState();

        assertTrue(manager.containsState(uri));
    }

    @Test
    void testReconcileSkipsWhenShuttingDown() {
        manager.setShuttingDown(true);
        String uri = "strimzi://kafka.strimzi.io/namespaces/kafka/kafkas/my-cluster/status";
        manager.putState(uri, "{}");

        manager.reconcileState();

        assertTrue(manager.containsState(uri));
    }

    @Test
    void testReconcileHandlesUnparsableUri() {
        manager.putState("invalid-uri", "{}");

        manager.reconcileState();

        assertTrue(manager.containsState("invalid-uri"));
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void testReconcileHandlesKubernetesApiFailure() {
        String uri = "strimzi://kafka.strimzi.io/namespaces/kafka/kafkas/my-cluster/status";
        manager.putState(uri, "{}");

        when(kubernetesClient.resources(Kafka.class))
            .thenThrow(new RuntimeException("API unavailable"));

        manager.reconcileState();

        assertTrue(manager.containsState(uri));
    }

    @Test
    void testResourceExistsReturnsTrueForUnknownKind() {
        assertTrue(manager.resourceExistsInCluster(
            "strimzi://test/namespaces/ns/unknownkind/name/status"));
    }

    @Test
    void testResourceExistsReturnsTrueForShortUri() {
        assertTrue(manager.resourceExistsInCluster("strimzi://too/short"));
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void testReconcileRemovesOrphanedTopicEntry() {
        String uri = "strimzi://kafka.strimzi.io/namespaces/prod/kafkatopics/my-topic/status";
        manager.putState(uri, "{}");

        MixedOperation topicOp = mock(MixedOperation.class);
        Mockito.doReturn(topicOp).when(kubernetesClient).resources(KafkaTopic.class);
        NonNamespaceOperation nsOp = mock(NonNamespaceOperation.class);
        when(topicOp.inNamespace("prod")).thenReturn(nsOp);
        Resource resource = mock(Resource.class);
        when(nsOp.withName("my-topic")).thenReturn(resource);
        when(resource.get()).thenReturn(null);

        manager.reconcileState();

        assertFalse(manager.containsState(uri));
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void testReconcileChecksOperatorDeployment() {
        String uri = "strimzi://operator.strimzi.io/namespaces/kafka/clusteroperator/strimzi-co/status";
        manager.putState(uri, "{}");

        AppsAPIGroupDSL appsApi = mock(AppsAPIGroupDSL.class);
        when(kubernetesClient.apps()).thenReturn(appsApi);
        MixedOperation deploymentOp = mock(MixedOperation.class);
        when(appsApi.deployments()).thenReturn(deploymentOp);
        NonNamespaceOperation nsOp = mock(NonNamespaceOperation.class);
        when(deploymentOp.inNamespace("kafka")).thenReturn(nsOp);
        RollableScalableResource resource = mock(RollableScalableResource.class);
        when(nsOp.withName("strimzi-co")).thenReturn(resource);
        when(resource.get()).thenReturn(new Deployment());

        manager.reconcileState();

        assertTrue(manager.containsState(uri));
    }
}
