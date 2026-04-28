/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.service;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodList;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.NonNamespaceOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link KubernetesResourceService} error propagation.
 */
@QuarkusTest
class KubernetesResourceServiceTest {

    @InjectMock
    KubernetesClient kubernetesClient;

    @Inject
    KubernetesResourceService k8sService;

    KubernetesResourceServiceTest() {
    }

    @Test
    void testQueryResourcesThrowsOnError() {
        when(kubernetesClient.resources(Pod.class))
            .thenThrow(new KubernetesClientException("Forbidden"));

        KubernetesQueryException ex = assertThrows(KubernetesQueryException.class,
            () -> k8sService.queryResources(Pod.class, "default"));

        assertTrue(ex.getMessage().contains("Pod"));
        assertTrue(ex.getMessage().contains("default"));
        assertNotNull(ex.getCause());
    }

    @Test
    void testQueryResourcesInAnyNamespaceThrowsOnError() {
        when(kubernetesClient.resources(Pod.class))
            .thenThrow(new KubernetesClientException("Forbidden"));

        KubernetesQueryException ex = assertThrows(KubernetesQueryException.class,
            () -> k8sService.queryResourcesInAnyNamespace(Pod.class));

        assertTrue(ex.getMessage().contains("Pod"));
        assertTrue(ex.getMessage().contains("all namespaces"));
    }

    @Test
    void testGetResourceThrowsOnError() {
        when(kubernetesClient.resources(ConfigMap.class))
            .thenThrow(new KubernetesClientException("Connection refused"));

        KubernetesQueryException ex = assertThrows(KubernetesQueryException.class,
            () -> k8sService.getResource(ConfigMap.class, "default", "my-config"));

        assertTrue(ex.getMessage().contains("ConfigMap"));
        assertTrue(ex.getMessage().contains("my-config"));
    }

    @Test
    void testQueryResourcesByLabelThrowsOnError() {
        when(kubernetesClient.resources(Pod.class))
            .thenThrow(new KubernetesClientException("Forbidden"));

        KubernetesQueryException ex = assertThrows(KubernetesQueryException.class,
            () -> k8sService.queryResourcesByLabel(Pod.class, "kafka", "app", "strimzi"));

        assertTrue(ex.getMessage().contains("Pod"));
        assertTrue(ex.getMessage().contains("app=strimzi"));
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void testQueryResourcesReturnsResultsOnSuccess() {
        MixedOperation podOp = Mockito.mock(MixedOperation.class);
        Mockito.doReturn(podOp).when(kubernetesClient).resources(Pod.class);

        NonNamespaceOperation nsOp = Mockito.mock(NonNamespaceOperation.class);
        when(podOp.inNamespace("default")).thenReturn(nsOp);

        PodList podList = new PodList();
        podList.setItems(List.of(new Pod()));
        when(nsOp.list()).thenReturn(podList);

        List<Pod> result = k8sService.queryResources(Pod.class, "default");

        assertNotNull(result);
        assertEquals(1, result.size());
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void testGetResourceReturnsNullWhenNotFound() {
        MixedOperation podOp = Mockito.mock(MixedOperation.class);
        Mockito.doReturn(podOp).when(kubernetesClient).resources(ConfigMap.class);

        NonNamespaceOperation nsOp = Mockito.mock(NonNamespaceOperation.class);
        when(podOp.inNamespace("default")).thenReturn(nsOp);

        Resource resource = Mockito.mock(Resource.class);
        when(nsOp.withName("missing")).thenReturn(resource);
        when(resource.get()).thenReturn(null);

        ConfigMap result = k8sService.getResource(ConfigMap.class, "default", "missing");

        assertNull(result);
    }

    @Test
    void testQueryResourcesByLabelsThrowsOnError() {
        when(kubernetesClient.resources(Pod.class))
            .thenThrow(new KubernetesClientException("Timeout"));

        assertThrows(KubernetesQueryException.class,
            () -> k8sService.queryResourcesByLabels(Pod.class, "ns", Map.of("a", "b")));
    }

    @Test
    void testQueryClusterScopedResourcesByLabelThrowsOnError() {
        when(kubernetesClient.resources(Pod.class))
            .thenThrow(new KubernetesClientException("Forbidden"));

        assertThrows(KubernetesQueryException.class,
            () -> k8sService.queryClusterScopedResourcesByLabel(Pod.class, "app", "test"));
    }

    @Test
    void testGetClusterScopedResourceThrowsOnError() {
        when(kubernetesClient.resources(ConfigMap.class))
            .thenThrow(new KubernetesClientException("Forbidden"));

        assertThrows(KubernetesQueryException.class,
            () -> k8sService.getClusterScopedResource(ConfigMap.class, "test"));
    }
}
