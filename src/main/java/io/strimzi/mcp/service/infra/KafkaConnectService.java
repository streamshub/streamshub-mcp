/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.mcp.service.infra;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.strimzi.api.kafka.model.connect.KafkaConnect;
import io.strimzi.mcp.dto.KafkaClusterInfo;
import io.strimzi.mcp.dto.KafkaConnectInfo;
import io.strimzi.mcp.dto.KafkaConnectResult;
import io.strimzi.mcp.util.InputUtils;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Service for Kafka Connect operations.
 * Handles Connect cluster discovery and provides information about Connect configurations.
 */
@ApplicationScoped
public class KafkaConnectService {

    private KafkaConnectService() {

    }

    private static final Logger LOG = Logger.getLogger(KafkaConnectService.class);

    @Inject
    KubernetesClient kubernetesClient;

    @Inject
    StrimziDiscoveryService discoveryService;

    /**
     * Get Kafka Connect clusters in namespace or auto-discover across namespaces.
     *
     * @param namespace   the namespace to search in, or null for auto-discovery
     * @param clusterName the cluster name to filter by, or null for all clusters
     * @return structured result containing Connect cluster information
     */
    public KafkaConnectResult getKafkaConnects(String namespace, String clusterName) {
        String normalizedNamespace = InputUtils.normalizeNamespace(namespace);
        String normalizedClusterName = InputUtils.normalizeClusterName(clusterName);

        LOG.infof("KafkaConnectService: getKafkaConnects (namespace=%s, cluster=%s)",
            normalizedNamespace, normalizedClusterName);

        // If no namespace specified, auto-discover from Kafka CRs
        if (normalizedNamespace == null) {
            List<KafkaClusterInfo> discoveredClusters = discoveryService.discoverKafkaClusters(null);

            if (discoveredClusters.isEmpty()) {
                return KafkaConnectResult.error("not-found",
                    "No Kafka clusters found in any namespace. Please ensure Kafka is deployed. " +
                        "You can specify a namespace explicitly: 'Show me Connect clusters in the kafka namespace'");
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
                return KafkaConnectResult.error("multiple-found",
                    String.format("Found Kafka clusters in multiple namespaces: %s. " +
                            "Please specify: 'Show me Connect clusters for %s'",
                        clusterSuggestions, discoveredClusters.get(0).getDisplayName()));
            }
        }

        final String effectiveNamespace = normalizedNamespace;

        try {
            List<KafkaConnect> connectResources;

            if (normalizedClusterName != null) {
                // Get Connect clusters for specific Kafka cluster
                connectResources = kubernetesClient.resources(KafkaConnect.class)
                    .inNamespace(effectiveNamespace)
                    .withLabel("strimzi.io/cluster", normalizedClusterName)
                    .list()
                    .getItems();
            } else {
                // Get all Connect clusters in the namespace
                connectResources = kubernetesClient.resources(KafkaConnect.class)
                    .inNamespace(effectiveNamespace)
                    .list()
                    .getItems();
            }

            if (connectResources.isEmpty()) {
                return KafkaConnectResult.empty(effectiveNamespace);
            }

            List<KafkaConnectInfo> connectInfos = connectResources.stream()
                .map(connect -> extractConnectInfo(effectiveNamespace, connect))
                .sorted(Comparator.comparing(KafkaConnectInfo::name))
                .toList();

            return KafkaConnectResult.of(connectInfos);

        } catch (Exception e) {
            LOG.errorf(e, "Error retrieving Kafka Connect clusters from namespace: %s", effectiveNamespace);
            return KafkaConnectResult.error(effectiveNamespace, e.getMessage());
        }
    }

    private KafkaConnectInfo extractConnectInfo(String namespace, KafkaConnect connect) {
        String name = connect.getMetadata().getName();
        String kafkaCluster = connect.getSpec() != null && connect.getSpec().getBootstrapServers() != null ?
            "external" : "unknown";

        // Try to get cluster name from labels
        if (connect.getMetadata().getLabels() != null &&
            connect.getMetadata().getLabels().containsKey("strimzi.io/cluster")) {
            kafkaCluster = connect.getMetadata().getLabels().get("strimzi.io/cluster");
        }

        Integer replicas = connect.getSpec() != null ? connect.getSpec().getReplicas() : null;
        String version = connect.getSpec() != null ? connect.getSpec().getVersion() : null;

        String build = "none";
        if (connect.getSpec() != null && connect.getSpec().getBuild() != null) {
            build = "configured";
        }

        String restApiUrl = "unknown";
        if (connect.getStatus() != null && connect.getStatus().getUrl() != null) {
            restApiUrl = connect.getStatus().getUrl();
        }

        String status = "Unknown";
        if (connect.getStatus() != null && connect.getStatus().getConditions() != null) {
            status = connect.getStatus().getConditions().stream()
                .filter(condition -> "Ready".equals(condition.getType()))
                .findFirst()
                .map(condition -> condition.getStatus())
                .orElse("Unknown");
        }

        return new KafkaConnectInfo(
            name,
            namespace,
            kafkaCluster,
            replicas,
            version,
            build,
            restApiUrl,
            status
        );
    }
}