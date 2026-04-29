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
import io.streamshub.mcp.strimzi.dto.StrimziOperatorLogsResponse;
import io.streamshub.mcp.strimzi.dto.StrimziOperatorResponse;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Service-level tests for {@link StrimziOperatorService} with mocked Kubernetes.
 */
@QuarkusTest
class StrimziOperatorServiceTest {

    @InjectMock
    KubernetesClient kubernetesClient;

    @Inject
    StrimziOperatorService operatorService;

    StrimziOperatorServiceTest() {
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
    void testGetOperatorLogsReturnsNotFoundWhenNoPodsExist() {
        setupEmptyPodResponses("kafka-system");

        StrimziOperatorLogsResponse result = operatorService.getOperatorLogs(
            "kafka-system", null, LogCollectionParams.of(null, null, 200, null));

        assertNotNull(result);
        assertEquals("kafka-system", result.namespace());
        assertNotNull(result.message());
        assertTrue(result.message().contains("No Strimzi operator pods found"));
    }

    @Test
    void testGetOperatorLogsNormalizesNamespaceInput() {
        setupEmptyPodResponses("kafka");

        StrimziOperatorLogsResponse result1 = operatorService.getOperatorLogs(
            "  KAFKA  ", null, LogCollectionParams.of(null, null, 200, null));
        StrimziOperatorLogsResponse result2 = operatorService.getOperatorLogs(
            "kafka", null, LogCollectionParams.of(null, null, 200, null));

        assertEquals("kafka", result1.namespace());
        assertEquals("kafka", result2.namespace());
    }

    @Test
    void testListOperatorsReturnsEmptyWhenNoneExist() {
        List<StrimziOperatorResponse> result = operatorService.listOperators("kafka-system");

        assertNotNull(result);
        assertTrue(result.isEmpty());
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
