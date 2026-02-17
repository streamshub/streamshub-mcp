/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.mcp.service.infra;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.strimzi.api.kafka.model.bridge.KafkaBridge;
import io.strimzi.api.kafka.model.common.Condition;
import io.strimzi.mcp.dto.KafkaBridgeInfo;
import io.strimzi.mcp.dto.KafkaBridgeResult;
import io.strimzi.mcp.dto.KafkaClusterInfo;
import io.strimzi.mcp.util.InputUtils;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Service for Kafka Bridge operations.
 * Handles Bridge discovery and provides information about Bridge configurations.
 */
@ApplicationScoped
public class KafkaBridgeService {

    private KafkaBridgeService() {

    }

    private static final Logger LOG = Logger.getLogger(KafkaBridgeService.class);

    @Inject
    KubernetesClient kubernetesClient;

    @Inject
    StrimziDiscoveryService discoveryService;

    /**
     * Get Kafka Bridges in namespace or auto-discover across namespaces.
     *
     * @param namespace   the namespace to search in, or null for auto-discovery
     * @param clusterName the cluster name to filter by, or null for all clusters
     * @return structured result containing Bridge information
     */
    public KafkaBridgeResult getKafkaBridges(String namespace, String clusterName) {
        String normalizedNamespace = InputUtils.normalizeNamespace(namespace);
        String normalizedClusterName = InputUtils.normalizeClusterName(clusterName);

        LOG.infof("KafkaBridgeService: getKafkaBridges (namespace=%s, cluster=%s)",
            normalizedNamespace, normalizedClusterName);

        // If no namespace specified, auto-discover from Kafka CRs
        if (normalizedNamespace == null) {
            List<KafkaClusterInfo> discoveredClusters = discoveryService.discoverKafkaClusters(null);

            if (discoveredClusters.isEmpty()) {
                return KafkaBridgeResult.error("not-found",
                    "No Kafka clusters found in any namespace. Please ensure Kafka is deployed. " +
                        "You can specify a namespace explicitly: 'Show me Bridges in the kafka namespace'");
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
                return KafkaBridgeResult.error("multiple-found",
                    String.format("Found Kafka clusters in multiple namespaces: %s. " +
                            "Please specify: 'Show me Bridges for %s'",
                        clusterSuggestions, discoveredClusters.getFirst().getDisplayName()));
            }
        }

        final String effectiveNamespace = normalizedNamespace;

        try {
            List<KafkaBridge> bridgeResources;

            if (normalizedClusterName != null) {
                // Get Bridges for specific cluster
                bridgeResources = kubernetesClient.resources(KafkaBridge.class)
                    .inNamespace(effectiveNamespace)
                    .withLabel("strimzi.io/cluster", normalizedClusterName)
                    .list()
                    .getItems();
            } else {
                // Get all Bridges in the namespace
                bridgeResources = kubernetesClient.resources(KafkaBridge.class)
                    .inNamespace(effectiveNamespace)
                    .list()
                    .getItems();
            }

            if (bridgeResources.isEmpty()) {
                return KafkaBridgeResult.empty(effectiveNamespace);
            }

            List<KafkaBridgeInfo> bridgeInfos = bridgeResources.stream()
                .map(bridge -> extractBridgeInfo(effectiveNamespace, bridge))
                .sorted(Comparator.comparing(KafkaBridgeInfo::name))
                .toList();

            return KafkaBridgeResult.of(bridgeInfos);

        } catch (Exception e) {
            LOG.errorf(e, "Error retrieving Kafka Bridges from namespace: %s", effectiveNamespace);
            return KafkaBridgeResult.error(effectiveNamespace, e.getMessage());
        }
    }

    private KafkaBridgeInfo extractBridgeInfo(String namespace, KafkaBridge bridge) {
        String name = bridge.getMetadata().getName();
        String kafkaCluster = bridge.getMetadata().getLabels() != null ?
            bridge.getMetadata().getLabels().get("strimzi.io/cluster") : "unknown";

        Integer replicas = bridge.getSpec() != null ? bridge.getSpec().getReplicas() : null;

        int httpPort = 8080; // Default Kafka Bridge port
        if (bridge.getSpec() != null && bridge.getSpec().getHttp() != null) {
            httpPort = bridge.getSpec().getHttp().getPort();
        }

        String apiUrl = "unknown";
        if (bridge.getStatus() != null && bridge.getStatus().getUrl() != null) {
            apiUrl = bridge.getStatus().getUrl();
        }

        String status = "Unknown";
        if (bridge.getStatus() != null && bridge.getStatus().getConditions() != null) {
            status = bridge.getStatus().getConditions().stream()
                .filter(condition -> "Ready".equals(condition.getType()))
                .findFirst()
                .map(Condition::getStatus)
                .orElse("Unknown");
        }

        return new KafkaBridgeInfo(
            name,
            namespace,
            kafkaCluster,
            replicas,
            httpPort,
            apiUrl,
            status
        );
    }
}