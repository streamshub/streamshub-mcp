/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.service.infra;

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.streamshub.mcp.config.StrimziConstants;
import io.streamshub.mcp.dto.KafkaBootstrapResponse;
import io.streamshub.mcp.dto.KafkaBootstrapResponse.BootstrapServerInfo;
import io.streamshub.mcp.dto.KafkaClusterResponse;
import io.streamshub.mcp.dto.PodSummaryResponse;
import io.streamshub.mcp.dto.ToolError;
import io.streamshub.mcp.service.common.KubernetesResourceService;
import io.streamshub.mcp.service.common.PodsService;
import io.streamshub.mcp.util.InputUtils;
import io.strimzi.api.kafka.model.kafka.Kafka;
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

    private static final Logger LOG = Logger.getLogger(KafkaClusterService.class);

    @Inject
    KubernetesClient kubernetesClient;
    @Inject
    StrimziDiscoveryService discoveryService;
    @Inject
    PodsService podsService;
    @Inject
    KubernetesResourceService resourceService;

    KafkaClusterService() {
    }

    /**
     * Get Kafka clusters in namespace or auto-discover across namespaces.
     *
     * @param namespace the namespace to search in, or null for auto-discovery
     * @return list of cluster information or ToolError for errors
     */
    public Object getKafkaClusters(String namespace) {
        String normalizedNamespace = InputUtils.normalizeNamespace(namespace);

        LOG.infof("KafkaClusterService: getKafkaClusters (namespace=%s)", normalizedNamespace);

        try {
            List<KafkaClusterResponse> clusters = discoveryService.discoverKafkaClusters(normalizedNamespace);

            if (clusters.isEmpty() && normalizedNamespace == null) {
                return ToolError.of("No Kafka clusters found in any namespace. Please ensure Kafka resources are deployed.");
            }

            return clusters;

        } catch (Exception e) {
            LOG.errorf(e, "Error retrieving Kafka clusters from namespace: %s", normalizedNamespace);
            return ToolError.of("Failed to retrieve Kafka clusters from namespace " + normalizedNamespace, e);
        }
    }

    /**
     * Get cluster pods with structured result.
     *
     * @param namespace   the namespace to search in, or null for auto-discovery
     * @param clusterName the cluster name to filter by, or null for all clusters
     * @return structured result containing pod information or ToolError for errors
     */
    public Object getClusterPods(String namespace, String clusterName) {
        String normalizedNamespace = InputUtils.normalizeNamespace(namespace);

        // If no namespace specified, auto-discover from Kafka CRs
        if (normalizedNamespace == null) {
            List<KafkaClusterResponse> discoveredClusters = discoveryService.discoverKafkaClusters(null);

            if (discoveredClusters.isEmpty()) {
                return ToolError.of("No Kafka clusters found in any namespace. Please ensure Kafka is deployed. " +
                        "You can specify a namespace explicitly: 'Show me pods in the kafka namespace'");
            }

            // Deduplicate namespaces
            List<String> distinctNamespaces = discoveredClusters.stream()
                .map(KafkaClusterResponse::namespace)
                .distinct()
                .toList();

            if (distinctNamespaces.size() == 1) {
                normalizedNamespace = distinctNamespaces.getFirst();
                LOG.infof("Auto-discovered Kafka cluster in namespace: %s", normalizedNamespace);
            } else {
                String clusterSuggestions = discoveredClusters.stream()
                    .limit(3)
                    .map(KafkaClusterResponse::getDisplayName)
                    .collect(Collectors.joining(", "));
                return ToolError.of(String.format("Found Kafka clusters in multiple namespaces: %s. " +
                            "Please specify: 'Show me pods for %s'",
                        clusterSuggestions, discoveredClusters.getFirst().getDisplayName()));
            }
        }

        String normalizedClusterName = InputUtils.normalizeClusterName(clusterName);

        LOG.infof("KafkaClusterResponseService: getClusterPods (namespace=%s, cluster=%s)",
            normalizedNamespace, normalizedClusterName);

        final String effectiveNamespace = normalizedNamespace;

        try {
            List<Pod> allPods = findKafkaPods(effectiveNamespace, normalizedClusterName);

            if (allPods.isEmpty()) {
                return PodSummaryResponse.empty(effectiveNamespace, normalizedClusterName);
            }

            List<PodSummaryResponse.PodInfo> podInfos = allPods.stream()
                .map(pod -> podsService.extractPodSummary(effectiveNamespace, pod))
                .sorted(Comparator.comparing(PodSummaryResponse.PodInfo::name))
                .toList();

            return PodSummaryResponse.of(effectiveNamespace, normalizedClusterName, podInfos);

        } catch (Exception e) {
            LOG.errorf(e, "Error retrieving cluster pods from namespace: %s", effectiveNamespace);
            return ToolError.of("Failed to retrieve cluster pods from namespace " + effectiveNamespace, e);
        }
    }

    // Private helper methods

    private List<Pod> findKafkaPods(String namespace, String clusterName) {
        if (clusterName != null) {
            // Get pods for specific cluster
            List<Pod> clusterPods = kubernetesClient.pods()
                .inNamespace(namespace)
                .withLabel(StrimziConstants.StrimziLabels.CLUSTER_LABEL, clusterName)
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

        return name.contains("kafka") || name.contains("zookeeper") || name.contains(StrimziConstants.CommonValues.STRIMZI);
    }

    private boolean hasStrimziLabels(Map<String, String> labels) {
        if (labels == null) {
            return false;
        }
        if (labels.containsKey(StrimziConstants.StrimziLabels.CLUSTER_LABEL) || labels.containsKey(StrimziConstants.StrimziLabels.KIND_LABEL)) {
            return true;
        }
        return labels.containsKey(StrimziConstants.KubernetesLabels.APP_NAME_LABEL)
            && labels.get(StrimziConstants.KubernetesLabels.APP_NAME_LABEL).contains(StrimziConstants.CommonValues.STRIMZI);
    }

    /**
     * Get bootstrap servers for a Kafka cluster from its Custom Resource.
     *
     * @param namespace   the namespace to search in, or null for auto-discovery
     * @param clusterName the cluster name to query
     * @return structured result containing bootstrap server information or ToolError for errors
     */
    public Object getBootstrapServers(String namespace, String clusterName) {
        String normalizedNamespace = InputUtils.normalizeNamespace(namespace);
        String normalizedClusterName = InputUtils.normalizeClusterName(clusterName);

        // Auto-discover namespace from Kafka CRs if not specified
        if (normalizedNamespace == null) {
            List<KafkaClusterResponse> discoveredClusters = discoveryService.discoverKafkaClusters(null);

            if (discoveredClusters.isEmpty()) {
                return ToolError.of("No Kafka clusters found. Please ensure Kafka is deployed.");
            }

            List<String> distinctNamespaces = discoveredClusters.stream()
                .map(KafkaClusterResponse::namespace)
                .distinct()
                .toList();

            if (distinctNamespaces.size() == 1) {
                normalizedNamespace = distinctNamespaces.getFirst();
                LOG.infof("Auto-discovered Kafka cluster in namespace: %s", normalizedNamespace);
            } else {
                String clusterSuggestions = discoveredClusters.stream()
                    .limit(3)
                    .map(KafkaClusterResponse::getDisplayName)
                    .collect(Collectors.joining(", "));
                return ToolError.of(String.format("Found Kafka clusters in multiple namespaces: %s. " +
                            "Please specify: 'Get bootstrap servers for %s in the %s namespace'",
                        clusterSuggestions, normalizedClusterName, distinctNamespaces.getFirst()));
            }
        }

        if (normalizedClusterName == null) {
            return ToolError.of("Cluster name is required. Please specify a cluster name like 'my-cluster'");
        }

        LOG.infof("KafkaClusterResponseService: getBootstrapServers (namespace=%s, cluster=%s)",
            normalizedNamespace, normalizedClusterName);

        try {
            Kafka kafka = resourceService.getResource(Kafka.class, normalizedNamespace, normalizedClusterName);

            if (kafka == null) {
                return KafkaBootstrapResponse.notFound(normalizedNamespace, normalizedClusterName);
            }

            List<BootstrapServerInfo> bootstrapServers = extractBootstrapServers(kafka);

            if (bootstrapServers.isEmpty()) {
                return KafkaBootstrapResponse.empty(normalizedNamespace, normalizedClusterName);
            }

            return KafkaBootstrapResponse.of(normalizedNamespace, normalizedClusterName, bootstrapServers);

        } catch (Exception e) {
            LOG.errorf(e, "Error retrieving bootstrap servers for cluster %s in namespace %s",
                normalizedClusterName, normalizedNamespace);
            return ToolError.of("Failed to get bootstrap servers for cluster '" + normalizedClusterName +
                "' in namespace " + normalizedNamespace, e);
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
