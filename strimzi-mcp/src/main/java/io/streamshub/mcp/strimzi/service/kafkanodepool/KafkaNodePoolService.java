/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.service.kafkanodepool;

import io.fabric8.kubernetes.api.model.Pod;
import io.streamshub.mcp.common.config.KubernetesConstants;
import io.streamshub.mcp.common.dto.PodSummaryResponse;
import io.streamshub.mcp.common.dto.ReconciliationInfo;
import io.streamshub.mcp.common.service.KubernetesResourceService;
import io.streamshub.mcp.common.service.PodsService;
import io.streamshub.mcp.common.util.InputUtils;
import io.streamshub.mcp.common.util.McpErrors;
import io.streamshub.mcp.strimzi.config.StrimziConstants;
import io.streamshub.mcp.strimzi.dto.kafkanodepool.KafkaNodePoolResponse;
import io.streamshub.mcp.strimzi.dto.kafkanodepool.StorageVolumeInfo;
import io.streamshub.mcp.strimzi.util.KafkaClusterChecks;
import io.streamshub.mcp.strimzi.util.KafkaStorageUtils;
import io.strimzi.api.ResourceLabels;
import io.strimzi.api.kafka.model.common.Condition;
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
            throw McpErrors.invalidParams("Cluster name is required");
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
        if (nodePools.isEmpty()) {
            KafkaClusterChecks.requireClusterExists(k8sService, ns, normalizedClusterName);
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
            throw McpErrors.invalidParams("NodePool name is required");
        }
        InputUtils.validateK8sName(nodePoolName, "node pool name");
        InputUtils.validateK8sName(ns, "namespace");

        LOG.infof("Getting node pool=%s for cluster=%s in namespace=%s",
            nodePoolName, clusterName, ns != null ? ns : "auto");

        KafkaNodePool nodePool;

        if (ns != null) {
            nodePool = k8sService.getResource(KafkaNodePool.class, ns, nodePoolName);
        } else {
            nodePool = findNodePoolInAllNamespaces(nodePoolName, clusterName);
        }

        if (nodePool == null) {
            throw McpErrors.notFound("KafkaNodePool", nodePoolName, ns);
        }

        if (clusterName != null) {
            String poolCluster = nodePool.getMetadata().getLabels() != null
                ? nodePool.getMetadata().getLabels().get(ResourceLabels.STRIMZI_CLUSTER_LABEL)
                : null;

            if (!clusterName.equals(poolCluster)) {
                throw McpErrors.invalidParams("NodePool '" + nodePoolName + "' belongs to cluster '"
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
            throw McpErrors.invalidParams("NodePool name is required");
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
            .map(pod -> podsService.extractPodSummary(finalNamespace, pod))
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
            throw McpErrors.notFound("KafkaNodePool", nodePoolName, null);
        }

        if (matchingNamespaces.size() > 1) {
            throw McpErrors.ambiguous("KafkaNodePool", nodePoolName, matchingNamespaces);
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

        Integer statusReplicas = null;
        List<String> statusRoles = null;
        List<Integer> nodeIds = null;
        boolean ready = deriveReady(nodePool);
        List<io.streamshub.mcp.common.dto.ConditionInfo> conditions = null;

        if (nodePool.getStatus() != null) {
            statusReplicas = nodePool.getStatus().getReplicas();
            if (nodePool.getStatus().getRoles() != null) {
                statusRoles = nodePool.getStatus().getRoles().stream()
                    .map(role -> role.toString().toLowerCase(Locale.ROOT))
                    .toList();
            }
            nodeIds = nodePool.getStatus().getNodeIds();
            if (nodePool.getStatus().getConditions() != null) {
                conditions = nodePool.getStatus().getConditions().stream()
                    .map(c -> io.streamshub.mcp.common.dto.ConditionInfo.of(
                        c.getType(), c.getStatus(), c.getReason(), c.getMessage(), c.getLastTransitionTime()))
                    .toList();
            }
        }

        String storageType = extractStorageType(nodePool);
        Storage storage = nodePool.getSpec().getStorage();
        String storageSize = KafkaStorageUtils.totalSize(storage);
        List<StorageVolumeInfo> volumes = KafkaStorageUtils.volumes(storage);

        ReconciliationInfo reconciliation = ReconciliationInfo.ofStatus(
            nodePool.getMetadata().getGeneration(),
            nodePool.getStatus() != null ? nodePool.getStatus().getObservedGeneration() : 0L);

        return KafkaNodePoolResponse.of(name, namespace, cluster, roles, replicas,
            statusReplicas, statusRoles, nodeIds, storageType, storageSize, volumes, ready, conditions, reconciliation);
    }

    /**
     * Derives readiness from the node pool CR, since Strimzi sets no Ready condition on it.
     * An explicit Ready or NotReady condition wins; otherwise the pool is ready when the
     * operator has observed the current generation and the status replicas match the spec.
     * Warning conditions and pod readiness are ignored.
     */
    private static boolean deriveReady(final KafkaNodePool nodePool) {
        if (nodePool.getStatus() == null) {
            return false;
        }
        List<Condition> conditions = nodePool.getStatus().getConditions() != null
            ? nodePool.getStatus().getConditions() : List.of();
        for (Condition c : conditions) {
            if (KubernetesConstants.Conditions.TYPE_READY.equals(c.getType())) {
                return KubernetesConstants.Conditions.STATUS_TRUE.equals(c.getStatus());
            }
        }
        for (Condition c : conditions) {
            if (KubernetesConstants.Conditions.TYPE_NOT_READY.equals(c.getType())
                && KubernetesConstants.Conditions.STATUS_TRUE.equals(c.getStatus())) {
                return false;
            }
        }
        Long generation = nodePool.getMetadata().getGeneration();
        Long observed = nodePool.getStatus().getObservedGeneration();
        if (observed != null && observed > 0 && generation != null && !observed.equals(generation)) {
            return false;
        }
        return Objects.equals(nodePool.getStatus().getReplicas(), nodePool.getSpec().getReplicas());
    }

    private String extractStorageType(final KafkaNodePool nodePool) {
        if (nodePool.getSpec().getStorage() != null) {
            return nodePool.getSpec().getStorage().getType();
        }
        return KubernetesConstants.UNKNOWN;
    }
}
