/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.service;

import io.fabric8.kubernetes.api.model.Pod;
import io.quarkiverse.mcp.server.ToolCallException;
import io.streamshub.mcp.common.config.KubernetesConstants;
import io.streamshub.mcp.common.dto.PodSummaryResponse;
import io.streamshub.mcp.common.service.KubernetesResourceService;
import io.streamshub.mcp.common.service.PodsService;
import io.streamshub.mcp.common.util.InputUtils;
import io.streamshub.mcp.strimzi.config.StrimziConstants;
import io.streamshub.mcp.strimzi.dto.KafkaNodePoolResponse;
import io.strimzi.api.ResourceLabels;
import io.strimzi.api.kafka.model.kafka.JbodStorage;
import io.strimzi.api.kafka.model.kafka.PersistentClaimStorage;
import io.strimzi.api.kafka.model.kafka.Storage;
import io.strimzi.api.kafka.model.nodepool.KafkaNodePool;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Service for KafkaNodePool operations.
 */
@ApplicationScoped
public class KafkaNodePoolService {

    private static final Logger LOG = Logger.getLogger(KafkaNodePoolService.class);

    @Inject
    KubernetesResourceService k8sService;

    @Inject
    PodsService podsService;

    KafkaNodePoolService() {
    }

    /**
     * List KafkaNodePools for a specific cluster, optionally filtered by namespace.
     *
     * @param namespace   the namespace, or null
     * @param clusterName the cluster name
     * @return list of node pool responses
     */
    public List<KafkaNodePoolResponse> listNodePools(final String namespace, final String clusterName) {
        String ns = InputUtils.normalizeInput(namespace);
        String normalizedClusterName = InputUtils.normalizeInput(clusterName);

        if (normalizedClusterName == null) {
            throw new ToolCallException("Cluster name is required");
        }

        LOG.infof("Listing node pools for cluster=%s (namespace=%s)", normalizedClusterName, ns != null ? ns : "all");

        List<KafkaNodePool> nodePools;
        if (ns != null) {
            nodePools = k8sService.queryResourcesByLabel(
                KafkaNodePool.class, ns, ResourceLabels.STRIMZI_CLUSTER_LABEL, normalizedClusterName);
        } else {
            nodePools = k8sService.queryResourcesByLabelInAnyNamespace(
                KafkaNodePool.class, ResourceLabels.STRIMZI_CLUSTER_LABEL, normalizedClusterName);
        }

        return nodePools.stream()
            .map(this::createNodePoolResponse)
            .toList();
    }

    /**
     * Get specific KafkaNodePool details.
     *
     * @param namespace    the namespace
     * @param clusterName  the cluster name
     * @param nodePoolName the node pool name
     * @return the node pool response
     */
    public KafkaNodePoolResponse getNodePool(final String namespace, final String clusterName,
                                             final String nodePoolName) {
        String ns = InputUtils.normalizeInput(namespace);

        if (nodePoolName == null) {
            throw new ToolCallException("NodePool name is required");
        }

        LOG.infof("Getting node pool=%s for cluster=%s in namespace=%s",
            nodePoolName, clusterName, ns != null ? ns : "auto");

        KafkaNodePool nodePool;

        if (ns != null) {
            nodePool = k8sService.getResource(KafkaNodePool.class, ns, nodePoolName);
        } else {
            nodePool = findNodePoolInAllNamespaces(nodePoolName, clusterName);
        }

        if (nodePool == null) {
            throw new ToolCallException("KafkaNodePool '" + nodePoolName + "' not found");
        }

        if (clusterName != null) {
            String poolCluster = nodePool.getMetadata().getLabels() != null
                ? nodePool.getMetadata().getLabels().get(ResourceLabels.STRIMZI_CLUSTER_LABEL)
                : null;

            if (!clusterName.equals(poolCluster)) {
                throw new ToolCallException("NodePool '" + nodePoolName + "' belongs to cluster '"
                    + poolCluster + "', not '" + clusterName + "'");
            }
        }

        return createNodePoolResponse(nodePool);
    }

