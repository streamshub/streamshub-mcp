/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.mcp.service.infra;

import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.strimzi.api.kafka.model.kafka.Kafka;
import io.strimzi.api.kafka.model.nodepool.KafkaNodePool;
import io.strimzi.mcp.dto.KafkaClusterInfo;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.AbstractMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Service for discovering Strimzi installations and shared utility methods.
 */
@ApplicationScoped
public class StrimziDiscoveryService {

    /**
     * Creates a new StrimziDiscoveryService instance.
     */
    public StrimziDiscoveryService() {
    }

    private static final Logger LOG = Logger.getLogger(StrimziDiscoveryService.class);

    /**
     * Ordered label strategies for finding operator Deployments (matched against deployment metadata.labels).
     * Stock Strimzi YAML only has "app: strimzi" on the Deployment metadata; Helm adds app.kubernetes.io/name.
     */
    static final List<Map.Entry<String, String>> OPERATOR_DEPLOYMENT_LABELS = List.of(
        new AbstractMap.SimpleImmutableEntry<>("app.kubernetes.io/name", "strimzi-cluster-operator"),
        new AbstractMap.SimpleImmutableEntry<>("app", "strimzi")
    );

    /**
     * Ordered label strategies for finding operator Pods (matched against pod metadata.labels).
     * Pod template labels differ from deployment metadata labels in stock Strimzi YAML.
     */
    static final List<Map.Entry<String, String>> OPERATOR_POD_LABELS = List.of(
        new AbstractMap.SimpleImmutableEntry<>("app.kubernetes.io/name", "strimzi-cluster-operator"),
        new AbstractMap.SimpleImmutableEntry<>("name", "strimzi-cluster-operator"),
        new AbstractMap.SimpleImmutableEntry<>("strimzi.io/kind", "cluster-operator")
    );

    /**
     * Name-based predicate for identifying operator resources as a last-resort fallback.
     */
    static final Predicate<String> OPERATOR_NAME_PATTERN = name ->
        name != null && name.contains("strimzi") && name.contains("operator");

    /**
     * Check whether a resource name matches the operator naming convention.
     *
     * @param name the resource name to check
     * @return true if the name matches the operator naming convention
     */
    public static boolean isOperatorName(String name) {
        return OPERATOR_NAME_PATTERN.test(name);
    }

    @Inject
    KubernetesClient kubernetesClient;

