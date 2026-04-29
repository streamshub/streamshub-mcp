/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.service;

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodList;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentList;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.AppsAPIGroupDSL;
import io.fabric8.kubernetes.client.dsl.FilterWatchListDeletable;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.NonNamespaceOperation;
import io.fabric8.kubernetes.client.dsl.PodResource;
import io.fabric8.kubernetes.client.dsl.RollableScalableResource;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.streamshub.mcp.common.dto.LogCollectionParams;
import io.streamshub.mcp.strimzi.dto.DrainCleanerLogsResponse;
import io.streamshub.mcp.strimzi.dto.DrainCleanerReadinessResponse;
import io.streamshub.mcp.strimzi.dto.DrainCleanerResponse;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Service-level tests for {@link DrainCleanerService} with mocked Kubernetes.
 */
@QuarkusTest
class DrainCleanerServiceTest {

    @InjectMock
    KubernetesClient kubernetesClient;

    @Inject
    DrainCleanerService drainCleanerService;

    DrainCleanerServiceTest() {
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        MixedOperation<Pod, PodList, PodResource> podOp = Mockito.mock(MixedOperation.class);
        Mockito.lenient().when(kubernetesClient.pods()).thenReturn(podOp);

        MixedOperation<Deployment, DeploymentList, RollableScalableResource<Deployment>> deploymentOp =
            Mockito.mock(MixedOperation.class);
        AppsAPIGroupDSL appsApi = Mockito.mock(AppsAPIGroupDSL.class);
        Mockito.lenient().when(kubernetesClient.apps()).thenReturn(appsApi);
        Mockito.lenient().when(appsApi.deployments()).thenReturn(deploymentOp);

        KubernetesMockHelper.setupEmptyResourceQuery(kubernetesClient, Deployment.class);
        KubernetesMockHelper.setupEmptyResourceQuery(kubernetesClient, Pod.class);
    }

    @Test
    void testListDrainCleanersReturnsEmptyWhenNoneExist() {
        List<DrainCleanerResponse> result = drainCleanerService.listDrainCleaners("strimzi-drain-cleaner");

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void testListDrainCleanersReturnsEmptyForAllNamespaces() {
        List<DrainCleanerResponse> result = drainCleanerService.listDrainCleaners(null);

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void testGetDrainCleanerLogsReturnsNotFoundWhenNoPodsExist() {
        setupEmptyPodResponses("strimzi-drain-cleaner");

        DrainCleanerLogsResponse result = drainCleanerService.getDrainCleanerLogs(
            "strimzi-drain-cleaner", null, LogCollectionParams.of(null, null, 200, null));

        assertNotNull(result);
        assertEquals("strimzi-drain-cleaner", result.namespace());
        assertNotNull(result.message());
        assertTrue(result.message().contains("No Strimzi Drain Cleaner pods found"));
    }

    @Test
    void testCheckReadinessWhenNotDeployed() {
        DrainCleanerReadinessResponse result = drainCleanerService.checkReadiness(
            "strimzi-drain-cleaner");

        assertNotNull(result);
        assertFalse(result.deployed());
        assertFalse(result.overallReady());
        assertNotNull(result.checks());
        assertFalse(result.checks().isEmpty());
        assertFalse(result.checks().getFirst().passed());
    }

    @SuppressWarnings("unchecked")
    private void setupEmptyPodResponses(final String namespace) {
        PodList emptyPodList = new PodList();
        emptyPodList.setItems(List.of());

        MixedOperation<Pod, PodList, PodResource> podOp = kubernetesClient.pods();
        NonNamespaceOperation<Pod, PodList, PodResource> namespacedPodOp =
            Mockito.mock(NonNamespaceOperation.class);
        FilterWatchListDeletable<Pod, PodList, PodResource> labeledPodOp =
            Mockito.mock(FilterWatchListDeletable.class);

        when(podOp.inNamespace(namespace)).thenReturn(namespacedPodOp);
        when(namespacedPodOp.withLabel(anyString(), anyString())).thenReturn(labeledPodOp);
        when(labeledPodOp.list()).thenReturn(emptyPodList);

        DeploymentList emptyDeploymentList = new DeploymentList();
        emptyDeploymentList.setItems(List.of());

        MixedOperation<Deployment, DeploymentList, RollableScalableResource<Deployment>> deploymentOp =
            kubernetesClient.apps().deployments();
        NonNamespaceOperation<Deployment, DeploymentList, RollableScalableResource<Deployment>> namespacedDeploymentOp =
            Mockito.mock(NonNamespaceOperation.class);
        FilterWatchListDeletable<Deployment, DeploymentList, RollableScalableResource<Deployment>> labeledDeploymentOp =
            Mockito.mock(FilterWatchListDeletable.class);

        when(deploymentOp.inNamespace(namespace)).thenReturn(namespacedDeploymentOp);
        when(namespacedDeploymentOp.withLabel(anyString(), anyString())).thenReturn(labeledDeploymentOp);
        when(labeledDeploymentOp.list()).thenReturn(emptyDeploymentList);
    }
}