    /**
     * Get pods for a specific KafkaNodePool.
     *
     * @param namespace    the namespace
     * @param clusterName  the cluster name
     * @param nodePoolName the node pool name
     * @return list of pod info summaries
     */
    public List<PodSummaryResponse.PodInfo> getNodePoolPods(final String namespace, final String clusterName,
                                                            final String nodePoolName) {
        String ns = InputUtils.normalizeInput(namespace);

        if (nodePoolName == null) {
            throw new ToolCallException("NodePool name is required");
        }

        LOG.infof("Getting pods for node pool=%s cluster=%s in namespace=%s",
            nodePoolName, clusterName, ns != null ? ns : "auto");

        if (ns == null) {
            ns = discoverNodePoolNamespace(nodePoolName, clusterName);
        }

        final String finalNamespace = ns;
        List<Pod> pods = k8sService.queryResourcesByLabel(
            Pod.class, finalNamespace, StrimziConstants.Labels.POOL_NAME, nodePoolName);

        return pods.stream()
            .map(pod -> {
                PodSummaryResponse.PodInfo info = podsService.extractPodSummary(finalNamespace, pod);
                return PodSummaryResponse.PodInfo.enrichedSummary(
                    info.name(), info.phase(), info.ready(), info.component(),
                    info.restarts(), info.ageMinutes(), nodePoolName,
                    info.lastTerminationReason(), info.lastTerminationTime(), info.resources());
            })
            .toList();
    }

    private KafkaNodePool findNodePoolInAllNamespaces(final String nodePoolName, final String clusterName) {
        List<KafkaNodePool> allNodePools = k8sService.queryResourcesInAnyNamespace(KafkaNodePool.class);

        for (KafkaNodePool nodePool : allNodePools) {
            if (nodePoolName.equals(nodePool.getMetadata().getName())) {
                if (clusterName != null) {
                    String poolCluster = nodePool.getMetadata().getLabels() != null
                        ? nodePool.getMetadata().getLabels().get(ResourceLabels.STRIMZI_CLUSTER_LABEL)
                        : null;

                    if (!clusterName.equals(poolCluster)) {
                        continue;
                    }
                }

                LOG.debugf("Discovered node pool %s in namespace %s",
                    nodePoolName, nodePool.getMetadata().getNamespace());
                return nodePool;
            }
        }

        return null;
    }

    private String discoverNodePoolNamespace(final String nodePoolName, final String clusterName) {
        List<KafkaNodePool> allNodePools = k8sService.queryResourcesInAnyNamespace(KafkaNodePool.class);
        List<String> matchingNamespaces = allNodePools.stream()
            .filter(pool -> nodePoolName.equals(pool.getMetadata().getName()))
            .filter(pool -> clusterName == null || clusterName.equals(
                pool.getMetadata().getLabels() != null
                    ? pool.getMetadata().getLabels().get(ResourceLabels.STRIMZI_CLUSTER_LABEL)
                    : null))
            .map(pool -> pool.getMetadata().getNamespace())
            .distinct()
            .toList();

        if (matchingNamespaces.isEmpty()) {
            throw new ToolCallException("No NodePool named '" + nodePoolName + "' found");
        }

        if (matchingNamespaces.size() > 1) {
            throw new ToolCallException("Multiple NodePools named '" + nodePoolName + "' found in namespaces: "
                + String.join(", ", matchingNamespaces) + ". Please specify namespace.");
        }

        return matchingNamespaces.getFirst();
    }

    private KafkaNodePoolResponse createNodePoolResponse(final KafkaNodePool nodePool) {
        String name = nodePool.getMetadata().getName();
        String namespace = nodePool.getMetadata().getNamespace();
        String cluster = nodePool.getMetadata().getLabels() != null
            ? nodePool.getMetadata().getLabels().get(ResourceLabels.STRIMZI_CLUSTER_LABEL)
            : KubernetesConstants.UNKNOWN;

        List<String> roles = nodePool.getSpec().getRoles().stream()
            .map(role -> role.toString().toLowerCase(Locale.ROOT))
            .toList();
        Integer replicas = nodePool.getSpec().getReplicas();

        String storageType = extractStorageType(nodePool);
        String storageSize = extractStorageSize(nodePool);

        return new KafkaNodePoolResponse(name, namespace, cluster, roles, replicas, storageType, storageSize);
    }

    private String extractStorageType(final KafkaNodePool nodePool) {
        if (nodePool.getSpec().getStorage() != null) {
            return nodePool.getSpec().getStorage().getType();
        }
        return KubernetesConstants.UNKNOWN;
    }

    private String extractStorageSize(final KafkaNodePool nodePool) {
        Storage storage = nodePool.getSpec().getStorage();
        return switch (storage) {
            case PersistentClaimStorage pcs -> pcs.getSize();
            case JbodStorage jbod when jbod.getVolumes() != null -> jbod.getVolumes().stream()
                .filter(PersistentClaimStorage.class::isInstance)
                .map(PersistentClaimStorage.class::cast)
                .map(PersistentClaimStorage::getSize)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
            case null, default -> null;
        };
    }

}
