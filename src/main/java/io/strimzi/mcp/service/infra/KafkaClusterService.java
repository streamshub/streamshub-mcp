package io.strimzi.mcp.service.infra;

import io.fabric8.kubernetes.api.model.ContainerStatus;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.strimzi.api.kafka.model.kafka.Kafka;
import io.strimzi.mcp.dto.BootstrapServersResult;
import io.strimzi.mcp.dto.BootstrapServersResult.BootstrapServerInfo;
import io.strimzi.mcp.dto.ClusterPodsResult;
import io.strimzi.mcp.dto.KafkaClustersResult;
import io.strimzi.mcp.service.infra.StrimziDiscoveryService.KafkaClusterInfo;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Service for Kafka cluster operations.
 * Handles cluster discovery, pod management, and cluster-wide information.
 */
@ApplicationScoped
public class KafkaClusterService {

    private static final Logger LOG = Logger.getLogger(KafkaClusterService.class);

    @Inject
    KubernetesClient kubernetesClient;

    @Inject
    StrimziDiscoveryService discoveryService;

    @Inject
    StrimziOperatorService operatorService;

    /**
     * Get cluster pods with structured result.
     */
    public ClusterPodsResult getClusterPods(String namespace, String clusterName) {
        String normalizedNamespace = discoveryService.normalizeNamespace(namespace);

        // If no namespace specified, try to auto-discover Strimzi operator and clusters
        if (normalizedNamespace == null) {
            List<String> discoveredNamespaces = discoveryService.discoverStrimziNamespaces();

            if (discoveredNamespaces.isEmpty()) {
                return ClusterPodsResult.error("not-found", clusterName,
                    "No Strimzi installation found in any namespace. Please ensure Strimzi is deployed. " +
                    "You can specify a namespace explicitly: 'Show me pods in the kafka namespace'");
            }

            if (discoveredNamespaces.size() == 1) {
                // Auto-use the single namespace found
                normalizedNamespace = discoveredNamespaces.get(0);
                LOG.infof("Auto-discovered Strimzi installation in namespace: %s", normalizedNamespace);
            } else {
                // Multiple namespaces found, suggest with clusters
                List<KafkaClusterInfo> allClusters = discoveryService.discoverKafkaClusters(null);
                if (!allClusters.isEmpty()) {
                    String clusterSuggestions = allClusters.stream()
                        .limit(3)
                        .map(KafkaClusterInfo::getDisplayName)
                        .collect(Collectors.joining(", "));
                    return ClusterPodsResult.error("multiple-found", clusterName,
                        String.format("Found Kafka clusters in multiple namespaces: %s. " +
                            "Please specify: 'Show me pods for %s'",
                            clusterSuggestions, allClusters.get(0).getDisplayName()));
                } else {
                    String namespaceList = String.join(", ", discoveredNamespaces);
                    return ClusterPodsResult.error("multiple-found", clusterName,
                        String.format("Found Strimzi in multiple namespaces: %s. " +
                            "Please specify: 'Show me pods in the %s namespace'",
                            namespaceList, discoveredNamespaces.get(0)));
                }
            }
        }

        String normalizedClusterName = discoveryService.normalizeClusterName(clusterName);

        LOG.infof("KafkaClusterService: getClusterPods (namespace=%s, cluster=%s)",
                 normalizedNamespace, normalizedClusterName);

        try {
            List<Pod> allPods = findKafkaPods(normalizedNamespace, normalizedClusterName);

            if (allPods.isEmpty()) {
                return ClusterPodsResult.empty(normalizedNamespace, normalizedClusterName);
            }

            List<ClusterPodsResult.PodInfo> podInfos = allPods.stream()
                .map(this::convertToPodInfo)
                .sorted(Comparator.comparing(ClusterPodsResult.PodInfo::name))
                .toList();

            Map<String, Integer> componentBreakdown = calculateComponentBreakdown(podInfos);

            return ClusterPodsResult.of(normalizedNamespace, normalizedClusterName, podInfos, componentBreakdown);

        } catch (Exception e) {
            LOG.errorf(e, "Error retrieving cluster pods from namespace: %s", normalizedNamespace);
            return ClusterPodsResult.error(normalizedNamespace, normalizedClusterName, e.getMessage());
        }
    }

