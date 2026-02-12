/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.mcp.service.infra;

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.strimzi.api.kafka.model.kafka.Kafka;
import io.strimzi.mcp.dto.BootstrapServersResult;
import io.strimzi.mcp.dto.BootstrapServersResult.BootstrapServerInfo;
import io.strimzi.mcp.dto.KafkaClusterInfo;
import io.strimzi.mcp.dto.KafkaClustersResult;
import io.strimzi.mcp.dto.PodsResult;
import io.strimzi.mcp.util.InputUtils;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Service for Kafka cluster operations.
 * Handles cluster discovery, pod management, and cluster-wide information.
 */
@ApplicationScoped
public class KafkaClusterService {

    KafkaClusterService() {
    }

    private static final Logger LOG = Logger.getLogger(KafkaClusterService.class);

    @Inject
    KubernetesClient kubernetesClient;

    @Inject
    StrimziDiscoveryService discoveryService;

    @Inject
    PodsService podsService;

    /**
     * Get cluster pods with structured result.
     *
     * @param namespace the namespace to search in, or null for auto-discovery
     * @param clusterName the cluster name to filter by, or null for all clusters
     * @return structured result containing pod information
     */
    public PodsResult getClusterPods(String namespace, String clusterName) {
        String normalizedNamespace = InputUtils.normalizeNamespace(namespace);

        // If no namespace specified, auto-discover from Kafka CRs
        if (normalizedNamespace == null) {
            List<KafkaClusterInfo> discoveredClusters = discoveryService.discoverKafkaClusters(null);

            if (discoveredClusters.isEmpty()) {
                return PodsResult.error("not-found", clusterName,
                    "No Kafka clusters found in any namespace. Please ensure Kafka is deployed. " +
                    "You can specify a namespace explicitly: 'Show me pods in the kafka namespace'");
            }

            // Deduplicate namespaces
            List<String> distinctNamespaces = discoveredClusters.stream()
                .map(KafkaClusterInfo::namespace)
                .distinct()
                .toList();

            if (distinctNamespaces.size() == 1) {
                normalizedNamespace = distinctNamespaces.get(0);
                LOG.infof("Auto-discovered Kafka cluster in namespace: %s", normalizedNamespace);
            } else {
                String clusterSuggestions = discoveredClusters.stream()
                    .limit(3)
                    .map(KafkaClusterInfo::getDisplayName)
                    .collect(Collectors.joining(", "));
                return PodsResult.error("multiple-found", clusterName,
                    String.format("Found Kafka clusters in multiple namespaces: %s. " +
                        "Please specify: 'Show me pods for %s'",
                        clusterSuggestions, discoveredClusters.get(0).getDisplayName()));
            }
        }

        String normalizedClusterName = InputUtils.normalizeClusterName(clusterName);

        LOG.infof("KafkaClusterService: getClusterPods (namespace=%s, cluster=%s)",
                 normalizedNamespace, normalizedClusterName);

        final String effectiveNamespace = normalizedNamespace;

        try {
            List<Pod> allPods = findKafkaPods(effectiveNamespace, normalizedClusterName);

            if (allPods.isEmpty()) {
                return PodsResult.empty(effectiveNamespace, normalizedClusterName);
            }

            List<PodsResult.PodInfo> podInfos = allPods.stream()
                .map(pod -> podsService.extractPodSummary(effectiveNamespace, pod))
                .sorted(Comparator.comparing(PodsResult.PodInfo::name))
                .toList();

            return PodsResult.of(effectiveNamespace, normalizedClusterName, podInfos);

        } catch (Exception e) {
            LOG.errorf(e, "Error retrieving cluster pods from namespace: %s", effectiveNamespace);
            return PodsResult.error(effectiveNamespace, normalizedClusterName, e.getMessage());
        }
    }

    /**
     * Get Kafka clusters in namespace or auto-discover across namespaces.
     *
     * @param namespace the namespace to search in, or null for auto-discovery
     * @return structured result containing cluster information
     */
    public KafkaClustersResult getKafkaClusters(String namespace) {
        String normalizedNamespace = InputUtils.normalizeNamespace(namespace);

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

        if (hasStrimziLabels(labels)) {
            return true;
        }

        return name.contains("kafka") || name.contains("zookeeper") || name.contains("strimzi");
    }

    private boolean hasStrimziLabels(Map<String, String> labels) {
        if (labels == null) {
            return false;
        }
        if (labels.containsKey("strimzi.io/cluster") || labels.containsKey("strimzi.io/kind")) {
            return true;
        }
        return labels.containsKey("app.kubernetes.io/name")
            && labels.get("app.kubernetes.io/name").contains("strimzi");
    }

    /**
     * Get bootstrap servers for a Kafka cluster from its Custom Resource.
     *
     * @param namespace the namespace to search in, or null for auto-discovery
     * @param clusterName the cluster name to query
     * @return structured result containing bootstrap server information
     */
    public BootstrapServersResult getBootstrapServers(String namespace, String clusterName) {
        String normalizedNamespace = InputUtils.normalizeNamespace(namespace);
        String normalizedClusterName = InputUtils.normalizeClusterName(clusterName);

        // Auto-discover namespace from Kafka CRs if not specified
        if (normalizedNamespace == null) {
            List<KafkaClusterInfo> discoveredClusters = discoveryService.discoverKafkaClusters(null);

            if (discoveredClusters.isEmpty()) {
                return BootstrapServersResult.error(null, normalizedClusterName,
                    "No Kafka clusters found. Please ensure Kafka is deployed.");
            }

            List<String> distinctNamespaces = discoveredClusters.stream()
                .map(KafkaClusterInfo::namespace)
                .distinct()
                .toList();

            if (distinctNamespaces.size() == 1) {
                normalizedNamespace = distinctNamespaces.get(0);
                LOG.infof("Auto-discovered Kafka cluster in namespace: %s", normalizedNamespace);
            } else {
                String clusterSuggestions = discoveredClusters.stream()
                    .limit(3)
                    .map(KafkaClusterInfo::getDisplayName)
                    .collect(Collectors.joining(", "));
                return BootstrapServersResult.error(null, normalizedClusterName,
                    String.format("Found Kafka clusters in multiple namespaces: %s. " +
                        "Please specify: 'Get bootstrap servers for %s in the %s namespace'",
                        clusterSuggestions, normalizedClusterName, distinctNamespaces.get(0)));
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
