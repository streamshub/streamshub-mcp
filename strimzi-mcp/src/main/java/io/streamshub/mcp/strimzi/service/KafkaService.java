/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.service;

import io.fabric8.kubernetes.api.model.Pod;
import io.quarkiverse.mcp.server.ToolCallException;
import io.streamshub.mcp.common.config.KubernetesConstants;
import io.streamshub.mcp.common.dto.PodLogsResult;
import io.streamshub.mcp.common.dto.PodSummaryResponse;
import io.streamshub.mcp.common.service.KubernetesResourceService;
import io.streamshub.mcp.common.service.PodsService;
import io.streamshub.mcp.common.util.InputUtils;
import io.streamshub.mcp.strimzi.config.StrimziConstants;
import io.streamshub.mcp.strimzi.dto.KafkaBootstrapResponse;
import io.streamshub.mcp.strimzi.dto.KafkaClusterLogsResponse;
import io.streamshub.mcp.strimzi.dto.KafkaClusterPodsResponse;
import io.streamshub.mcp.strimzi.dto.KafkaClusterResponse;
import io.streamshub.mcp.strimzi.dto.KafkaNodePoolResponse;
import io.strimzi.api.ResourceLabels;
import io.strimzi.api.kafka.model.common.Condition;
import io.strimzi.api.kafka.model.kafka.Kafka;
import io.strimzi.api.kafka.model.kafka.listener.GenericKafkaListener;
import io.strimzi.api.kafka.model.kafka.listener.KafkaListenerType;
import io.strimzi.api.kafka.model.nodepool.ProcessRoles;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Service for Kafka cluster operations.
 */
@ApplicationScoped
public class KafkaService {

    private static final Logger LOG = Logger.getLogger(KafkaService.class);

    @Inject
    KubernetesResourceService k8sService;

    @Inject
    PodsService podsService;

    @Inject
    KafkaNodePoolService nodePoolService;

    KafkaService() {
    }

    /**
     * List Kafka clusters, optionally filtered by namespace.
     *
     * @param namespace the namespace to search in, or null for all namespaces
     * @return list of cluster responses
     */
    public List<KafkaClusterResponse> listClusters(final String namespace) {
        String ns = InputUtils.normalizeInput(namespace);
        LOG.infof("Listing Kafka clusters (namespace=%s)", ns != null ? ns : "all");

        List<Kafka> kafkas;
        if (ns != null) {
            kafkas = k8sService.queryResources(Kafka.class, ns);
        } else {
            kafkas = k8sService.queryResourcesInAnyNamespace(Kafka.class);
        }

        return kafkas.stream()
            .map(this::createClusterResponse)
            .toList();
    }

    /**
     * Get a specific Kafka cluster by name.
     * If namespace is null, auto-discovers across all namespaces.
     *
     * @param namespace the namespace, or null for auto-discovery
     * @param name      the cluster name
     * @return the cluster response
     */
    public KafkaClusterResponse getCluster(final String namespace, final String name) {
        String ns = InputUtils.normalizeInput(namespace);
        String normalizedName = InputUtils.normalizeInput(name);

        if (normalizedName == null) {
            throw new ToolCallException("Cluster name is required");
        }

        LOG.infof("Getting Kafka cluster name=%s (namespace=%s)", normalizedName, ns != null ? ns : "auto");

        Kafka kafka;
        if (ns != null) {
            kafka = k8sService.getResource(Kafka.class, ns, normalizedName);
        } else {
            kafka = findClusterInAllNamespaces(normalizedName);
        }

        if (kafka == null) {
            if (ns != null) {
                throw new ToolCallException(
                    "Kafka cluster '" + normalizedName + "' not found in namespace " + ns);
            } else {
                throw new ToolCallException(
                    "Kafka cluster '" + normalizedName + "' not found in any namespace");
            }
        }

        return createClusterResponse(kafka);
    }

    /**
     * Get pods for a specific Kafka cluster.
     *
     * @param namespace   the namespace, or null for auto-discovery
     * @param clusterName the cluster name
     * @return the cluster pods response
     */
    public KafkaClusterPodsResponse getClusterPods(final String namespace, final String clusterName) {
        String ns = InputUtils.normalizeInput(namespace);
        String normalizedClusterName = InputUtils.normalizeInput(clusterName);

        if (normalizedClusterName == null) {
            throw new ToolCallException("Cluster name is required");
        }

        LOG.infof("Getting pods for cluster=%s in namespace=%s", normalizedClusterName, ns != null ? ns : "auto");

        if (ns == null) {
            ns = discoverClusterNamespace(normalizedClusterName);
        }

        final String finalNamespace = ns;
        List<Pod> pods = k8sService.queryResourcesByLabel(
            Pod.class, finalNamespace, ResourceLabels.STRIMZI_CLUSTER_LABEL, normalizedClusterName);

        if (pods.isEmpty()) {
            return KafkaClusterPodsResponse.empty(normalizedClusterName, finalNamespace);
        }

        List<PodSummaryResponse.PodInfo> podInfos = pods.stream()
            .map(pod -> podsService.extractPodSummary(finalNamespace, pod))
            .toList();

        PodSummaryResponse podSummary = PodSummaryResponse.of(finalNamespace, podInfos);
        return KafkaClusterPodsResponse.of(normalizedClusterName, finalNamespace, podSummary);
    }