    /**
     * Get Kafka clusters in namespace or auto-discover across namespaces.
     */
    public KafkaClustersResult getKafkaClusters(String namespace) {
        String normalizedNamespace = discoveryService.normalizeNamespace(namespace);

        LOG.infof("KafkaClusterService: getKafkaClusters (namespace=%s)", normalizedNamespace);

        try {
            List<KafkaClusterInfo> clusters = discoveryService.discoverKafkaClusters(normalizedNamespace);

            if (clusters.isEmpty()) {
                if (normalizedNamespace != null) {
                    return KafkaClustersResult.empty(normalizedNamespace);
                } else {
                    return KafkaClustersResult.error("not-found",
                        "No Kafka clusters found in any namespace. Please ensure Kafka resources are deployed.");
                }
            }

            return KafkaClustersResult.of(clusters);

        } catch (Exception e) {
            LOG.errorf(e, "Error retrieving Kafka clusters from namespace: %s", normalizedNamespace);
            return KafkaClustersResult.error(normalizedNamespace != null ? normalizedNamespace : "all", e.getMessage());
        }
    }

    // Private helper methods

    private List<Pod> findKafkaPods(String namespace, String clusterName) {
        if (clusterName != null) {
            // Get pods for specific cluster
            List<Pod> clusterPods = kubernetesClient.pods()
                .inNamespace(namespace)
                .withLabel("strimzi.io/cluster", clusterName)
                .list()
                .getItems();

            // Also include operator pods
            List<Pod> operatorPods = discoveryService.findOperatorPods(namespace);
            List<Pod> allPods = new ArrayList<>(clusterPods);
            allPods.addAll(operatorPods);
            return allPods;
        } else {
            // Get all Strimzi/Kafka related pods
            return kubernetesClient.pods()
                .inNamespace(namespace)
                .list()
                .getItems()
                .stream()
                .filter(this::isKafkaRelatedPod)
                .toList();
        }
    }


    private boolean isKafkaRelatedPod(Pod pod) {
        Map<String, String> labels = pod.getMetadata().getLabels();
        String name = pod.getMetadata().getName();
        return (labels != null && (
            labels.containsKey("strimzi.io/cluster") ||
            labels.containsKey("strimzi.io/kind") ||
            labels.containsKey("app.kubernetes.io/name") && labels.get("app.kubernetes.io/name").contains("strimzi")
        )) || name.contains("kafka") || name.contains("zookeeper") || name.contains("strimzi");
    }

    private ClusterPodsResult.PodInfo convertToPodInfo(Pod pod) {
        String name = pod.getMetadata().getName();
        String phase = pod.getStatus().getPhase();
        Map<String, String> labels = pod.getMetadata().getLabels() != null ?
            pod.getMetadata().getLabels() : new HashMap<>();

        // Check if pod is ready
        boolean ready = false;
        if (pod.getStatus().getConditions() != null) {
            ready = pod.getStatus().getConditions().stream()
                .anyMatch(condition ->
                    "Ready".equals(condition.getType()) && "True".equals(condition.getStatus()));
        }

        // Determine component type
        String component = determineComponent(name, labels);

        // Count restarts
        int restarts = 0;
        if (pod.getStatus().getContainerStatuses() != null) {
            restarts = pod.getStatus().getContainerStatuses().stream()
                .mapToInt(ContainerStatus::getRestartCount)
                .sum();
        }

        // Calculate age
        long ageMinutes = 0;
        if (pod.getMetadata().getCreationTimestamp() != null) {
            Instant created = Instant.parse(pod.getMetadata().getCreationTimestamp());
            ageMinutes = ChronoUnit.MINUTES.between(created, Instant.now());
        }

        return new ClusterPodsResult.PodInfo(name, phase, ready, component, restarts, ageMinutes);
    }

