/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.service;

import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.client.KubernetesClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;

/**
 * Common service for Kubernetes resource operations.
 */
@ApplicationScoped
public class KubernetesResourceService {

    private static final Logger LOG = Logger.getLogger(KubernetesResourceService.class);

    @Inject
    KubernetesClient kubernetesClient;

    KubernetesResourceService() {
    }

    /**
     * Query resources in a namespace.
     *
     * @param <T>           the resource type
     * @param resourceClass the resource class
     * @param namespace     the namespace
     * @return list of resources found
     */
    public <T extends HasMetadata> List<T> queryResources(final Class<T> resourceClass, final String namespace) {
        try {
            return kubernetesClient
                .resources(resourceClass)
                .inNamespace(namespace)
                .list()
                .getItems();
        } catch (Exception e) {
            LOG.warnf(
                "Error querying %s in ns %s: %s",
                resourceClass.getSimpleName(),
                namespace, e.getMessage());
            throw new KubernetesQueryException(
                String.format("Failed to query %s in namespace '%s'",
                    resourceClass.getSimpleName(), namespace), e);
        }
    }

    /**
     * Query resources across all namespaces.
     *
     * @param <T>           the resource type
     * @param resourceClass the resource class
     * @return list of resources found
     */
    public <T extends HasMetadata> List<T> queryResourcesInAnyNamespace(final Class<T> resourceClass) {
        try {
            return kubernetesClient
                .resources(resourceClass)
                .inAnyNamespace()
                .list()
                .getItems();
        } catch (Exception e) {
            LOG.warnf(
                "Error querying %s in any ns: %s",
                resourceClass.getSimpleName(),
                e.getMessage());
            throw new KubernetesQueryException(
                String.format("Failed to query %s across all namespaces",
                    resourceClass.getSimpleName()), e);
        }
    }

    /**
     * Query resources by label in a namespace.
     *
     * @param <T>           the resource type
     * @param resourceClass the resource class
     * @param namespace     the namespace
     * @param labelKey      the label key
     * @param labelValue    the label value
     * @return list of resources found
     */
    public <T extends HasMetadata> List<T> queryResourcesByLabel(final Class<T> resourceClass, final String namespace,
                                                                 final String labelKey, final String labelValue) {
        try {
            return kubernetesClient
                .resources(resourceClass)
                .inNamespace(namespace)
                .withLabel(labelKey, labelValue)
                .list()
                .getItems();
        } catch (Exception e) {
            LOG.warnf(
                "Error querying %s by %s=%s"
                    + " in ns %s: %s",
                resourceClass.getSimpleName(),
                labelKey, labelValue,
                namespace, e.getMessage());
            throw new KubernetesQueryException(
                String.format("Failed to query %s by %s=%s in namespace '%s'",
                    resourceClass.getSimpleName(), labelKey, labelValue, namespace), e);
        }
    }

    /**
     * Query resources by label in all namespaces.
     *
     * @param <T>           the resource type
     * @param resourceClass the resource class
     * @param labelKey      the label key
     * @param labelValue    the label value
     * @return list of resources found
     */
    public <T extends HasMetadata> List<T> queryResourcesByLabelInAnyNamespace(final Class<T> resourceClass,
                                                                               final String labelKey,
                                                                               final String labelValue) {
        try {
            return kubernetesClient
                .resources(resourceClass)
                .inAnyNamespace()
                .withLabel(labelKey, labelValue)
                .list()
                .getItems();
        } catch (Exception e) {
            LOG.warnf(
                "Error querying %s by %s=%s: %s",
                resourceClass.getSimpleName(),
                labelKey, labelValue,
                e.getMessage());
            throw new KubernetesQueryException(
                String.format("Failed to query %s by %s=%s across all namespaces",
                    resourceClass.getSimpleName(), labelKey, labelValue), e);
        }
    }

