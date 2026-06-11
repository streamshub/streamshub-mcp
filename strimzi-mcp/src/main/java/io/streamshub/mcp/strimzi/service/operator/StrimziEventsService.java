/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.service.operator;

import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaim;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.quarkiverse.mcp.server.ToolCallException;
import io.streamshub.mcp.common.config.KubernetesConstants;
import io.streamshub.mcp.common.dto.ResourceEventsResult;
import io.streamshub.mcp.common.service.KubernetesEventsService;
import io.streamshub.mcp.common.service.KubernetesQueryException;
import io.streamshub.mcp.common.service.KubernetesResourceService;
import io.streamshub.mcp.common.util.InputUtils;
import io.streamshub.mcp.strimzi.config.StrimziConstants;
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
 * Service for gathering Kubernetes events across Strimzi resources.
 * Supports Kafka clusters (with PVC and node pool events),
 * KafkaConnect, KafkaMirrorMaker2, KafkaBridge (CR and pod events),
 * and StrimziOperator and DrainCleaner deployments.
 *
 * <p>Delegates generic event querying to {@link KubernetesEventsService}
 * and adds Strimzi-specific orchestration: resolving the resource,
 * finding related pods by appropriate labels.</p>
 */
@ApplicationScoped
public class StrimziEventsService {

    private static final Logger LOG = Logger.getLogger(StrimziEventsService.class);
    private static final int SECONDS_PER_MINUTE = 60;
    private static final Set<String> SUPPORTED_RESOURCE_KINDS = Set.of(
        StrimziConstants.KindValues.KAFKA,
        StrimziConstants.KindValues.KAFKA_CONNECT,
        StrimziConstants.KindValues.KAFKA_MIRROR_MAKER_2,
        StrimziConstants.KindValues.KAFKA_BRIDGE,
        StrimziConstants.KindValues.STRIMZI_OPERATOR,
        StrimziConstants.KindValues.DRAIN_CLEANER);

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
     * Get events for a Strimzi resource and all related pods.
     *
     * @param namespace    the namespace, or null for auto-discovery
     * @param resourceName the Strimzi resource name
     * @param resourceKind the Strimzi kind (required)
     * @param sinceMinutes only return events newer than this many minutes, or null for all
     * @return events grouped by resource
     */
    public StrimziEventsResponse getEvents(final String namespace, final String resourceName,
                                            final String resourceKind, final Integer sinceMinutes) {
        String ns = InputUtils.normalizeInput(namespace);
        String name = InputUtils.normalizeInput(resourceName);
        String kind = resourceKind != null ? resourceKind.trim() : null;

        if (name == null) {
            throw new ToolCallException("Resource name is required");
        }
        if (kind == null || !SUPPORTED_RESOURCE_KINDS.contains(kind)) {
            throw new ToolCallException("resource_kind is required. Supported values: "
                + SUPPORTED_RESOURCE_KINDS);
        }
        InputUtils.validateK8sName(name, "resource name");
        InputUtils.validateK8sName(ns, "namespace");

        LOG.infof("Getting events for %s=%s (namespace=%s, sinceMinutes=%s)",
            kind, name, ns != null ? ns : "auto", sinceMinutes);

        Instant sinceTime = sinceMinutes != null
            ? Instant.now().minusSeconds((long) sinceMinutes * SECONDS_PER_MINUTE)
            : null;

        return switch (kind) {
            case StrimziConstants.KindValues.KAFKA ->
                collectKafkaEvents(ns, name, sinceTime);
            case StrimziConstants.KindValues.STRIMZI_OPERATOR ->
                collectDeploymentEvents(ns, name,
                    StrimziConstants.Operator.APP_LABEL_VALUE,
                    ResourceLabels.STRIMZI_KIND_LABEL,
                    StrimziConstants.KindValues.CLUSTER_OPERATOR, sinceTime);
            case StrimziConstants.KindValues.DRAIN_CLEANER ->
                collectDeploymentEvents(ns, name,
                    StrimziConstants.DrainCleaner.APP_LABEL_VALUE,
                    KubernetesConstants.Labels.APP,
                    StrimziConstants.DrainCleaner.APP_LABEL_VALUE, sinceTime);
            default ->
                collectStrimziCREvents(ns, name, kind, sinceTime);
        };
    }

    // ---- Resource-specific collectors ----

    private StrimziEventsResponse collectKafkaEvents(final String namespace, final String name,
                                                      final Instant sinceTime) {
        Kafka kafka = kafkaService.findKafkaCluster(namespace, name);
        String resolvedNs = kafka.getMetadata().getNamespace();

        List<ResourceEventsResult> results = new ArrayList<>();
        results.add(eventsService.getEventsForResource(resolvedNs, name, "Kafka", sinceTime));
        collectPods(resolvedNs, ResourceLabels.STRIMZI_CLUSTER_LABEL, name, null,
            results, sinceTime);
        collectLabeledResourceEvents(PersistentVolumeClaim.class, "PersistentVolumeClaim",
            resolvedNs, name, results, sinceTime);
        collectLabeledResourceEvents(KafkaNodePool.class, "KafkaNodePool",
            resolvedNs, name, results, sinceTime);

        return buildResponse(name, resolvedNs, results);
    }