    private String determineComponent(String name, Map<String, String> labels) {
        if (labels.containsKey("strimzi.io/kind")) {
            return labels.get("strimzi.io/kind").toLowerCase();
        }
        if (name.contains("kafka-operator") || name.contains("strimzi-cluster-operator")) {
            return "operator";
        }
        if (name.contains("entity-operator")) {
            return "entity-operator";
        }
        if (name.contains("kafka") && !name.contains("zookeeper")) {
            return "kafka";
        }
        if (name.contains("zookeeper")) {
            return "zookeeper";
        }
        return "unknown";
    }

    private Map<String, Integer> calculateComponentBreakdown(List<ClusterPodsResult.PodInfo> pods) {
        return pods.stream()
            .collect(Collectors.groupingBy(
                ClusterPodsResult.PodInfo::component,
                Collectors.collectingAndThen(Collectors.counting(), Math::toIntExact)
            ));
    }

    /**
     * Get bootstrap servers for a Kafka cluster from its Custom Resource.
     */
    public BootstrapServersResult getBootstrapServers(String namespace, String clusterName) {
        String normalizedNamespace = discoveryService.normalizeNamespace(namespace);
        String normalizedClusterName = discoveryService.normalizeClusterName(clusterName);

        // Auto-discover namespace if not specified
        if (normalizedNamespace == null) {
            List<String> discoveredNamespaces = discoveryService.discoverStrimziNamespaces();
            if (discoveredNamespaces.size() == 1) {
                normalizedNamespace = discoveredNamespaces.get(0);
                LOG.infof("Auto-discovered Strimzi in namespace: %s", normalizedNamespace);
            } else if (!discoveredNamespaces.isEmpty()) {
                String namespaceList = String.join(", ", discoveredNamespaces);
                return BootstrapServersResult.error(null, normalizedClusterName,
                    String.format("Found Strimzi in multiple namespaces: %s. " +
                        "Please specify: 'Get bootstrap servers for %s in the %s namespace'",
                        namespaceList, normalizedClusterName, discoveredNamespaces.get(0)));
            } else {
                return BootstrapServersResult.error(null, normalizedClusterName,
                    "No Strimzi installation found. Please ensure Kafka is deployed.");
            }
        }

        if (normalizedClusterName == null) {
            return BootstrapServersResult.error(normalizedNamespace, null,
                "Cluster name is required. Please specify a cluster name like 'my-cluster'");
        }

        LOG.infof("KafkaClusterService: getBootstrapServers (namespace=%s, cluster=%s)",
                 normalizedNamespace, normalizedClusterName);

        try {
            Kafka kafka = kubernetesClient.resources(Kafka.class)
                .inNamespace(normalizedNamespace)
                .withName(normalizedClusterName)
                .get();

            if (kafka == null) {
                return BootstrapServersResult.notFound(normalizedNamespace, normalizedClusterName);
            }

            List<BootstrapServerInfo> bootstrapServers = extractBootstrapServers(kafka);

            if (bootstrapServers.isEmpty()) {
                return BootstrapServersResult.empty(normalizedNamespace, normalizedClusterName);
            }

            return BootstrapServersResult.of(normalizedNamespace, normalizedClusterName, bootstrapServers);

        } catch (Exception e) {
            LOG.errorf(e, "Error retrieving bootstrap servers for cluster %s in namespace %s",
                      normalizedClusterName, normalizedNamespace);
            return BootstrapServersResult.error(normalizedNamespace, normalizedClusterName, e.getMessage());
        }
    }

    private List<BootstrapServerInfo> extractBootstrapServers(Kafka kafka) {
        List<BootstrapServerInfo> servers = new ArrayList<>();

        if (kafka.getStatus() == null || kafka.getStatus().getListeners() == null) {
            return servers;
        }

        // Extract from listeners status
        kafka.getStatus().getListeners().forEach(listener -> {
            String listenerName = listener.getName();
            String listenerType = listener.getType();

            if (listener.getAddresses() != null) {
                listener.getAddresses().forEach(address -> {
                    String host = address.getHost();
                    Integer port = address.getPort();

                    if (host != null && port != null) {
                        servers.add(new BootstrapServerInfo(
                            host,
                            port,
                            listenerName,
                            listenerType,
                            String.format("%s:%d", host, port)
                        ));
                    }
                });
            }
        });

        return servers;
    }
}