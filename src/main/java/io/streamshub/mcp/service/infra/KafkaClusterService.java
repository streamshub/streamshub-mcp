/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.service.infra;

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.streamshub.mcp.config.Constants;
import io.streamshub.mcp.dto.KafkaBootstrapResponse;
import io.streamshub.mcp.dto.KafkaBootstrapResponse.BootstrapServerInfo;
import io.streamshub.mcp.dto.KafkaClusterResponse;
import io.streamshub.mcp.dto.PodSummaryResponse;
import io.streamshub.mcp.dto.ToolError;
import io.streamshub.mcp.service.common.KubernetesResourceService;
import io.streamshub.mcp.service.common.PodsService;
import io.streamshub.mcp.util.InputUtils;
import io.strimzi.api.ResourceLabels;
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
            List<Kafka> kafkaResources = discoveryService.discoverKafkaClusters(normalizedNamespace);

            List<KafkaClusterResponse> clusters = kafkaResources.stream()
                .map(this::buildKafkaClusterResponse)
                .collect(Collectors.toList());

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

        if (normalizedNamespace == null) {
            List<Kafka> discoveredClusters = discoveryService.discoverKafkaClusters(null);

            if (discoveredClusters.isEmpty()) {
                return ToolError.of("No Kafka clusters found in any namespace. Please ensure Kafka is deployed. " +
                    "You can specify a namespace explicitly: 'Show me pods in the kafka namespace'");
            }

            List<String> distinctNamespaces = discoveredClusters.stream()
                .map(kafka -> kafka.getMetadata().getNamespace())
                .distinct()
                .toList();

            if (distinctNamespaces.size() == 1) {
                normalizedNamespace = distinctNamespaces.getFirst();
                LOG.infof("Auto-discovered Kafka cluster in namespace: %s", normalizedNamespace);
            } else {
                String clusterSuggestions = discoveredClusters.stream()
                    .limit(3)
                    .map(kafka -> String.format("%s/%s", kafka.getMetadata().getNamespace(), kafka.getMetadata().getName()))
                    .collect(Collectors.joining(", "));
                return ToolError.of(String.format("Found Kafka clusters in multiple namespaces: %s. " +
                        "Please specify: 'Show me pods for %s'",
                    clusterSuggestions, String.format("%s/%s", discoveredClusters.getFirst().getMetadata().getNamespace(),
                        discoveredClusters.getFirst().getMetadata().getName())));
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

    private List<Pod> findKafkaPods(String namespace, String clusterName) {
        if (clusterName != null) {
            List<Pod> clusterPods = kubernetesClient.pods()
                .inNamespace(namespace)
                .withLabel(ResourceLabels.STRIMZI_CLUSTER_LABEL, clusterName)
                .list()
                .getItems();

            List<Pod> operatorPods = discoveryService.findOperatorPods(namespace);
            List<Pod> allPods = new ArrayList<>(clusterPods);
            allPods.addAll(operatorPods);
            return allPods;
        } else {
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

        return name.contains(Constants.Strimzi.ComponentTypes.KAFKA_LOWERCASE) || name.contains(Constants.Strimzi.ComponentTypes.ZOOKEEPER_LOWERCASE) || name.contains(Constants.Strimzi.CommonValues.STRIMZI);
    }

    private boolean hasStrimziLabels(Map<String, String> labels) {
        if (labels == null) {
            return false;
        }
        if (labels.containsKey(ResourceLabels.STRIMZI_CLUSTER_LABEL) || labels.containsKey(ResourceLabels.STRIMZI_KIND_LABEL)) {
            return true;
        }
        return labels.containsKey(Constants.Kubernetes.Labels.APP_NAME_LABEL)
            && labels.get(Constants.Kubernetes.Labels.APP_NAME_LABEL).contains(Constants.Strimzi.CommonValues.STRIMZI);
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

        if (normalizedNamespace == null) {
            List<Kafka> discoveredClusters = discoveryService.discoverKafkaClusters(null);

            if (discoveredClusters.isEmpty()) {
                return ToolError.of("No Kafka clusters found. Please ensure Kafka is deployed.");
            }

            List<String> distinctNamespaces = discoveredClusters.stream()
                .map(kafka -> kafka.getMetadata().getNamespace())
                .distinct()
                .toList();

            if (distinctNamespaces.size() == 1) {
                normalizedNamespace = distinctNamespaces.getFirst();
                LOG.infof("Auto-discovered Kafka cluster in namespace: %s", normalizedNamespace);
            } else {
                String clusterSuggestions = discoveredClusters.stream()
                    .limit(3)
                    .map(kafka -> String.format("%s/%s", kafka.getMetadata().getNamespace(), kafka.getMetadata().getName()))
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

    /**
     * Build comprehensive KafkaClusterResponse from Kafka Custom Resource.
     */
    private KafkaClusterResponse buildKafkaClusterResponse(Kafka kafka) {
        String name = kafka.getMetadata().getName();
        String namespace = kafka.getMetadata().getNamespace();
        String status = determineClusterStatus(kafka);

        String kafkaVersion = extractKafkaVersion(kafka);
        Integer replicas = extractReplicas(kafka);
        Integer readyReplicas = extractReadyReplicas(kafka);

        StorageInfo storageInfo = extractStorageInfo(kafka);
        ListenerInfo listenerInfo = extractListenerInfo(kafka);
        SecurityInfo securityInfo = extractSecurityInfo(kafka);
        TimingInfo timingInfo = extractTimingInfo(kafka, name);
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
        String clusterName = kafka.getMetadata().getName();
        String namespace = kafka.getMetadata().getNamespace();

        try {
            List<Pod> kafkaPods = kubernetesClient.pods()
                .inNamespace(namespace)
                .withLabel(ResourceLabels.STRIMZI_CLUSTER_LABEL, clusterName)
                .withLabel(ResourceLabels.STRIMZI_KIND_LABEL,
                    Constants.Strimzi.ComponentNames.KAFKA)
                .list()
                .getItems();

            return (int) kafkaPods.stream()
                .filter(pod -> pod.getStatus() != null &&
                    Constants.Kubernetes.PodPhases.RUNNING.equals(pod.getStatus().getPhase()))
                .count();
        } catch (Exception e) {
            LOG.debugf("Could not count ready replicas for cluster %s: %s",
                clusterName, e.getMessage());
            return null;
        }
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
            String managedBy = kafka.getMetadata().getLabels().get(Constants.Kubernetes.Labels.MANAGED_BY_LABEL);
            if (managedBy == null) {
                managedBy = kafka.getMetadata().getLabels().get(Constants.Strimzi.Labels.OPERATOR_LABEL);
            }
            return managedBy;
        }
        return null;
    }

    /**
     * Determine cluster status from conditions.
     */
    private String determineClusterStatus(Kafka kafka) {
        if (kafka.getStatus() == null || kafka.getStatus().getConditions() == null) {
            return Constants.Strimzi.StatusValues.UNKNOWN;
        }

        for (Condition condition : kafka.getStatus().getConditions()) {
            if (Constants.Kubernetes.ConditionTypes.READY.equals(condition.getType())) {
                if (Constants.Kubernetes.ConditionStatuses.TRUE.equals(condition.getStatus())) {
                    return Constants.Strimzi.StatusValues.READY;
                } else if (Constants.Kubernetes.ConditionStatuses.FALSE.equals(condition.getStatus())) {
                    return Constants.Strimzi.StatusValues.ERROR;
                } else {
                    return Constants.Strimzi.StatusValues.NOT_READY;
                }
            }
        }

        return Constants.Strimzi.StatusValues.UNKNOWN;
    }

    /**
     * Determine storage type from storage configuration.
     */
    private String determineStorageType(Object storage) {
        if (storage == null) {
            return Constants.Strimzi.StorageTypes.UNKNOWN;
        }

        try {
            // Use reflection to get the type field - this is a simplified approach
            String storageStr = storage.toString();
            if (storageStr.contains("ephemeral")) {
                return Constants.Strimzi.StorageTypes.EPHEMERAL;
            } else if (storageStr.contains("persistent")) {
                return Constants.Strimzi.StorageTypes.PERSISTENT_CLAIM;
            } else if (storageStr.contains("jbod")) {
                return Constants.Strimzi.StorageTypes.JBOD;
            }
        } catch (Exception e) {
            LOG.debugf("Could not determine storage type: %s", e.getMessage());
        }

        return Constants.Strimzi.StorageTypes.UNKNOWN;
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

    // Helper records for extracted information
    private record StorageInfo(String type, String size) {
    }

    private record ListenerInfo(List<String> listeners, boolean externalAccess) {
    }

    private record SecurityInfo(Boolean authenticationEnabled, Boolean authorizationEnabled) {
    }

    private record TimingInfo(Instant creationTime, Long ageMinutes) {
    }
}
