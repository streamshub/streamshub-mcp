/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.service.infra;

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.streamshub.mcp.config.StrimziConstants;
import io.streamshub.mcp.dto.KafkaClusterResponse;
import io.streamshub.mcp.service.common.KubernetesResourceService;
import io.strimzi.api.kafka.model.common.Condition;
import io.strimzi.api.kafka.model.kafka.Kafka;
import io.strimzi.api.kafka.model.kafka.listener.GenericKafkaListener;
import io.strimzi.api.kafka.model.kafka.listener.KafkaListenerType;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Service for discovering Strimzi installations and shared utility methods.
 * Provides comprehensive discovery of namespaces containing any Strimzi resources.
 */
@ApplicationScoped
public class StrimziDiscoveryService {

    private static final Logger LOG = Logger.getLogger(StrimziDiscoveryService.class);

    @Inject
    KubernetesClient kubernetesClient;
    @Inject
    KubernetesResourceService resourceService;

    StrimziDiscoveryService() {
    }

    /**
     * Get Strimzi Kafka clusters across all namespaces or in specific namespace.
     *
     * @param namespace the namespace to search in, or null for all namespaces
     * @return list of discovered Kafka cluster information with comprehensive details
     */
    public List<KafkaClusterResponse> discoverKafkaClusters(String namespace) {
        try {
            List<Kafka> kafkaResources = resourceService.queryResources(Kafka.class, namespace);

            return kafkaResources.stream()
                .map(this::buildKafkaClusterResponse)
                .collect(Collectors.toList());

        } catch (Exception e) {
            LOG.warnf("Error discovering Kafka clusters: %s", e.getMessage());
            return List.of();
        }
    }

    /**
     * Build comprehensive KafkaClusterResponse from Kafka Custom Resource.
     */
    private KafkaClusterResponse buildKafkaClusterResponse(Kafka kafka) {
        String name = kafka.getMetadata().getName();
        String namespace = kafka.getMetadata().getNamespace();
        String status = determineClusterStatus(kafka);

        // Extract basic cluster information
        String kafkaVersion = extractKafkaVersion(kafka);
        Integer replicas = extractReplicas(kafka);
        Integer readyReplicas = extractReadyReplicas(kafka);

        // Extract storage information
        StorageInfo storageInfo = extractStorageInfo(kafka);

        // Extract listener information
        ListenerInfo listenerInfo = extractListenerInfo(kafka);

        // Extract security information
        SecurityInfo securityInfo = extractSecurityInfo(kafka);

        // Extract timing information
        TimingInfo timingInfo = extractTimingInfo(kafka, name);

        // Extract operator version
        String managedBy = extractManagedBy(kafka);

        return new KafkaClusterResponse(
            name,
            namespace,
            status,
            kafkaVersion,
            replicas,
            readyReplicas,
            storageInfo.type(),
            storageInfo.size(),
            listenerInfo.listeners().isEmpty() ? null : listenerInfo.listeners(),
            listenerInfo.externalAccess(),
            securityInfo.authenticationEnabled(),
            securityInfo.authorizationEnabled(),
            timingInfo.creationTime(),
            timingInfo.ageMinutes(),
            managedBy
        );
    }

    private String extractKafkaVersion(Kafka kafka) {
        if (kafka.getSpec() != null && kafka.getSpec().getKafka() != null) {
            return kafka.getSpec().getKafka().getVersion();
        }
        return null;
    }

    private Integer extractReplicas(Kafka kafka) {
        if (kafka.getSpec() != null && kafka.getSpec().getKafka() != null) {
            return kafka.getSpec().getKafka().getReplicas();
        }
        return null;
    }

    private Integer extractReadyReplicas(Kafka kafka) {
        // Simplified extraction - in a real implementation we'd need to check pod status
        // For now, assume ready replicas equals replicas if status is Ready
        if (kafka.getStatus() != null && kafka.getStatus().getConditions() != null) {
            boolean isReady = kafka.getStatus().getConditions().stream()
                .anyMatch(condition -> StrimziConstants.ConditionTypes.READY.equals(condition.getType()) &&
                                     StrimziConstants.ConditionStatuses.TRUE.equals(condition.getStatus()));
            if (isReady) {
                return extractReplicas(kafka);
            }
        }
        return null;
    }

    private StorageInfo extractStorageInfo(Kafka kafka) {
        if (kafka.getSpec() != null && kafka.getSpec().getKafka() != null && kafka.getSpec().getKafka().getStorage() != null) {
            Object storage = kafka.getSpec().getKafka().getStorage();
            return new StorageInfo(determineStorageType(storage), extractStorageSize(storage));
        }
        return new StorageInfo(null, null);
    }

    private ListenerInfo extractListenerInfo(Kafka kafka) {
        List<String> listeners = new ArrayList<>();
        boolean externalAccess = false;

        if (kafka.getSpec() != null && kafka.getSpec().getKafka() != null && kafka.getSpec().getKafka().getListeners() != null) {
            for (GenericKafkaListener listener : kafka.getSpec().getKafka().getListeners()) {
                if (listener.getName() != null) {
                    listeners.add(listener.getName());
                }
                if (isExternalListener(listener)) {
                    externalAccess = true;
                }
            }
        }

        return new ListenerInfo(listeners, externalAccess);
    }