    /**
     * Get default namespace from environment if needed.
     * This is only used as a fallback when no namespace can be determined from user input.
     *
     * @return the default namespace from environment or "strimzi-operator"
     */
    public String getDefaultNamespace() {
        return System.getenv().getOrDefault("K8S_NAMESPACE", "strimzi-operator");
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
     * Uses label-based searches first (fast, server-side), then name-based fallback.
     *
     * @return sorted list of namespaces containing the Strimzi operator
     */
    public List<String> discoverStrimziNamespaces() {
        try {
            LOG.debug("Discovering Strimzi Operator across all namespaces...");
            Set<String> strimziNamespaces = new HashSet<>();

            // Method 1: Label-based deployment search (fast path, server-side)
            for (Map.Entry<String, String> label : OPERATOR_DEPLOYMENT_LABELS) {
                try {
                    List<Deployment> deployments = kubernetesClient.apps().deployments()
                        .inAnyNamespace()
                        .withLabel(label.getKey(), label.getValue())
                        .list()
                        .getItems();
                    strimziNamespaces.addAll(extractNamespaces(deployments));
                } catch (Exception e) {
                    LOG.debugf("Error searching deployments with label %s=%s: %s",
                        label.getKey(), label.getValue(), e.getMessage());
                }
                if (!strimziNamespaces.isEmpty()) {
                    break;
                }
            }

            // Method 2: Name-based deployment search (reliable fallback, client-side)
            if (strimziNamespaces.isEmpty()) {
                List<Deployment> operatorDeployments = findDeploymentsByNamePattern(OPERATOR_NAME_PATTERN);
                strimziNamespaces.addAll(extractNamespaces(operatorDeployments));
            }

            // Method 3: Look for operator pods by labels
            if (strimziNamespaces.isEmpty()) {
                for (Map.Entry<String, String> label : OPERATOR_POD_LABELS) {
                    List<Pod> operatorPods = findPodsByLabels(label.getKey(), label.getValue());
                    strimziNamespaces.addAll(extractNamespaces(operatorPods));
                    if (!strimziNamespaces.isEmpty()) {
                        break;
                    }
                }
            }

            LOG.debugf("Discovered Strimzi Operator in namespaces: %s", strimziNamespaces);
            return strimziNamespaces.stream().sorted().collect(Collectors.toList());

        } catch (Exception e) {
            LOG.warnf("Error discovering Strimzi Operator namespaces: %s", e.getMessage());
            return List.of();
        }
    }

    /**
     * Get Strimzi Kafka clusters across all namespaces or in specific namespace.
     * Includes associated node pools for each cluster.
     *
     * @param namespace the namespace to search in, or null for all namespaces
     * @return list of discovered Kafka cluster information with associated node pools
     */
    public List<KafkaClusterInfo> discoverKafkaClusters(String namespace) {
        try {
            List<Kafka> kafkaResources = queryResources(Kafka.class, namespace);

            return kafkaResources.stream()
                .map(kafka -> {
                    String clusterName = kafka.getMetadata().getName();
                    String clusterNamespace = kafka.getMetadata().getNamespace();

                    // Discover associated node pools for this cluster
                    List<String> nodePools = discoverNodePoolsForCluster(clusterNamespace, clusterName);

                    return new KafkaClusterInfo(
                        clusterName,
                        clusterNamespace,
                        kafka.getStatus() != null ? kafka.getStatus().getConditions() : List.of(),
                        nodePools
                    );
                })
                .collect(Collectors.toList());

        } catch (Exception e) {
            LOG.warnf("Error discovering Kafka clusters: %s", e.getMessage());
            return List.of();
        }
    }

    /**
     * Discover node pools associated with a specific Kafka cluster.
     *
     * @param namespace   the namespace where the cluster is deployed
     * @param clusterName the name of the Kafka cluster
     * @return list of node pool names associated with the cluster
     */
    private List<String> discoverNodePoolsForCluster(String namespace, String clusterName) {
        try {
            List<KafkaNodePool> nodePoolResources = kubernetesClient.resources(KafkaNodePool.class)
                .inNamespace(namespace)
                .withLabel("strimzi.io/cluster", clusterName)
                .list()
                .getItems();

            return nodePoolResources.stream()
                .map(nodePool -> nodePool.getMetadata().getName())
                .sorted()
                .collect(Collectors.toList());

        } catch (Exception e) {
            LOG.debugf("Error discovering node pools for cluster %s in namespace %s: %s",
                clusterName, namespace, e.getMessage());
            return List.of();
        }
    }

    /**
     * Find Strimzi operator pods in a specific namespace.
     * Tries each label strategy in order (server-side), then falls back to name-based matching (client-side).
     *
     * @param namespace the namespace to search in
     * @return list of operator pods found
     */
    public List<Pod> findOperatorPods(String namespace) {
        try {
            // Try each label strategy (server-side filtering, fast)
            for (Map.Entry<String, String> label : OPERATOR_POD_LABELS) {
                List<Pod> operatorPods = kubernetesClient.pods()
                    .inNamespace(namespace)
                    .withLabel(label.getKey(), label.getValue())
                    .list()
                    .getItems();

                if (!operatorPods.isEmpty()) {
                    LOG.debugf("Found operator pods in namespace %s via label %s=%s",
                        namespace, label.getKey(), label.getValue());
                    return operatorPods;
                }
            }

            // Fallback: name-based matching (client-side)
            List<Pod> allPods = kubernetesClient.pods()
                .inNamespace(namespace)
                .list()
                .getItems()
                .stream()
                .filter(p -> isOperatorName(p.getMetadata().getName()))
                .collect(Collectors.toList());

            if (!allPods.isEmpty()) {
                LOG.debugf("Found operator pods in namespace %s via name-based fallback", namespace);
            }
            return allPods;
        } catch (Exception e) {
            LOG.debugf("Error finding operator pods in namespace %s: %s", namespace, e.getMessage());
            return List.of();
        }
    }

    /**
     * Find Strimzi operator deployments in a specific namespace.
     * Tries each label strategy in order (server-side), then falls back to name-based matching (client-side).
     *
     * @param namespace the namespace to search in
     * @return list of operator deployments found
     */
    public List<Deployment> findOperatorDeployments(String namespace) {
        try {
            // Try each label strategy (server-side filtering, fast)
            for (Map.Entry<String, String> label : OPERATOR_DEPLOYMENT_LABELS) {
                List<Deployment> operatorDeployments = kubernetesClient.apps().deployments()
                    .inNamespace(namespace)
                    .withLabel(label.getKey(), label.getValue())
                    .list()
                    .getItems();

                if (!operatorDeployments.isEmpty()) {
                    LOG.debugf("Found operator deployments in namespace %s via label %s=%s",
                        namespace, label.getKey(), label.getValue());
                    return operatorDeployments;
                }
            }

            // Fallback: name-based matching (client-side)
            List<Deployment> allDeployments = kubernetesClient.apps().deployments()
                .inNamespace(namespace)
                .list()
                .getItems()
                .stream()
                .filter(d -> isOperatorName(d.getMetadata().getName()))
                .collect(Collectors.toList());

            if (!allDeployments.isEmpty()) {
                LOG.debugf("Found operator deployments in namespace %s via name-based fallback", namespace);
            }
            return allDeployments;
        } catch (Exception e) {
            LOG.debugf("Error finding operator deployments in namespace %s: %s", namespace, e.getMessage());
            return List.of();
        }
    }

    /**
     * Extract version from deployment container image.
     *
     * @param deployment the deployment to extract version from
     * @return the version string or "unknown"
     */
    public String extractVersionFromDeployment(Deployment deployment) {
        if (deployment.getSpec().getTemplate().getSpec().getContainers() != null &&
            !deployment.getSpec().getTemplate().getSpec().getContainers().isEmpty()) {
            String image = deployment.getSpec().getTemplate().getSpec().getContainers().get(0).getImage();
            if (image.contains(":")) {
                return image.substring(image.lastIndexOf(":") + 1);
            }
        }
        return "unknown";
    }

    /**
     * Extract full image name from deployment.
     *
     * @param deployment the deployment to extract image from
     * @return the image name or "unknown"
     */
    public String extractImageFromDeployment(Deployment deployment) {
        if (deployment.getSpec().getTemplate().getSpec().getContainers() != null &&
            !deployment.getSpec().getTemplate().getSpec().getContainers().isEmpty()) {
            return deployment.getSpec().getTemplate().getSpec().getContainers().get(0).getImage();
        }
        return "unknown";
    }

    /**
     * Calculate deployment uptime in minutes.
     *
     * @param deployment the deployment to calculate uptime for
     * @return uptime in minutes, or null if it cannot be calculated
     */
    public Long calculateDeploymentUptimeMinutes(Deployment deployment) {
        try {
            if (deployment.getMetadata().getCreationTimestamp() != null) {
                Instant created = Instant.parse(deployment.getMetadata().getCreationTimestamp());
                return ChronoUnit.MINUTES.between(created, Instant.now());
            }
        } catch (Exception e) {
            LOG.debugf("Could not calculate uptime for deployment %s: %s",
                deployment.getMetadata().getName(), e.getMessage());
        }
        return null;
    }
}