    /**
     * Get bootstrap servers for a Kafka cluster.
     *
     * @param namespace   the namespace, or null for auto-discovery
     * @param clusterName the cluster name
     * @return the bootstrap response
     */
    public KafkaBootstrapResponse getBootstrapServers(final String namespace, final String clusterName) {
        String ns = InputUtils.normalizeInput(namespace);
        String normalizedName = InputUtils.normalizeInput(clusterName);

        if (normalizedName == null) {
            throw new ToolCallException("Cluster name is required");
        }

        LOG.infof("Getting bootstrap servers for cluster=%s (namespace=%s)",
            normalizedName, ns != null ? ns : "auto");

        Kafka kafka;
        if (ns != null) {
            kafka = k8sService.getResource(Kafka.class, ns, normalizedName);
        } else {
            kafka = findClusterInAllNamespaces(normalizedName);
        }

        if (kafka == null) {
            if (ns != null) {
                throw new ToolCallException(
                    "Kafka cluster '" + normalizedName + "' not found in namespace " + ns);
            } else {
                throw new ToolCallException(
                    "Kafka cluster '" + normalizedName + "' not found in any namespace");
            }
        }

        String resolvedNs = kafka.getMetadata().getNamespace();
        List<KafkaBootstrapResponse.BootstrapServerInfo> servers = extractBootstrapServers(kafka);

        if (servers.isEmpty()) {
            return KafkaBootstrapResponse.empty(resolvedNs, normalizedName);
        }

        return KafkaBootstrapResponse.of(resolvedNs, normalizedName, servers);
    }

    /**
     * Get logs from Kafka cluster pods with optional filtering.
     *
     * @param namespace   the namespace, or null for auto-discovery
     * @param clusterName the cluster name
     * @param filter      optional log filter: "errors", "warnings", or a regex pattern
     * @return the cluster logs response
     */
    public KafkaClusterLogsResponse getClusterLogs(final String namespace, final String clusterName,
                                                   final String filter) {
        String ns = InputUtils.normalizeInput(namespace);
        String normalizedName = InputUtils.normalizeInput(clusterName);

        if (normalizedName == null) {
            throw new ToolCallException("Cluster name is required");
        }

        LOG.infof("Getting logs for cluster=%s (namespace=%s, filter=%s)",
            normalizedName, ns != null ? ns : "auto", filter != null ? filter : "none");

        if (ns == null) {
            ns = discoverClusterNamespace(normalizedName);
        }

        List<Pod> pods = k8sService.queryResourcesByLabel(
            Pod.class, ns, ResourceLabels.STRIMZI_CLUSTER_LABEL, normalizedName);

        if (pods.isEmpty()) {
            return KafkaClusterLogsResponse.empty(normalizedName, ns);
        }

        String normalizedFilter = InputUtils.normalizeInput(filter);
        PodLogsResult result = podsService.collectLogs(ns, pods, normalizedFilter);
        return KafkaClusterLogsResponse.of(normalizedName, ns, result.podNames(),
            result.hasErrors(), result.errorCount(), result.totalLines(), result.logs());
    }

    private List<KafkaBootstrapResponse.BootstrapServerInfo> extractBootstrapServers(final Kafka kafka) {
        if (kafka.getStatus() == null || kafka.getStatus().getListeners() == null) {
            return List.of();
        }

        Map<String, String> listenerTypesByName = buildListenerTypeMap(kafka);

        return kafka.getStatus().getListeners().stream()
            .filter(listener -> listener.getAddresses() != null)
            .flatMap(listener -> listener.getAddresses().stream()
                .filter(addr -> addr.getHost() != null && addr.getPort() != null)
                .map(addr -> new KafkaBootstrapResponse.BootstrapServerInfo(
                    addr.getHost(),
                    addr.getPort(),
                    listener.getName(),
                    listenerTypesByName.getOrDefault(listener.getName(), KubernetesConstants.UNKNOWN),
                    String.format("%s:%d", addr.getHost(), addr.getPort())
                )))
            .toList();
    }

