/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.service;

import io.fabric8.kubernetes.api.model.PersistentVolumeClaim;
import io.fabric8.kubernetes.api.model.Pod;
import io.quarkiverse.mcp.server.ToolCallException;
import io.streamshub.mcp.common.dto.ResourceEventsResult;
import io.streamshub.mcp.common.service.KubernetesEventsService;
import io.streamshub.mcp.common.service.KubernetesResourceService;
import io.streamshub.mcp.common.util.InputUtils;
import io.streamshub.mcp.strimzi.dto.StrimziEventsResponse;
import io.strimzi.api.ResourceLabels;
import io.strimzi.api.kafka.model.kafka.Kafka;
import io.strimzi.api.kafka.model.nodepool.KafkaNodePool;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Service for gathering Kubernetes events across a Strimzi Kafka cluster's
 * resource tree (Kafka CR, pods, PVCs, node pools).
 *
 * <p>Delegates generic event querying to {@link KubernetesEventsService}
 * and adds Strimzi-specific orchestration: resolving the cluster,
 * finding related resources by {@code strimzi.io/cluster} label.</p>
 */
@ApplicationScoped
public class StrimziEventsService {

    private static final Logger LOG = Logger.getLogger(StrimziEventsService.class);
    private static final int SECONDS_PER_MINUTE = 60;

    @Inject
    KafkaService kafkaService;

    @Inject
    KubernetesResourceService k8sService;

    @Inject
    KubernetesEventsService eventsService;

    @ConfigProperty(name = "mcp.events.max-related-resources", defaultValue = "50")
    int maxRelatedResources;

    StrimziEventsService() {
    }

    /**
     * Get events for a Strimzi Kafka cluster and all related resources.
     * Gathers events from the Kafka CR, pods, PVCs, and node pools
     * labeled with {@code strimzi.io/cluster}.
     *
     * @param namespace    the namespace, or null for auto-discovery
     * @param clusterName  the Kafka cluster name
     * @param sinceMinutes only return events newer than this many minutes, or null for all
     * @return events grouped by resource
     */
    public StrimziEventsResponse getClusterEvents(final String namespace, final String clusterName,
                                                   final Integer sinceMinutes) {
        String ns = InputUtils.normalizeInput(namespace);
        String name = InputUtils.normalizeInput(clusterName);

        if (name == null) {
            throw new ToolCallException("Cluster name is required");
        }

        LOG.infof("Getting events for cluster=%s (namespace=%s, sinceMinutes=%s)",
            name, ns != null ? ns : "auto", sinceMinutes);

        Kafka kafka = kafkaService.findKafkaCluster(ns, name);
        String resolvedNs = kafka.getMetadata().getNamespace();

        Instant sinceTime = sinceMinutes != null
            ? Instant.now().minusSeconds((long) sinceMinutes * SECONDS_PER_MINUTE)
            : null;

        List<ResourceEventsResult> resourceEvents = new ArrayList<>();

        // Events for the Kafka CR itself
        resourceEvents.add(eventsService.getEventsForResource(resolvedNs, name, "Kafka", sinceTime));

        // Events for related pods (limited to avoid excessive API calls on large clusters)
        List<Pod> pods = k8sService.queryResourcesByLabel(
            Pod.class, resolvedNs, ResourceLabels.STRIMZI_CLUSTER_LABEL, name);
        if (pods.size() > maxRelatedResources) {
            LOG.warnf("Cluster %s has %d pods, limiting event queries to first %d",
                name, pods.size(), maxRelatedResources);
            pods = pods.subList(0, maxRelatedResources);
        }
        for (Pod pod : pods) {
            resourceEvents.add(eventsService.getEventsForResource(
                resolvedNs, pod.getMetadata().getName(), "Pod", sinceTime));
        }

        // Events for related PVCs
        List<PersistentVolumeClaim> pvcs = k8sService.queryResourcesByLabel(
            PersistentVolumeClaim.class, resolvedNs, ResourceLabels.STRIMZI_CLUSTER_LABEL, name);
        if (pvcs.size() > maxRelatedResources) {
            LOG.warnf("Cluster %s has %d PVCs, limiting event queries to first %d",
                name, pvcs.size(), maxRelatedResources);
            pvcs = pvcs.subList(0, maxRelatedResources);
        }
        for (PersistentVolumeClaim pvc : pvcs) {
            resourceEvents.add(eventsService.getEventsForResource(
                resolvedNs, pvc.getMetadata().getName(), "PersistentVolumeClaim", sinceTime));
        }

        // Events for related KafkaNodePools
        List<KafkaNodePool> nodePools = k8sService.queryResourcesByLabel(
            KafkaNodePool.class, resolvedNs, ResourceLabels.STRIMZI_CLUSTER_LABEL, name);
        for (KafkaNodePool nodePool : nodePools) {
            resourceEvents.add(eventsService.getEventsForResource(
                resolvedNs, nodePool.getMetadata().getName(), "KafkaNodePool", sinceTime));
        }

        // Remove resources with no events
        List<ResourceEventsResult> nonEmpty = resourceEvents.stream()
            .filter(r -> !r.events().isEmpty())
            .toList();

        return StrimziEventsResponse.of(name, resolvedNs, nonEmpty);
    }
}
