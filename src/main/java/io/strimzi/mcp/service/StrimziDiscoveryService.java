package io.strimzi.mcp.service;

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.strimzi.api.kafka.model.kafka.Kafka;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
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
     * Discover namespaces that contain Strimzi operator.
     * Returns list of namespaces where Strimzi operator is deployed.
     */
    public List<String> discoverStrimziNamespaces() {
        try {
            LOG.debug("Discovering Strimzi operator across all namespaces...");

            List<String> strimziNamespaces = new ArrayList<>();

            // Method 1: Look for operator deployments
            List<Deployment> allDeployments = kubernetesClient.apps().deployments()
                .inAnyNamespace()
                .list()
                .getItems();

            Set<String> namespacesWithOperator = allDeployments.stream()
                .filter(deployment -> {
                    String name = deployment.getMetadata().getName();
                    return name.contains("strimzi") && name.contains("operator");
                })
                .map(deployment -> deployment.getMetadata().getNamespace())
                .collect(Collectors.toSet());

            strimziNamespaces.addAll(namespacesWithOperator);

            // Method 2: Look for operator pods if deployments not found
            if (strimziNamespaces.isEmpty()) {
                List<Pod> allPods = kubernetesClient.pods()
                    .inAnyNamespace()
                    .withLabel("name", "strimzi-cluster-operator")
                    .list()
                    .getItems();

                Set<String> namespacesWithPods = allPods.stream()
                    .map(pod -> pod.getMetadata().getNamespace())
                    .collect(Collectors.toSet());

                strimziNamespaces.addAll(namespacesWithPods);
            }

            // Method 3: Look for Kafka custom resources
            if (strimziNamespaces.isEmpty()) {
                try {
                    List<Kafka> kafkaResources = kubernetesClient.resources(Kafka.class)
                        .inAnyNamespace()
                        .list()
                        .getItems();

                    Set<String> namespacesWithKafka = kafkaResources.stream()
                        .map(kafka -> kafka.getMetadata().getNamespace())
                        .collect(Collectors.toSet());

                    strimziNamespaces.addAll(namespacesWithKafka);
                } catch (Exception e) {
                    LOG.debugf("Could not query Kafka CRDs (may not be installed): %s", e.getMessage());
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
        List<KafkaClusterInfo> clusters = new ArrayList<>();

        try {
            List<Kafka> kafkaResources;

            if (namespace != null) {
                kafkaResources = kubernetesClient.resources(Kafka.class)
                    .inNamespace(namespace)
                    .list()
                    .getItems();
            } else {
                kafkaResources = kubernetesClient.resources(Kafka.class)
                    .inAnyNamespace()
                    .list()
                    .getItems();
            }

            for (Kafka kafka : kafkaResources) {
                KafkaClusterInfo info = new KafkaClusterInfo(
                    kafka.getMetadata().getName(),
                    kafka.getMetadata().getNamespace(),
                    kafka.getStatus() != null ? kafka.getStatus().getConditions() : List.of()
                );
                clusters.add(info);
            }

        } catch (Exception e) {
            LOG.warnf("Error discovering Kafka clusters: %s", e.getMessage());
        }

        return clusters;
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