    private Map<String, String> buildListenerTypeMap(final Kafka kafka) {
        if (kafka.getSpec() == null || kafka.getSpec().getKafka() == null
            || kafka.getSpec().getKafka().getListeners() == null) {
            return Map.of();
        }
        return kafka.getSpec().getKafka().getListeners().stream()
            .filter(l -> l.getName() != null && l.getType() != null)
            .collect(Collectors.toMap(
                GenericKafkaListener::getName,
                l -> l.getType().toValue(),
                (a, b) -> a));
    }

    private String discoverClusterNamespace(final String clusterName) {
        List<Kafka> allClusters = k8sService.queryResourcesInAnyNamespace(Kafka.class);
        List<String> matchingNamespaces = allClusters.stream()
            .filter(kafka -> clusterName.equals(kafka.getMetadata().getName()))
            .map(kafka -> kafka.getMetadata().getNamespace())
            .distinct()
            .toList();

        if (matchingNamespaces.isEmpty()) {
            throw new ToolCallException("No Kafka cluster named '" + clusterName + "' found in any namespace");
        }

        if (matchingNamespaces.size() > 1) {
            throw new ToolCallException("Multiple clusters named '" + clusterName + "' found in namespaces: "
                + String.join(", ", matchingNamespaces) + ". Please specify namespace.");
        }

        return matchingNamespaces.getFirst();
    }

    private KafkaClusterResponse createClusterResponse(final Kafka kafka) {
        String name = kafka.getMetadata().getName();
        String namespace = kafka.getMetadata().getNamespace();

        String status;
        if (kafka.getStatus() != null) {
            status = determineResourceStatus(kafka.getStatus().getConditions());
        } else {
            status = KubernetesConstants.ResourceStatus.UNKNOWN;
        }

        String version = extractVersion(kafka);
        Instant creationTime = extractCreationTime(kafka);
        Long ageMinutes = null;
        if (creationTime != null) {
            ageMinutes = Duration.between(creationTime, Instant.now()).toMinutes();
        }

        return new KafkaClusterResponse(
            name, namespace, status, version,
            extractReplicas(kafka), extractReadyReplicas(kafka),
            extractStorageType(kafka), extractStorageSize(kafka),
            extractListeners(kafka), extractExternalAccess(kafka),
            extractAuthenticationEnabled(kafka), extractAuthorizationEnabled(kafka),
            creationTime, ageMinutes, extractManagedBy(kafka));
    }

    private String determineResourceStatus(final List<Condition> conditions) {
        if (conditions == null || conditions.isEmpty()) {
            return KubernetesConstants.ResourceStatus.UNKNOWN;
        }
        boolean ready = conditions.stream().anyMatch(c ->
            KubernetesConstants.Conditions.TYPE_READY.equals(c.getType())
                && KubernetesConstants.Conditions.STATUS_TRUE.equals(c.getStatus()));
        if (ready) {
            return KubernetesConstants.ResourceStatus.READY;
        }

        boolean hasError = conditions.stream().anyMatch(c ->
            KubernetesConstants.Conditions.TYPE_READY.equals(c.getType())
                && KubernetesConstants.Conditions.STATUS_FALSE.equals(c.getStatus()));
        return hasError ? KubernetesConstants.ResourceStatus.ERROR : KubernetesConstants.ResourceStatus.NOT_READY;
    }

    private String extractVersion(final Kafka kafka) {
        if (kafka.getStatus() != null && kafka.getStatus().getKafkaVersion() != null) {
            return kafka.getStatus().getKafkaVersion();
        }
        return null;
    }

    private Instant extractCreationTime(final Kafka kafka) {
        String ts = kafka.getMetadata().getCreationTimestamp();
        if (ts != null) {
            try {
                return Instant.parse(ts);
            } catch (DateTimeParseException e) {
                LOG.debugf("Could not parse creation timestamp: %s", e.getMessage());
            }
        }
        return null;
    }

    private String extractManagedBy(final Kafka kafka) {
        Map<String, String> labels = kafka.getMetadata().getLabels();
        return labels != null ? labels.get(KubernetesConstants.Labels.MANAGED_BY) : null;
    }

    private Integer extractReplicas(final Kafka kafka) {
        String clusterName = kafka.getMetadata().getName();
        String namespace = kafka.getMetadata().getNamespace();

        try {
            List<KafkaNodePoolResponse> nodePools = nodePoolService.listNodePools(namespace, clusterName);
            return nodePools.stream()
                .mapToInt(pool -> pool.replicas() != null ? pool.replicas() : 0)
                .sum();
        } catch (Exception e) {
            LOG.debugf("Could not get replicas from NodePools for cluster %s: %s", clusterName, e.getMessage());
        }
        return null;
    }

