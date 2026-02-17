/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.mcp.service.infra;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.strimzi.api.kafka.model.common.Condition;
import io.strimzi.api.kafka.model.user.KafkaUser;
import io.strimzi.mcp.dto.KafkaClusterInfo;
import io.strimzi.mcp.dto.KafkaUserInfo;
import io.strimzi.mcp.dto.KafkaUsersResult;
import io.strimzi.mcp.util.InputUtils;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Service for Kafka User operations.
 * Handles User discovery and provides information about User configurations.
 */
@ApplicationScoped
public class KafkaUserService {

    private KafkaUserService() {

    }

    private static final Logger LOG = Logger.getLogger(KafkaUserService.class);

    @Inject
    KubernetesClient kubernetesClient;

    @Inject
    StrimziDiscoveryService discoveryService;

    /**
     * Get Kafka Users in namespace or auto-discover across namespaces.
     *
     * @param namespace   the namespace to search in, or null for auto-discovery
     * @param clusterName the cluster name to filter by, or null for all clusters
     * @return structured result containing User information
     */
    public KafkaUsersResult getKafkaUsers(String namespace, String clusterName) {
        String normalizedNamespace = InputUtils.normalizeNamespace(namespace);
        String normalizedClusterName = InputUtils.normalizeClusterName(clusterName);

        LOG.infof("KafkaUserService: getKafkaUsers (namespace=%s, cluster=%s)",
            normalizedNamespace, normalizedClusterName);

        // If no namespace specified, auto-discover from Kafka CRs
        if (normalizedNamespace == null) {
            List<KafkaClusterInfo> discoveredClusters = discoveryService.discoverKafkaClusters(null);

            if (discoveredClusters.isEmpty()) {
                return KafkaUsersResult.error("not-found",
                    "No Kafka clusters found in any namespace. Please ensure Kafka is deployed. " +
                        "You can specify a namespace explicitly: 'Show me Users in the kafka namespace'");
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
                return KafkaUsersResult.error("multiple-found",
                    String.format("Found Kafka clusters in multiple namespaces: %s. " +
                            "Please specify: 'Show me Users for %s'",
                        clusterSuggestions, discoveredClusters.getFirst().getDisplayName()));
            }
        }

        final String effectiveNamespace = normalizedNamespace;

        try {
            List<KafkaUser> userResources;

            if (normalizedClusterName != null) {
                // Get Users for specific cluster
                userResources = kubernetesClient.resources(KafkaUser.class)
                    .inNamespace(effectiveNamespace)
                    .withLabel("strimzi.io/cluster", normalizedClusterName)
                    .list()
                    .getItems();
            } else {
                // Get all Users in the namespace
                userResources = kubernetesClient.resources(KafkaUser.class)
                    .inNamespace(effectiveNamespace)
                    .list()
                    .getItems();
            }

            if (userResources.isEmpty()) {
                return KafkaUsersResult.empty(effectiveNamespace);
            }

            List<KafkaUserInfo> userInfos = userResources.stream()
                .map(user -> extractUserInfo(effectiveNamespace, user))
                .sorted(Comparator.comparing(KafkaUserInfo::name))
                .toList();

            return KafkaUsersResult.of(userInfos);

        } catch (Exception e) {
            LOG.errorf(e, "Error retrieving Kafka Users from namespace: %s", effectiveNamespace);
            return KafkaUsersResult.error(effectiveNamespace, e.getMessage());
        }
    }

    private KafkaUserInfo extractUserInfo(String namespace, KafkaUser user) {
        String name = user.getMetadata().getName();
        String kafkaCluster = user.getMetadata().getLabels() != null ?
            user.getMetadata().getLabels().get("strimzi.io/cluster") : "unknown";

        String authentication = "none";
        if (user.getSpec() != null && user.getSpec().getAuthentication() != null) {
            authentication = user.getSpec().getAuthentication().getType();
        }

        String authorization = "none";
        List<String> aclRules = new ArrayList<>();

        if (user.getSpec() != null && user.getSpec().getAuthorization() != null) {
            authorization = user.getSpec().getAuthorization().getType();
        }

        String status = "Unknown";
        if (user.getStatus() != null && user.getStatus().getConditions() != null) {
            status = user.getStatus().getConditions().stream()
                .filter(condition -> "Ready".equals(condition.getType()))
                .findFirst()
                .map(Condition::getStatus)
                .orElse("Unknown");
        }

        return new KafkaUserInfo(
            name,
            namespace,
            kafkaCluster,
            authentication,
            authorization,
            aclRules,
            status
        );
    }
}