    private StrimziEventsResponse collectStrimziCREvents(final String namespace, final String name,
                                                          final String kind, final Instant sinceTime) {
        if (namespace == null) {
            throw new ToolCallException("Namespace is required for " + kind + " events");
        }

        List<ResourceEventsResult> results = new ArrayList<>();
        results.add(eventsService.getEventsForResource(namespace, name, kind, sinceTime));
        collectPods(namespace,
            Map.of(ResourceLabels.STRIMZI_CLUSTER_LABEL, name,
                   ResourceLabels.STRIMZI_KIND_LABEL, kind),
            results, sinceTime);

        return buildResponse(name, namespace, results);
    }

    private StrimziEventsResponse collectDeploymentEvents(final String namespace, final String name,
                                                           final String appLabel,
                                                           final String podLabelKey,
                                                           final String podLabelValue,
                                                           final Instant sinceTime) {
        String resolvedNs = resolveDeploymentNamespace(namespace, name, appLabel);

        List<ResourceEventsResult> results = new ArrayList<>();
        results.add(
            eventsService.getEventsForResource(resolvedNs, name, "Deployment", sinceTime));
        collectPods(resolvedNs, podLabelKey, podLabelValue, name, results, sinceTime);

        return buildResponse(name, resolvedNs, results);
    }

    // ---- Pod collection ----

    private void collectPods(final String namespace, final String labelKey,
                              final String labelValue, final String namePrefix,
                              final List<ResourceEventsResult> results,
                              final Instant sinceTime) {
        try {
            List<Pod> pods = k8sService.queryResourcesByLabel(
                Pod.class, namespace, labelKey, labelValue);
            if (namePrefix != null) {
                pods = pods.stream()
                    .filter(pod -> pod.getMetadata().getName().startsWith(namePrefix))
                    .toList();
            }
            addPodEvents(pods, namespace, results, sinceTime);
        } catch (KubernetesQueryException e) {
            LOG.warnf("Failed to query pods for event collection: %s", e.getMessage());
        }
    }

    private void collectPods(final String namespace, final Map<String, String> labels,
                              final List<ResourceEventsResult> results,
                              final Instant sinceTime) {
        try {
            List<Pod> pods = k8sService.queryResourcesByLabels(Pod.class, namespace, labels);
            addPodEvents(pods, namespace, results, sinceTime);
        } catch (KubernetesQueryException e) {
            LOG.warnf("Failed to query pods for event collection: %s", e.getMessage());
        }
    }

    private void addPodEvents(List<Pod> pods, final String namespace,
                               final List<ResourceEventsResult> results,
                               final Instant sinceTime) {
        if (pods.size() > maxRelatedResources) {
            LOG.warnf("Limiting pod event queries from %d to %d", pods.size(), maxRelatedResources);
            pods = pods.subList(0, maxRelatedResources);
        }
        for (Pod pod : pods) {
            results.add(eventsService.getEventsForResource(
                namespace, pod.getMetadata().getName(), "Pod", sinceTime));
        }
    }

    // ---- Labeled resource collection (PVCs, NodePools) ----

    private <T extends HasMetadata> void collectLabeledResourceEvents(
            final Class<T> resourceClass, final String resourceKind,
            final String namespace, final String clusterName,
            final List<ResourceEventsResult> results, final Instant sinceTime) {
        try {
            List<T> resources = k8sService.queryResourcesByLabel(
                resourceClass, namespace, ResourceLabels.STRIMZI_CLUSTER_LABEL, clusterName);
            if (resources.size() > maxRelatedResources) {
                LOG.warnf("Cluster %s has %d %s resources, limiting to first %d",
                    clusterName, resources.size(), resourceKind, maxRelatedResources);
                resources = resources.subList(0, maxRelatedResources);
            }
            for (T resource : resources) {
                results.add(eventsService.getEventsForResource(
                    namespace, resource.getMetadata().getName(), resourceKind, sinceTime));
            }
        } catch (KubernetesQueryException e) {
            LOG.warnf("Failed to query %s for event collection: %s", resourceKind, e.getMessage());
        }
    }

    // ---- Namespace resolution ----

    private String resolveDeploymentNamespace(final String namespace, final String name,
                                               final String appLabelValue) {
        if (namespace != null) {
            return namespace;
        }
        try {
            List<Deployment> deployments = k8sService.queryResourcesByLabelInAnyNamespace(
                Deployment.class, KubernetesConstants.Labels.APP, appLabelValue);
            for (Deployment deployment : deployments) {
                if (name.equals(deployment.getMetadata().getName())) {
                    return deployment.getMetadata().getNamespace();
                }
            }
        } catch (KubernetesQueryException e) {
            LOG.warnf("Failed to auto-discover namespace for %s: %s", name, e.getMessage());
        }
        throw new ToolCallException(
            "Could not auto-discover namespace for '" + name + "'. Please provide the namespace.");
    }

    // ---- Response ----

    private StrimziEventsResponse buildResponse(final String name, final String namespace,
                                                 final List<ResourceEventsResult> results) {
        List<ResourceEventsResult> nonEmpty = results.stream()
            .filter(r -> !r.events().isEmpty())
            .toList();
        return StrimziEventsResponse.of(name, namespace, nonEmpty);
    }
}
