/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.service.infra;

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.streamshub.mcp.config.Constants;
import io.streamshub.mcp.service.common.KubernetesResourceService;
import io.strimzi.api.ResourceLabels;
import io.strimzi.api.kafka.model.kafka.Kafka;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;

/**
 * Service for discovering Strimzi installations and shared utility methods.
 * Provides comprehensive discovery of namespaces containing any Strimzi resources.
 */
@ApplicationScoped
public class StrimziDiscoveryService {

    private static final Logger LOG = Logger.getLogger(StrimziDiscoveryService.class);

    @Inject
    KubernetesClient kubernetesClient;
    @Inject
    KubernetesResourceService resourceService;

    StrimziDiscoveryService() {
    }

    /**
     * Discover raw Strimzi Kafka clusters across all namespaces or in specific namespace.
     *
     * @param namespace the namespace to search in, or null for all namespaces
     * @return list of discovered raw Kafka custom resources
     */
    public List<Kafka> discoverKafkaClusters(String namespace) {
        try {
            return resourceService.queryResources(Kafka.class, namespace);
        } catch (Exception e) {
            LOG.warnf("Error discovering Kafka clusters: %s", e.getMessage());
            return List.of();
        }
    }


    /**
     * Find Strimzi operator pods in a specific namespace.
     * Simple approach: just look for the standard operator label.
     *
     * @param namespace the namespace to search in
     * @return list of operator pods found
     */
    public List<Pod> findOperatorPods(String namespace) {
        try {
            return kubernetesClient.pods()
                .inNamespace(namespace)
                .withLabel(ResourceLabels.STRIMZI_KIND_LABEL,
                    Constants.Strimzi.ComponentNames.CLUSTER_OPERATOR)
                .list()
                .getItems();
        } catch (Exception e) {
            LOG.debugf("Error finding operator pods in namespace %s: %s", namespace, e.getMessage());
            return List.of();
        }
    }

    /**
     * Determine the Strimzi component type from pod name and labels.
     *
     * @param name   the pod name
     * @param labels the pod labels
     * @return the component type string
     */
    public String determineComponent(String name, java.util.Map<String, String> labels) {
        if (labels.containsKey(ResourceLabels.STRIMZI_KIND_LABEL)) {
            return labels.get(ResourceLabels.STRIMZI_KIND_LABEL).toLowerCase(java.util.Locale.ENGLISH);
        }
        if (name.contains("kafka-operator") || name.contains("strimzi-cluster-operator")) {
            return Constants.Strimzi.ComponentTypes.OPERATOR_LOWERCASE;
        }
        if (name.contains("entity-operator")) {
            return Constants.Strimzi.ComponentNames.ENTITY_OPERATOR;
        }
        if (name.contains(Constants.Strimzi.ComponentTypes.KAFKA_LOWERCASE) && !name.contains(Constants.Strimzi.ComponentTypes.ZOOKEEPER_LOWERCASE)) {
            return Constants.Strimzi.ComponentTypes.KAFKA_LOWERCASE;
        }
        if (name.contains(Constants.Strimzi.ComponentTypes.ZOOKEEPER_LOWERCASE)) {
            return Constants.Strimzi.ComponentTypes.ZOOKEEPER_LOWERCASE;
        }
        return "unknown";
    }
}
