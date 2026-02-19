/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.service.common;

import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.client.KubernetesClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;

/**
 * Common service for generic Kubernetes resource operations.
 * Provides reusable methods for basic resource querying operations.
 */
@ApplicationScoped
public class KubernetesResourceService {

    private static final Logger LOG = Logger.getLogger(KubernetesResourceService.class);
    @Inject
    KubernetesClient kubernetesClient;

    KubernetesResourceService() {
    }

    /**
     * Query resources of a specific type in a namespace or across all namespaces.
     * Generic method that can be used for any Kubernetes resource type.
     *
     * @param <T>           the resource type
     * @param resourceClass the class of the resource to query
     * @param namespace     the namespace to search in, or null for all namespaces
     * @return list of resources found
     */
    public <T extends HasMetadata> List<T> queryResources(Class<T> resourceClass, String namespace) {
        try {
            if (namespace != null) {
                return kubernetesClient.resources(resourceClass)
                    .inNamespace(namespace)
                    .list()
                    .getItems();
            } else {
                return kubernetesClient.resources(resourceClass)
                    .inAnyNamespace()
                    .list()
                    .getItems();
            }
        } catch (Exception e) {
            LOG.debugf("Error querying %s resources in namespace %s: %s",
                resourceClass.getSimpleName(), namespace, e.getMessage());
            return List.of();
        }
    }

    /**
     * Query resources by label in a specific namespace.
     *
     * @param <T>           the resource type
     * @param resourceClass the class of the resource to query
     * @param namespace     the namespace to search in
     * @param labelKey      the label key to match
     * @param labelValue    the label value to match
     * @return list of resources found
     */
    public <T extends HasMetadata> List<T> queryResourcesByLabel(Class<T> resourceClass, String namespace,
                                                                 String labelKey, String labelValue) {
        try {
            return kubernetesClient.resources(resourceClass)
                .inNamespace(namespace)
                .withLabel(labelKey, labelValue)
                .list()
                .getItems();
        } catch (Exception e) {
            LOG.debugf("Error querying %s resources by label %s=%s in namespace %s: %s",
                resourceClass.getSimpleName(), labelKey, labelValue, namespace, e.getMessage());
            return List.of();
        }
    }

    /**
     * Query resources by label across all namespaces.
     *
     * @param <T>           the resource type
     * @param resourceClass the class of the resource to query
     * @param labelKey      the label key to match
     * @param labelValue    the label value to match
     * @return list of resources found
     */
    public <T extends HasMetadata> List<T> queryResourcesByLabelInAnyNamespace(Class<T> resourceClass,
                                                                               String labelKey, String labelValue) {
        try {
            return kubernetesClient.resources(resourceClass)
                .inAnyNamespace()
                .withLabel(labelKey, labelValue)
                .list()
                .getItems();
        } catch (Exception e) {
            LOG.debugf("Error querying %s resources by label %s=%s: %s",
                resourceClass.getSimpleName(), labelKey, labelValue, e.getMessage());
            return List.of();
        }
    }


    /**
     * Get a specific resource by name in a namespace.
     *
     * @param <T>           the resource type
     * @param resourceClass the class of the resource
     * @param namespace     the namespace to search in
     * @param name          the name of the resource
     * @return the resource if found, null otherwise
     */
    public <T extends HasMetadata> T getResource(Class<T> resourceClass, String namespace, String name) {
        try {
            return kubernetesClient.resources(resourceClass)
                .inNamespace(namespace)
                .withName(name)
                .get();
        } catch (Exception e) {
            LOG.debugf("Error getting %s resource %s in namespace %s: %s",
                resourceClass.getSimpleName(), name, namespace, e.getMessage());
            return null;
        }
    }

}