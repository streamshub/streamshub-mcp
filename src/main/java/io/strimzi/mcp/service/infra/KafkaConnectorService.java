/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.mcp.service.infra;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.strimzi.api.kafka.model.common.Condition;
import io.strimzi.api.kafka.model.connector.KafkaConnector;
import io.strimzi.mcp.dto.KafkaClusterInfo;
import io.strimzi.mcp.dto.KafkaConnectorInfo;
import io.strimzi.mcp.dto.KafkaConnectorsResult;
import io.strimzi.mcp.util.InputUtils;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Service for Kafka Connector operations.
 * Handles Connector discovery and provides information about Connector configurations.
 */
@ApplicationScoped
public class KafkaConnectorService {

    private KafkaConnectorService() {
    }

    private static final Logger LOG = Logger.getLogger(KafkaConnectorService.class);

    @Inject
    KubernetesClient kubernetesClient;

    @Inject
    StrimziDiscoveryService discoveryService;

    /**
     * Get Kafka Connectors in namespace or auto-discover across namespaces.
     *
     * @param namespace      the namespace to search in, or null for auto-discovery
     * @param connectCluster the connect cluster name to filter by, or null for all clusters
     * @return structured result containing Connector information
     */
    public KafkaConnectorsResult getKafkaConnectors(String namespace, String connectCluster) {
        String normalizedNamespace = InputUtils.normalizeNamespace(namespace);
        String normalizedConnectCluster = InputUtils.normalizeClusterName(connectCluster);

        LOG.infof("KafkaConnectorService: getKafkaConnectors (namespace=%s, connectCluster=%s)",
            normalizedNamespace, normalizedConnectCluster);

        // If no namespace specified, auto-discover from Kafka CRs
        if (normalizedNamespace == null) {
            List<KafkaClusterInfo> discoveredClusters = discoveryService.discoverKafkaClusters(null);

            if (discoveredClusters.isEmpty()) {
                return KafkaConnectorsResult.error("not-found",
                    "No Kafka clusters found in any namespace. Please ensure Kafka is deployed. " +
                        "You can specify a namespace explicitly: 'Show me Connectors in the kafka namespace'");
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
                return KafkaConnectorsResult.error("multiple-found",
                    String.format("Found Kafka clusters in multiple namespaces: %s. " +
                            "Please specify: 'Show me Connectors for %s'",
                        clusterSuggestions, discoveredClusters.getFirst().getDisplayName()));
            }
        }

        final String effectiveNamespace = normalizedNamespace;

        try {
            List<KafkaConnector> connectorResources;

            if (normalizedConnectCluster != null) {
                // Get Connectors for specific Connect cluster
                connectorResources = kubernetesClient.resources(KafkaConnector.class)
                    .inNamespace(effectiveNamespace)
                    .withLabel("strimzi.io/cluster", normalizedConnectCluster)
                    .list()
                    .getItems();
            } else {
                // Get all Connectors in the namespace
                connectorResources = kubernetesClient.resources(KafkaConnector.class)
                    .inNamespace(effectiveNamespace)
                    .list()
                    .getItems();
            }

            if (connectorResources.isEmpty()) {
                return KafkaConnectorsResult.empty(effectiveNamespace);
            }

            List<KafkaConnectorInfo> connectorInfos = connectorResources.stream()
                .map(connector -> extractConnectorInfo(effectiveNamespace, connector))
                .sorted(Comparator.comparing(KafkaConnectorInfo::name))
                .toList();

            return KafkaConnectorsResult.of(connectorInfos);

        } catch (Exception e) {
            LOG.errorf(e, "Error retrieving Kafka Connectors from namespace: %s", effectiveNamespace);
            return KafkaConnectorsResult.error(effectiveNamespace, e.getMessage());
        }
    }

    private KafkaConnectorInfo extractConnectorInfo(String namespace, KafkaConnector connector) {
        String name = connector.getMetadata().getName();
        String connectCluster = connector.getMetadata().getLabels() != null ?
            connector.getMetadata().getLabels().get("strimzi.io/cluster") : "unknown";

        String connectorClass = "unknown";
        Integer tasksMax = null;
        Map<String, String> configKeys = Map.of();

        if (connector.getSpec() != null && connector.getSpec().getConfig() != null) {
            Map<String, Object> config = connector.getSpec().getConfig();

            if (config.get("connector.class") != null) {
                connectorClass = config.get("connector.class").toString();
            }

            if (config.get("tasks.max") != null) {
                try {
                    tasksMax = Integer.valueOf(config.get("tasks.max").toString());
                } catch (NumberFormatException e) {
                    LOG.warnf("Invalid tasks.max value for connector %s: %s", name, config.get("tasks.max"));
                }
            }

            // Extract key configuration properties (limit to first few for readability)
            configKeys = config.entrySet().stream()
                .limit(5)
                .filter(entry -> entry.getValue() != null)
                .collect(Collectors.toMap(
                    Map.Entry::getKey,
                    entry -> entry.getValue().toString()
                ));
        }

        String state = "Unknown";
        String status = "Unknown";

        if (connector.getStatus() != null) {
            if (connector.getStatus().getConnectorStatus() != null) {
                state = connector.getStatus().getConnectorStatus().get("state") != null ?
                    connector.getStatus().getConnectorStatus().get("state").toString() : "Unknown";
            }

            if (connector.getStatus().getConditions() != null) {
                status = connector.getStatus().getConditions().stream()
                    .filter(condition -> "Ready".equals(condition.getType()))
                    .findFirst()
                    .map(Condition::getStatus)
                    .orElse("Unknown");
            }
        }

        return new KafkaConnectorInfo(
            name,
            namespace,
            connectCluster,
            connectorClass,
            tasksMax,
            configKeys,
            state,
            status
        );
    }
}