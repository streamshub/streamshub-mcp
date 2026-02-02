package io.strimzi.mcp.service;

import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.strimzi.api.kafka.model.kafka.Kafka;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Service for discovering Strimzi installations and shared utility methods.
 */
@ApplicationScoped
public class StrimziDiscoveryService {

    private static final Logger LOG = Logger.getLogger(StrimziDiscoveryService.class);

    @Inject
    KubernetesClient kubernetesClient;

    /**
     * Normalize namespace to handle various input formats.
     * Returns null if no namespace is provided, allowing tools to prompt for it.
     */
    public String normalizeNamespace(String namespace) {
        if (namespace == null || namespace.isBlank()) {
            return null; // Let the caller handle missing namespace
        }
        return namespace.toLowerCase().trim();
    }

    /**
     * Get default namespace from environment if needed.
     * This is only used as a fallback when no namespace can be determined from user input.
     */
    public String getDefaultNamespace() {
        return System.getenv().getOrDefault("K8S_NAMESPACE", "strimzi-operator");
    }

    /**
     * Normalize cluster name to handle various input formats.
     */
    public String normalizeClusterName(String clusterName) {
        if (clusterName == null || clusterName.isBlank()) {
            return null;
        }
        return clusterName.toLowerCase().trim();
    }

    /**
     * Find deployments by name pattern across all namespaces.
     */
    private List<Deployment> findDeploymentsByNamePattern(Predicate<String> nameFilter) {
        try {
            return kubernetesClient.apps().deployments()
                .inAnyNamespace()
                .list()
                .getItems()
                .stream()
                .filter(deployment -> nameFilter.test(deployment.getMetadata().getName()))
                .collect(Collectors.toList());
        } catch (Exception e) {
            LOG.debugf("Error finding deployments: %s", e.getMessage());
            return List.of();
        }
    }

    /**
     * Find pods by labels across all namespaces.
     */
    private List<Pod> findPodsByLabels(String labelKey, String labelValue) {
        try {
            return kubernetesClient.pods()
                .inAnyNamespace()
                .withLabel(labelKey, labelValue)
                .list()
                .getItems();
        } catch (Exception e) {
            LOG.debugf("Error finding pods: %s", e.getMessage());
            return List.of();
        }
    }

    /**
     * Query resources either in a specific namespace or across all namespaces.
     */
    private <T extends HasMetadata> List<T> queryResources(Class<T> resourceClass, String namespace) {
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
            LOG.debugf("Error querying %s resources: %s", resourceClass.getSimpleName(), e.getMessage());
            return List.of();
        }
    }

    /**
     * Extract unique namespaces from a list of Kubernetes resources.
     */
    private Set<String> extractNamespaces(List<? extends HasMetadata> resources) {
        return resources.stream()
            .map(resource -> resource.getMetadata().getNamespace())
            .collect(Collectors.toSet());
    }

    /**
     * Discover namespaces that contain Strimzi operator.
     * Returns list of namespaces where Strimzi operator is deployed.
     */
    public List<String> discoverStrimziNamespaces() {
        try {
            LOG.debug("Discovering Strimzi operator across all namespaces...");

            // Method 1: Look for operator deployments
            List<Deployment> operatorDeployments = findDeploymentsByNamePattern(name ->
                name.contains("strimzi") && name.contains("operator"));
            Set<String> strimziNamespaces = new HashSet<>(extractNamespaces(operatorDeployments));

            // Method 2: Look for operator pods if deployments not found
            if (strimziNamespaces.isEmpty()) {
                List<Pod> operatorPods = findPodsByLabels("name", "strimzi-cluster-operator");
                strimziNamespaces.addAll(extractNamespaces(operatorPods));
            }

            // Method 3: Look for Kafka custom resources
            if (strimziNamespaces.isEmpty()) {
                List<Kafka> kafkaResources = queryResources(Kafka.class, null);
                if (!kafkaResources.isEmpty()) {
                    strimziNamespaces.addAll(extractNamespaces(kafkaResources));
                }
            }

            LOG.debugf("Discovered Strimzi in namespaces: %s", strimziNamespaces);
            return strimziNamespaces.stream().sorted().collect(Collectors.toList());

        } catch (Exception e) {
            LOG.warnf("Error discovering Strimzi namespaces: %s", e.getMessage());
            return List.of();
        }
    }

    /**
     * Get Strimzi Kafka clusters across all namespaces or in specific namespace.
     */
    public List<KafkaClusterInfo> discoverKafkaClusters(String namespace) {
        try {
            List<Kafka> kafkaResources = queryResources(Kafka.class, namespace);

            return kafkaResources.stream()
                .map(kafka -> new KafkaClusterInfo(
                    kafka.getMetadata().getName(),
                    kafka.getMetadata().getNamespace(),
                    kafka.getStatus() != null ? kafka.getStatus().getConditions() : List.of()
                ))
                .collect(Collectors.toList());

        } catch (Exception e) {
            LOG.warnf("Error discovering Kafka clusters: %s", e.getMessage());
            return List.of();
        }
    }

    /**
     * Simple record to hold Kafka cluster information.
     */
    public record KafkaClusterInfo(
        String name,
        String namespace,
        List<?> conditions
    ) {
        public String getDisplayName() {
            return String.format("%s (namespace: %s)", name, namespace);
        }
    }
}