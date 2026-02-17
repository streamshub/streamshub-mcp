/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.mcp.service.infra;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.strimzi.api.kafka.model.common.Condition;
import io.strimzi.api.kafka.model.rebalance.KafkaRebalance;
import io.strimzi.mcp.dto.KafkaClusterInfo;
import io.strimzi.mcp.dto.KafkaRebalanceInfo;
import io.strimzi.mcp.dto.KafkaRebalanceResult;
import io.strimzi.mcp.util.InputUtils;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Service for Kafka Rebalance operations.
 * Handles Rebalance discovery and provides information about rebalancing configurations.
 */
@ApplicationScoped
public class KafkaRebalanceService {

    private KafkaRebalanceService() {

    }

    private static final Logger LOG = Logger.getLogger(KafkaRebalanceService.class);

    @Inject
    KubernetesClient kubernetesClient;

    @Inject
    StrimziDiscoveryService discoveryService;

    /**
     * Get Kafka Rebalance operations in namespace or auto-discover across namespaces.
     *
     * @param namespace   the namespace to search in, or null for auto-discovery
     * @param clusterName the cluster name to filter by, or null for all clusters
     * @return structured result containing Rebalance information
     */
    public KafkaRebalanceResult getKafkaRebalances(String namespace, String clusterName) {
        String normalizedNamespace = InputUtils.normalizeNamespace(namespace);
        String normalizedClusterName = InputUtils.normalizeClusterName(clusterName);

        LOG.infof("KafkaRebalanceService: getKafkaRebalances (namespace=%s, cluster=%s)",
            normalizedNamespace, normalizedClusterName);

        // If no namespace specified, auto-discover from Kafka CRs
        if (normalizedNamespace == null) {
            List<KafkaClusterInfo> discoveredClusters = discoveryService.discoverKafkaClusters(null);

            if (discoveredClusters.isEmpty()) {
                return KafkaRebalanceResult.error("not-found",
                    "No Kafka clusters found in any namespace. Please ensure Kafka is deployed. " +
                        "You can specify a namespace explicitly: 'Show me Rebalances in the kafka namespace'");
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
                return KafkaRebalanceResult.error("multiple-found",
                    String.format("Found Kafka clusters in multiple namespaces: %s. " +
                            "Please specify: 'Show me Rebalances for %s'",
                        clusterSuggestions, discoveredClusters.getFirst().getDisplayName()));
            }
        }

        final String effectiveNamespace = normalizedNamespace;

        try {
            List<KafkaRebalance> rebalanceResources;

            if (normalizedClusterName != null) {
                // Get Rebalances for specific cluster
                rebalanceResources = kubernetesClient.resources(KafkaRebalance.class)
                    .inNamespace(effectiveNamespace)
                    .withLabel("strimzi.io/cluster", normalizedClusterName)
                    .list()
                    .getItems();
            } else {
                // Get all Rebalances in the namespace
                rebalanceResources = kubernetesClient.resources(KafkaRebalance.class)
                    .inNamespace(effectiveNamespace)
                    .list()
                    .getItems();
            }

            if (rebalanceResources.isEmpty()) {
                return KafkaRebalanceResult.empty(effectiveNamespace);
            }

            List<KafkaRebalanceInfo> rebalanceInfos = rebalanceResources.stream()
                .map(rebalance -> extractRebalanceInfo(effectiveNamespace, rebalance))
                .sorted(Comparator.comparing(KafkaRebalanceInfo::name))
                .toList();

            return KafkaRebalanceResult.of(rebalanceInfos);

        } catch (Exception e) {
            LOG.errorf(e, "Error retrieving Kafka Rebalances from namespace: %s", effectiveNamespace);
            return KafkaRebalanceResult.error(effectiveNamespace, e.getMessage());
        }
    }

    private KafkaRebalanceInfo extractRebalanceInfo(String namespace, KafkaRebalance rebalance) {
        String name = rebalance.getMetadata().getName();
        String kafkaCluster = rebalance.getMetadata().getLabels() != null ?
            rebalance.getMetadata().getLabels().get("strimzi.io/cluster") : "unknown";

        String mode = "full";
        List<String> goals = new ArrayList<>();

        if (rebalance.getSpec() != null) {
            if (rebalance.getSpec().getMode() != null) {
                mode = rebalance.getSpec().getMode().toString();
            }

            if (rebalance.getSpec().getGoals() != null) {
                goals = rebalance.getSpec().getGoals().stream()
                    .limit(5) // Limit for readability
                    .toList();
            }
        }

        String sessionId = "none";
        if (rebalance.getStatus() != null && rebalance.getStatus().getSessionId() != null) {
            sessionId = rebalance.getStatus().getSessionId();
        }

        String status = "Unknown";
        if (rebalance.getStatus() != null && rebalance.getStatus().getConditions() != null) {
            status = rebalance.getStatus().getConditions().stream()
                .filter(condition -> "Ready".equals(condition.getType()))
                .findFirst()
                .map(Condition::getStatus)
                .orElse("Unknown");
        }

        return new KafkaRebalanceInfo(
            name,
            namespace,
            kafkaCluster,
            mode,
            goals,
            sessionId,
            status
        );
    }
}