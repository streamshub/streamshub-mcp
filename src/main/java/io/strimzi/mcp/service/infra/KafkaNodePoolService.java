/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.mcp.service.infra;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.strimzi.api.kafka.model.common.Condition;
import io.strimzi.api.kafka.model.nodepool.KafkaNodePool;
import io.strimzi.mcp.dto.KafkaClusterInfo;
import io.strimzi.mcp.dto.KafkaNodePoolInfo;
import io.strimzi.mcp.dto.KafkaNodePoolsResult;
import io.strimzi.mcp.util.InputUtils;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Service for Kafka node pool operations.
 * Handles node pool discovery and provides information about node pool configurations.
 */
@ApplicationScoped
public class KafkaNodePoolService {

    private KafkaNodePoolService() {

    }

    private static final Logger LOG = Logger.getLogger(KafkaNodePoolService.class);

    @Inject
    KubernetesClient kubernetesClient;

    @Inject
    StrimziDiscoveryService discoveryService;

    /**
     * Get Kafka node pools in namespace or auto-discover across namespaces.
     *
     * @param namespace   the namespace to search in, or null for auto-discovery
     * @param clusterName the cluster name to filter by, or null for all clusters
     * @return structured result containing node pool information
     */
    public KafkaNodePoolsResult getKafkaNodePools(String namespace, String clusterName) {
        String normalizedNamespace = InputUtils.normalizeNamespace(namespace);
        String normalizedClusterName = InputUtils.normalizeClusterName(clusterName);

        LOG.infof("KafkaNodePoolService: getKafkaNodePools (namespace=%s, cluster=%s)",
            normalizedNamespace, normalizedClusterName);

        // If no namespace specified, auto-discover from Kafka CRs
        if (normalizedNamespace == null) {
            List<KafkaClusterInfo> discoveredClusters = discoveryService.discoverKafkaClusters(null);

            if (discoveredClusters.isEmpty()) {
                return KafkaNodePoolsResult.error("not-found",
                    "No Kafka clusters found in any namespace. Please ensure Kafka is deployed. " +
                        "You can specify a namespace explicitly: 'Show me node pools in the kafka namespace'");
            }

            // Deduplicate namespaces
            List<String> distinctNamespaces = discoveredClusters.stream()
                .map(KafkaClusterInfo::namespace)
                .distinct()
                .toList();

            if (distinctNamespaces.size() == 1) {
                normalizedNamespace = distinctNamespaces.getFirst();
                LOG.infof("Auto-discovered Kafka cluster in namespace: %s", normalizedNamespace);
            } else {
                String clusterSuggestions = discoveredClusters.stream()
                    .limit(3)
                    .map(KafkaClusterInfo::getDisplayName)
                    .collect(Collectors.joining(", "));
                return KafkaNodePoolsResult.error("multiple-found",
                    String.format("Found Kafka clusters in multiple namespaces: %s. " +
                            "Please specify: 'Show me node pools for %s'",
                        clusterSuggestions, discoveredClusters.getFirst().getDisplayName()));
            }
        }

        final String effectiveNamespace = normalizedNamespace;

        try {
            List<KafkaNodePool> nodePoolResources;

            if (normalizedClusterName != null) {
                // Get node pools for specific cluster
                nodePoolResources = kubernetesClient.resources(KafkaNodePool.class)
                    .inNamespace(effectiveNamespace)
                    .withLabel("strimzi.io/cluster", normalizedClusterName)
                    .list()
                    .getItems();
            } else {
                // Get all node pools in the namespace
                nodePoolResources = kubernetesClient.resources(KafkaNodePool.class)
                    .inNamespace(effectiveNamespace)
                    .list()
                    .getItems();
            }

            if (nodePoolResources.isEmpty()) {
                return KafkaNodePoolsResult.empty(effectiveNamespace);
            }

            List<KafkaNodePoolInfo> nodePoolInfos = nodePoolResources.stream()
                .map(nodePool -> extractNodePoolInfo(effectiveNamespace, nodePool))
                .sorted(Comparator.comparing(KafkaNodePoolInfo::name))
                .toList();

            return KafkaNodePoolsResult.of(nodePoolInfos);

        } catch (Exception e) {
            LOG.errorf(e, "Error retrieving Kafka node pools from namespace: %s", effectiveNamespace);
            return KafkaNodePoolsResult.error(effectiveNamespace, e.getMessage());
        }
    }

    private KafkaNodePoolInfo extractNodePoolInfo(String namespace, KafkaNodePool nodePool) {
        String name = nodePool.getMetadata().getName();
        String kafkaCluster = nodePool.getMetadata().getLabels() != null ?
            nodePool.getMetadata().getLabels().get("strimzi.io/cluster") : "unknown";

        Integer replicas = nodePool.getSpec() != null ? nodePool.getSpec().getReplicas() : null;

        List<String> roles = new ArrayList<>();
        if (nodePool.getSpec() != null && nodePool.getSpec().getRoles() != null) {
            roles.addAll(nodePool.getSpec().getRoles().stream().map(Object::toString).toList());
        }

        List<Integer> nodeIds = new ArrayList<>();
        if (nodePool.getStatus() != null && nodePool.getStatus().getNodeIds() != null) {
            nodeIds.addAll(nodePool.getStatus().getNodeIds());
        }

        String status = "Unknown";
        if (nodePool.getStatus() != null && nodePool.getStatus().getConditions() != null) {
            status = nodePool.getStatus().getConditions().stream()
                .filter(condition -> "Ready".equals(condition.getType()))
                .findFirst()
                .map(Condition::getStatus)
                .orElse("Unknown");
        }

        return new KafkaNodePoolInfo(
            name,
            namespace,
            kafkaCluster,
            replicas,
            roles,
            nodeIds,
            status
        );
    }
}