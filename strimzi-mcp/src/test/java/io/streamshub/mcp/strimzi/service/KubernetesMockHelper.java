/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.service;

import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.KubernetesResourceList;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.FilterWatchListDeletable;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.NonNamespaceOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import org.mockito.Mockito;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;

/**
 * Helper for setting up mock Kubernetes client chains
 * that return empty results for {@link io.streamshub.mcp.common.service.KubernetesResourceService}.
 */
final class KubernetesMockHelper {

    private KubernetesMockHelper() {
    }

    @SuppressWarnings("unchecked")
    static <T extends HasMetadata> void setupEmptyResourceQuery(
            KubernetesClient kubernetesClient, Class<T> resourceClass) {
        MixedOperation resourceOp = Mockito.mock(MixedOperation.class);
        Mockito.lenient().when(kubernetesClient.resources(resourceClass)).thenReturn(resourceOp);

        NonNamespaceOperation nsOp = Mockito.mock(NonNamespaceOperation.class);
        Mockito.lenient().when(resourceOp.inNamespace(anyString())).thenReturn(nsOp);
        Mockito.lenient().when(resourceOp.inAnyNamespace()).thenReturn(nsOp);

        FilterWatchListDeletable labeledOp = Mockito.mock(FilterWatchListDeletable.class);
        Mockito.lenient().when(nsOp.withLabel(anyString(), anyString())).thenReturn(labeledOp);
        Mockito.lenient().when(nsOp.withLabels(any())).thenReturn(labeledOp);

        KubernetesResourceList emptyList = Mockito.mock(KubernetesResourceList.class);
        Mockito.lenient().when(emptyList.getItems()).thenReturn(List.of());
        Mockito.lenient().when(nsOp.list()).thenReturn(emptyList);
        Mockito.lenient().when(labeledOp.list()).thenReturn(emptyList);
        Mockito.lenient().when(resourceOp.list()).thenReturn(emptyList);

        // For withLabel on the top-level op (cluster-scoped queries)
        Mockito.lenient().when(resourceOp.withLabel(anyString(), anyString())).thenReturn(labeledOp);

        // For getResource() / getClusterScopedResource() calls
        Resource resourceMock = Mockito.mock(Resource.class);
        Mockito.lenient().when(nsOp.withName(anyString())).thenReturn(resourceMock);
        Mockito.lenient().when(resourceOp.withName(anyString())).thenReturn(resourceMock);
        Mockito.lenient().when(resourceMock.get()).thenReturn(null);
    }
}