    private Integer extractReadyReplicas(final Kafka kafka) {
        String clusterName = kafka.getMetadata().getName();
        String namespace = kafka.getMetadata().getNamespace();

        try {
            List<Pod> kafkaPods = k8sService.queryResourcesByLabel(
                    Pod.class, namespace, ResourceLabels.STRIMZI_CLUSTER_LABEL, clusterName)
                .stream()
                .filter(pod -> pod.getMetadata().getLabels() != null
                    && StrimziConstants.ComponentTypes.KAFKA.equals(
                    pod.getMetadata().getLabels().get(ResourceLabels.STRIMZI_COMPONENT_TYPE_LABEL)))
                .toList();

            return (int) kafkaPods.stream()
                .filter(pod -> pod.getStatus() != null
                    && KubernetesConstants.PodPhases.RUNNING.equals(pod.getStatus().getPhase())
                    && pod.getStatus().getConditions() != null
                    && pod.getStatus().getConditions().stream()
                    .anyMatch(cond -> KubernetesConstants.Conditions.TYPE_READY.equals(cond.getType())
                        && KubernetesConstants.Conditions.STATUS_TRUE.equals(cond.getStatus())))
                .count();
        } catch (Exception e) {
            LOG.debugf("Could not count ready replicas for cluster %s: %s", clusterName, e.getMessage());
            return null;
        }
    }

    private String extractStorageType(final Kafka kafka) {
        String clusterName = kafka.getMetadata().getName();
        String namespace = kafka.getMetadata().getNamespace();

        try {
            List<KafkaNodePoolResponse> nodePools = nodePoolService.listNodePools(namespace, clusterName);
            return nodePools.stream()
                .filter(pool -> pool.roles().contains(ProcessRoles.BROKER.toValue()))
                .map(KafkaNodePoolResponse::storageType)
                .filter(type -> type != null && !type.equals(KubernetesConstants.UNKNOWN))
                .findFirst()
                .orElse(null);
        } catch (Exception e) {
            LOG.debugf("Could not get storage type from NodePools for cluster %s: %s", clusterName, e.getMessage());
        }
        return null;
    }

    private String extractStorageSize(final Kafka kafka) {
        String clusterName = kafka.getMetadata().getName();
        String namespace = kafka.getMetadata().getNamespace();

        try {
            List<KafkaNodePoolResponse> nodePools = nodePoolService.listNodePools(namespace, clusterName);
            return nodePools.stream()
                .filter(pool -> pool.roles().contains(ProcessRoles.BROKER.toValue()))
                .map(KafkaNodePoolResponse::storageSize)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
        } catch (Exception e) {
            LOG.debugf("Could not get storage size from NodePools for cluster %s: %s", clusterName, e.getMessage());
        }
        return null;
    }

    private List<String> extractListeners(final Kafka kafka) {
        if (kafka.getSpec() != null && kafka.getSpec().getKafka() != null
            && kafka.getSpec().getKafka().getListeners() != null) {
            return kafka.getSpec().getKafka().getListeners().stream()
                .map(GenericKafkaListener::getName)
                .toList();
        }
        return null;
    }

    private boolean extractExternalAccess(final Kafka kafka) {
        if (kafka.getSpec() != null && kafka.getSpec().getKafka() != null
            && kafka.getSpec().getKafka().getListeners() != null) {
            return kafka.getSpec().getKafka().getListeners().stream()
                .anyMatch(listener -> {
                    if (listener.getType() == null) {
                        return false;
                    }
                    String type = listener.getType().toValue();
                    return !KafkaListenerType.INTERNAL.toValue().equals(type)
                        && !KafkaListenerType.CLUSTER_IP.toValue().equals(type);
                });
        }
        return false;
    }

    private boolean extractAuthenticationEnabled(final Kafka kafka) {
        if (kafka.getSpec() != null && kafka.getSpec().getKafka() != null
            && kafka.getSpec().getKafka().getListeners() != null) {
            return kafka.getSpec().getKafka().getListeners().stream()
                .anyMatch(listener -> listener.getAuth() != null);
        }
        return false;
    }

    private boolean extractAuthorizationEnabled(final Kafka kafka) {
        return kafka.getSpec() != null && kafka.getSpec().getKafka() != null
            && kafka.getSpec().getKafka().getAuthorization() != null;
    }

    private Kafka findClusterInAllNamespaces(final String clusterName) {
        List<Kafka> allClusters = k8sService.queryResourcesInAnyNamespace(Kafka.class);

        for (Kafka kafka : allClusters) {
            if (clusterName.equals(kafka.getMetadata().getName())) {
                LOG.debugf("Discovered cluster %s in namespace %s",
                    clusterName, kafka.getMetadata().getNamespace());
                return kafka;
            }
        }

        return null;
    }

}