    private boolean isExternalListener(GenericKafkaListener listener) {
        return listener.getType() != null &&
               (listener.getType() == KafkaListenerType.NODEPORT ||
                listener.getType() == KafkaListenerType.LOADBALANCER ||
                listener.getType() == KafkaListenerType.INGRESS ||
                listener.getType() == KafkaListenerType.ROUTE);
    }

    private SecurityInfo extractSecurityInfo(Kafka kafka) {
        Boolean authenticationEnabled = false;
        Boolean authorizationEnabled = false;

        if (kafka.getSpec() != null && kafka.getSpec().getKafka() != null) {
            if (kafka.getSpec().getKafka().getListeners() != null) {
                authenticationEnabled = kafka.getSpec().getKafka().getListeners().stream()
                    .anyMatch(listener -> listener.getAuth() != null);
            }
            authorizationEnabled = kafka.getSpec().getKafka().getAuthorization() != null;
        }

        return new SecurityInfo(authenticationEnabled, authorizationEnabled);
    }

    private TimingInfo extractTimingInfo(Kafka kafka, String name) {
        Instant creationTime = null;
        Long ageMinutes = null;

        if (kafka.getMetadata().getCreationTimestamp() != null) {
            try {
                creationTime = Instant.parse(kafka.getMetadata().getCreationTimestamp());
                ageMinutes = Duration.between(creationTime, Instant.now()).toMinutes();
            } catch (DateTimeParseException e) {
                LOG.debugf("Could not parse creation timestamp for cluster %s: %s", name, e.getMessage());
            }
        }

        return new TimingInfo(creationTime, ageMinutes);
    }

    private String extractManagedBy(Kafka kafka) {
        if (kafka.getMetadata().getLabels() != null) {
            String managedBy = kafka.getMetadata().getLabels().get("app.kubernetes.io/managed-by");
            if (managedBy == null) {
                managedBy = kafka.getMetadata().getLabels().get("strimzi.io/operator");
            }
            return managedBy;
        }
        return null;
    }

    // Helper records for extracted information
    private record StorageInfo(String type, String size) { }
    private record ListenerInfo(List<String> listeners, boolean externalAccess) { }
    private record SecurityInfo(Boolean authenticationEnabled, Boolean authorizationEnabled) { }
    private record TimingInfo(Instant creationTime, Long ageMinutes) { }

    /**
     * Determine cluster status from conditions.
     */
    private String determineClusterStatus(Kafka kafka) {
        if (kafka.getStatus() == null || kafka.getStatus().getConditions() == null) {
            return "Unknown";
        }

        for (Condition condition : kafka.getStatus().getConditions()) {
            if (StrimziConstants.ConditionTypes.READY.equals(condition.getType())) {
                if (StrimziConstants.ConditionStatuses.TRUE.equals(condition.getStatus())) {
                    return "Ready";
                } else if (StrimziConstants.ConditionStatuses.FALSE.equals(condition.getStatus())) {
                    return "Error";
                } else {
                    return "NotReady";
                }
            }
        }

        return "Unknown";
    }

    /**
     * Determine storage type from storage configuration.
     */
    private String determineStorageType(Object storage) {
        if (storage == null) {
            return "unknown";
        }

        try {
            // Use reflection to get the type field - this is a simplified approach
            String storageStr = storage.toString();
            if (storageStr.contains("ephemeral")) {
                return "ephemeral";
            } else if (storageStr.contains("persistent")) {
                return "persistent-claim";
            } else if (storageStr.contains("jbod")) {
                return "jbod";
            }
        } catch (Exception e) {
            LOG.debugf("Could not determine storage type: %s", e.getMessage());
        }

        return "unknown";
    }

    /**
     * Extract storage size from storage configuration.
     */
    private String extractStorageSize(Object storage) {
        if (storage == null) {
            return null;
        }

        try {
            // This is a simplified extraction - we'd need to handle different storage types
            String storageStr = storage.toString();
            // Look for size patterns like "100Gi", "1Ti", etc.
            if (storageStr.contains("size")) {
                // This is a very basic extraction - a real implementation would properly parse the storage object
                return null; // Simplified for now
            }
        } catch (Exception e) {
            LOG.debugf("Could not extract storage size: %s", e.getMessage());
        }

        return null;
    }

    /**
     * Find Strimzi operator pods in a specific namespace.
     * Simple approach: just look for the standard operator label.
     *
     * @param namespace the namespace to search in
     * @return list of operator pods found
     */
    public List<Pod> findOperatorPods(String namespace) {
        try {
            return kubernetesClient.pods()
                .inNamespace(namespace)
                .withLabel(StrimziConstants.StrimziLabels.KIND_LABEL,
                    StrimziConstants.ComponentNames.CLUSTER_OPERATOR)
                .list()
                .getItems();
        } catch (Exception e) {
            LOG.debugf("Error finding operator pods in namespace %s: %s", namespace, e.getMessage());
            return List.of();
        }
    }
}