    /**
     * Query resources matching multiple labels in a namespace.
     *
     * @param <T>           the resource type
     * @param resourceClass the resource class
     * @param namespace     the namespace
     * @param labels        the label key-value pairs to match
     * @return list of resources found
     */
    public <T extends HasMetadata> List<T> queryResourcesByLabels(final Class<T> resourceClass,
                                                                   final String namespace,
                                                                   final Map<String, String> labels) {
        try {
            return kubernetesClient
                .resources(resourceClass)
                .inNamespace(namespace)
                .withLabels(labels)
                .list()
                .getItems();
        } catch (Exception e) {
            LOG.warnf(
                "Error querying %s by labels %s in ns %s: %s",
                resourceClass.getSimpleName(),
                labels, namespace, e.getMessage());
            throw new KubernetesQueryException(
                String.format("Failed to query %s by labels %s in namespace '%s'",
                    resourceClass.getSimpleName(), labels, namespace), e);
        }
    }

    /**
     * Query resources matching multiple labels in all namespaces.
     *
     * @param <T>           the resource type
     * @param resourceClass the resource class
     * @param labels        the label key-value pairs to match
     * @return list of resources found
     */
    public <T extends HasMetadata> List<T> queryResourcesByLabelsInAnyNamespace(final Class<T> resourceClass,
                                                                                final Map<String, String> labels) {
        try {
            return kubernetesClient
                .resources(resourceClass)
                .inAnyNamespace()
                .withLabels(labels)
                .list()
                .getItems();
        } catch (Exception e) {
            LOG.warnf(
                "Error querying %s by labels %s: %s",
                resourceClass.getSimpleName(),
                labels, e.getMessage());
            throw new KubernetesQueryException(
                String.format("Failed to query %s by labels %s across all namespaces",
                    resourceClass.getSimpleName(), labels), e);
        }
    }

    /**
     * Query cluster-scoped resources by label.
     *
     * @param <T>           the resource type
     * @param resourceClass the resource class
     * @param labelKey      the label key
     * @param labelValue    the label value
     * @return list of resources found
     */
    public <T extends HasMetadata> List<T> queryClusterScopedResourcesByLabel(final Class<T> resourceClass,
                                                                              final String labelKey,
                                                                              final String labelValue) {
        try {
            return kubernetesClient
                .resources(resourceClass)
                .withLabel(labelKey, labelValue)
                .list()
                .getItems();
        } catch (Exception e) {
            LOG.warnf(
                "Error querying cluster-scoped %s by %s=%s: %s",
                resourceClass.getSimpleName(),
                labelKey, labelValue,
                e.getMessage());
            throw new KubernetesQueryException(
                String.format("Failed to query cluster-scoped %s by %s=%s",
                    resourceClass.getSimpleName(), labelKey, labelValue), e);
        }
    }

    /**
     * Get a cluster-scoped resource by name (no namespace).
     *
     * @param <T>           the resource type
     * @param resourceClass the resource class
     * @param name          the resource name
     * @return the resource, or null if not found
     */
    public <T extends HasMetadata> T getClusterScopedResource(final Class<T> resourceClass, final String name) {
        try {
            return kubernetesClient
                .resources(resourceClass)
                .withName(name)
                .get();
        } catch (Exception e) {
            LOG.warnf(
                "Error getting cluster-scoped %s %s: %s",
                resourceClass.getSimpleName(),
                name, e.getMessage());
            throw new KubernetesQueryException(
                String.format("Failed to get cluster-scoped %s '%s'",
                    resourceClass.getSimpleName(), name), e);
        }
    }

    /**
     * Get a resource by name in a namespace.
     *
     * @param <T>           the resource type
     * @param resourceClass the resource class
     * @param namespace     the namespace
     * @param name          the resource name
     * @return the resource, or null if not found
     */
    public <T extends HasMetadata> T getResource(final Class<T> resourceClass, final String namespace, final String name) {
        try {
            return kubernetesClient
                .resources(resourceClass)
                .inNamespace(namespace)
                .withName(name)
                .get();
        } catch (Exception e) {
            LOG.warnf(
                "Error getting %s %s in ns %s: %s",
                resourceClass.getSimpleName(),
                name, namespace, e.getMessage());
            throw new KubernetesQueryException(
                String.format("Failed to get %s '%s' in namespace '%s'",
                    resourceClass.getSimpleName(), name, namespace), e);
        }
    }
}
