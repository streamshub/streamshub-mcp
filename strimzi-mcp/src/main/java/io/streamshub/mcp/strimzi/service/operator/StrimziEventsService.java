/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.service.operator;

import io.fabric8.kubernetes.api.model.PersistentVolumeClaim;
import io.fabric8.kubernetes.api.model.Pod;
import io.quarkiverse.mcp.server.ToolCallException;
import io.streamshub.mcp.common.dto.ResourceEventsResult;
import io.streamshub.mcp.common.service.KubernetesEventsService;
import io.streamshub.mcp.common.service.KubernetesQueryException;
import io.streamshub.mcp.common.service.KubernetesResourceService;
import io.streamshub.mcp.common.util.InputUtils;
import io.streamshub.mcp.strimzi.dto.operator.StrimziEventsResponse;
import io.streamshub.mcp.strimzi.service.kafka.KafkaService;
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
import java.util.Map;
import java.util.Set;
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
    private static final Set<String> SUPPORTED_RESOURCE_KINDS =
        Set.of("KafkaConnect", "KafkaMirrorMaker2", "KafkaBridge");

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
        InputUtils.validateK8sName(name, "cluster name");
        InputUtils.validateK8sName(ns, "namespace");

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
        try {
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
        } catch (KubernetesQueryException e) {
            LOG.warnf("Failed to query pods for event collection: %s", e.getMessage());
        }

        // Events for related PVCs
        try {
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
        } catch (KubernetesQueryException e) {
            LOG.warnf("Failed to query PVCs for event collection: %s", e.getMessage());
        }

        // Events for related KafkaNodePools
        try {
            List<KafkaNodePool> nodePools = k8sService.queryResourcesByLabel(
                KafkaNodePool.class, resolvedNs, ResourceLabels.STRIMZI_CLUSTER_LABEL, name);
            if (nodePools.size() > maxRelatedResources) {
                LOG.warnf("Cluster %s has %d node pools, limiting event queries to first %d",
                    name, nodePools.size(), maxRelatedResources);
                nodePools = nodePools.subList(0, maxRelatedResources);
            }
            for (KafkaNodePool nodePool : nodePools) {
                resourceEvents.add(eventsService.getEventsForResource(
                    resolvedNs, nodePool.getMetadata().getName(), "KafkaNodePool", sinceTime));
            }
        } catch (KubernetesQueryException e) {
            LOG.warnf("Failed to query KafkaNodePools for event collection: %s", e.getMessage());
        }

        // Remove resources with no events
        List<ResourceEventsResult> nonEmpty = resourceEvents.stream()
            .filter(r -> !r.events().isEmpty())
            .toList();

        return StrimziEventsResponse.of(name, resolvedNs, nonEmpty);
    }

    /**
     * Get events for a Strimzi resource (KafkaConnect, KafkaMirrorMaker2, KafkaBridge)
     * and its related pods. Unlike {@link #getClusterEvents}, this method does not
     * look up a Kafka CR — it queries pods directly using both
     * {@code strimzi.io/cluster} and {@code strimzi.io/kind} labels.
     *
     * @param namespace    the namespace (required — caller must resolve beforehand)
     * @param resourceName the CR name (e.g., "my-connect")
     * @param resourceKind the Strimzi kind (e.g., "KafkaConnect")
     * @param sinceMinutes only return events newer than this many minutes, or null for all
     * @return events grouped by resource
     */
    public StrimziEventsResponse getResourceEvents(final String namespace, final String resourceName,
                                                    final String resourceKind,
                                                    final Integer sinceMinutes) {
        String ns = InputUtils.normalizeInput(namespace);
        String name = InputUtils.normalizeInput(resourceName);
        String kind = resourceKind != null ? resourceKind.trim() : null;

        if (name == null) {
            throw new ToolCallException("Resource name is required");
        }
        if (ns == null) {
            throw new ToolCallException("Namespace is required when querying events by resource kind");
        }
        if (kind == null || !SUPPORTED_RESOURCE_KINDS.contains(kind)) {
            throw new ToolCallException("resource_kind must be one of: " + SUPPORTED_RESOURCE_KINDS);
        }
        InputUtils.validateK8sName(name, "resource name");
        InputUtils.validateK8sName(ns, "namespace");

        LOG.infof("Getting events for %s=%s (namespace=%s, sinceMinutes=%s)",
            kind, name, ns, sinceMinutes);

        Instant sinceTime = sinceMinutes != null
            ? Instant.now().minusSeconds((long) sinceMinutes * SECONDS_PER_MINUTE)
            : null;

        List<ResourceEventsResult> resourceEvents = new ArrayList<>();

        resourceEvents.add(eventsService.getEventsForResource(ns, name, kind, sinceTime));

        try {
            Map<String, String> labels = Map.of(
                ResourceLabels.STRIMZI_CLUSTER_LABEL, name,
                ResourceLabels.STRIMZI_KIND_LABEL, kind);
            List<Pod> pods = k8sService.queryResourcesByLabels(Pod.class, ns, labels);
            if (pods.size() > maxRelatedResources) {
                LOG.warnf("%s %s has %d pods, limiting event queries to first %d",
                    kind, name, pods.size(), maxRelatedResources);
                pods = pods.subList(0, maxRelatedResources);
            }
            for (Pod pod : pods) {
                resourceEvents.add(eventsService.getEventsForResource(
                    ns, pod.getMetadata().getName(), "Pod", sinceTime));
            }
        } catch (KubernetesQueryException e) {
            LOG.warnf("Failed to query %s pods for event collection: %s", kind, e.getMessage());
        }

        List<ResourceEventsResult> nonEmpty = resourceEvents.stream()
            .filter(r -> !r.events().isEmpty())
            .toList();

        return StrimziEventsResponse.of(name, ns, nonEmpty);
    }
}
