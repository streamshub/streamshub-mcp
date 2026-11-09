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
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
/**
 * Helper for setting up mock Kubernetes client chains
 * that return empty results for {@link io.streamshub.mcp.common.service.KubernetesResourceService}.
 */
public final class KubernetesMockHelper {

    private KubernetesMockHelper() {
    }

    /**
     * Set up mock Kubernetes client chain that returns empty results for a resource type.
     *
     * @param <T>              the resource type
     * @param kubernetesClient the mocked Kubernetes client
     * @param resourceClass    the resource class to mock
     */
    @SuppressWarnings("unchecked")
    public static <T extends HasMetadata> void setupEmptyResourceQuery(
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

    /**
     * Set up mock Kubernetes client chain that returns the given items for a resource type.
     * List and by-name lookups resolve against the item list ({@code getResource(type, ns, name)}
     * returns the item whose metadata name matches), and labelled lookups return only the items
     * carrying all of the requested label key/value pairs.
     *
     * @param <T>              the resource type
     * @param kubernetesClient the mocked Kubernetes client
     * @param resourceClass    the resource class to mock
     * @param items            the items the mocked chain should return
     */
    @SuppressWarnings("unchecked")
    public static <T extends HasMetadata> void setupResourceQuery(
            KubernetesClient kubernetesClient, Class<T> resourceClass, List<T> items) {
        MixedOperation resourceOp = Mockito.mock(MixedOperation.class);
        Mockito.lenient().when(kubernetesClient.resources(resourceClass)).thenReturn(resourceOp);

        NonNamespaceOperation nsOp = Mockito.mock(NonNamespaceOperation.class);
        Mockito.lenient().when(resourceOp.inNamespace(anyString())).thenReturn(nsOp);
        Mockito.lenient().when(resourceOp.inAnyNamespace()).thenReturn(nsOp);

        Mockito.lenient().when(nsOp.withLabel(anyString(), anyString()))
            .thenAnswer(invocation -> labeledOp(items, invocation.getArgument(0), invocation.getArgument(1)));
        Mockito.lenient().when(nsOp.withLabels(any())).thenAnswer(invocation -> {
            Map<String, String> labels = invocation.getArgument(0);
            return labeledOp(items, labels);
        });
        Mockito.lenient().when(resourceOp.withLabel(anyString(), anyString()))
            .thenAnswer(invocation -> labeledOp(items, invocation.getArgument(0), invocation.getArgument(1)));

        KubernetesResourceList itemList = Mockito.mock(KubernetesResourceList.class);
        Mockito.lenient().when(itemList.getItems()).thenReturn(items);
        Mockito.lenient().when(nsOp.list()).thenReturn(itemList);
        Mockito.lenient().when(resourceOp.list()).thenReturn(itemList);

        Mockito.lenient().when(nsOp.withName(anyString()))
            .thenAnswer(invocation -> namedResource(items, invocation.getArgument(0)));
        Mockito.lenient().when(resourceOp.withName(anyString()))
            .thenAnswer(invocation -> namedResource(items, invocation.getArgument(0)));
    }

    private static <T extends HasMetadata> FilterWatchListDeletable<?, ?, ?> labeledOp(
            final List<T> items, final String key, final String value) {
        return labeledOp(items, Map.of(key, value));
    }

    @SuppressWarnings("unchecked")
    private static <T extends HasMetadata> FilterWatchListDeletable<?, ?, ?> labeledOp(
            final List<T> items, final Map<String, String> labels) {
        List<T> filtered = items.stream()
            .filter(i -> i.getMetadata() != null && i.getMetadata().getLabels() != null
                && i.getMetadata().getLabels().entrySet().containsAll(labels.entrySet()))
            .toList();
        FilterWatchListDeletable labeledOp = Mockito.mock(FilterWatchListDeletable.class);
        KubernetesResourceList filteredList = Mockito.mock(KubernetesResourceList.class);
        Mockito.lenient().when(filteredList.getItems()).thenReturn(filtered);
        Mockito.lenient().when(labeledOp.list()).thenReturn(filteredList);
        return labeledOp;
    }

    @SuppressWarnings("unchecked")
    private static <T extends HasMetadata> Resource<T> namedResource(final List<T> items, final String name) {
        Resource<T> resourceMock = Mockito.mock(Resource.class);
        T match = items.stream()
            .filter(i -> i.getMetadata() != null && name.equals(i.getMetadata().getName()))
            .findFirst()
            .orElse(null);
        Mockito.lenient().when(resourceMock.get()).thenReturn(match);
        return resourceMock;
    }
}
