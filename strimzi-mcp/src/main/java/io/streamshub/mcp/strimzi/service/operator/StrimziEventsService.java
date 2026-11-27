/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.service.operator;

import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaim;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.streamshub.mcp.common.config.KubernetesConstants;
import io.streamshub.mcp.common.dto.ResourceEventsResult;
import io.streamshub.mcp.common.service.KubernetesEventsService;
import io.streamshub.mcp.common.service.KubernetesQueryException;
import io.streamshub.mcp.common.service.KubernetesResourceService;
import io.streamshub.mcp.common.util.InputUtils;
import io.streamshub.mcp.common.util.McpErrors;
import io.streamshub.mcp.strimzi.config.StrimziConstants;
import io.streamshub.mcp.strimzi.dto.operator.StrimziEventsResponse;
import io.streamshub.mcp.strimzi.service.kafka.KafkaService;
import io.strimzi.api.ResourceLabels;
import io.strimzi.api.kafka.model.bridge.KafkaBridge;
import io.strimzi.api.kafka.model.connect.KafkaConnect;
import io.strimzi.api.kafka.model.kafka.Kafka;
import io.strimzi.api.kafka.model.mirrormaker2.KafkaMirrorMaker2;
import io.strimzi.api.kafka.model.nodepool.KafkaNodePool;
import io.strimzi.api.kafka.model.rebalance.KafkaRebalance;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
/**
 * Service for gathering Kubernetes events across Strimzi resources.
 * Supports Kafka clusters (with PVC and node pool events),
 * KafkaConnect, KafkaMirrorMaker2, KafkaBridge, KafkaRebalance (CR and pod events),
 * and StrimziOperator and DrainCleaner deployments.
 *
 * <p>Delegates generic event querying to {@link KubernetesEventsService}
 * and adds Strimzi-specific orchestration: resolving the resource,
 * finding related pods by appropriate labels.</p>
 *
 * <p>The namespace is auto-discovered for every kind when omitted. For Kafka and the custom
 * resource kinds (KafkaConnect, KafkaBridge, KafkaMirrorMaker2, KafkaRebalance) a name that exists
 * in several namespaces raises the structured AMBIGUOUS error listing them; StrimziOperator and
 * DrainCleaner use the first matching deployment.</p>
 */
@ApplicationScoped
public class StrimziEventsService {

    private static final Logger LOG = Logger.getLogger(StrimziEventsService.class);
    private static final int SECONDS_PER_MINUTE = 60;
    private static final List<String> SUPPORTED_RESOURCE_KINDS = List.of(
        StrimziConstants.KindValues.KAFKA,
        StrimziConstants.KindValues.KAFKA_CONNECT,
        StrimziConstants.KindValues.KAFKA_MIRROR_MAKER_2,
        StrimziConstants.KindValues.KAFKA_BRIDGE,
        StrimziConstants.KindValues.STRIMZI_OPERATOR,
        StrimziConstants.KindValues.DRAIN_CLEANER,
        StrimziConstants.KindValues.KAFKA_REBALANCE);
    private static final Map<String, Class<? extends HasMetadata>> CR_CLASSES = Map.of(
        StrimziConstants.KindValues.KAFKA_CONNECT, KafkaConnect.class,
        StrimziConstants.KindValues.KAFKA_BRIDGE, KafkaBridge.class,
        StrimziConstants.KindValues.KAFKA_MIRROR_MAKER_2, KafkaMirrorMaker2.class,
        StrimziConstants.KindValues.KAFKA_REBALANCE, KafkaRebalance.class);

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
     * @param sinceMinutes only return events last observed within this many minutes, or null for all
     * @return events grouped by resource
     */
    public StrimziEventsResponse getEvents(final String namespace, final String resourceName,
                                            final String resourceKind, final Integer sinceMinutes) {
        String ns = InputUtils.normalizeInput(namespace);
        String name = InputUtils.normalizeInput(resourceName);
        if (name == null) {
            throw McpErrors.invalidParams("Resource name is required");
        }
        String kind = canonicalKind(resourceKind);
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

    /**
     * Get events for a KafkaTopic and for the Entity Operator pods of its cluster, whose Topic
     * Operator reconciles the topic.
     *
     * <p>Unlike {@link #getEvents} for a Kafka cluster, this leaves out the broker pods, PVCs and
     * node pools, whose scheduling and image-pull events say nothing about one topic.</p>
     *
     * @param namespace         the namespace of the KafkaTopic (and of its cluster's Entity Operator)
     * @param topicResourceName the KafkaTopic resource name
     * @param clusterName       the Kafka cluster the topic belongs to, or null to skip the operator pods
     * @param sinceMinutes      only return events last observed within this many minutes, or null for all
     * @return events grouped by resource
     */
    public StrimziEventsResponse getKafkaTopicEvents(final String namespace, final String topicResourceName,
                                                     final String clusterName, final Integer sinceMinutes) {
        Instant sinceTime = sinceMinutes != null
            ? Instant.now().minusSeconds((long) sinceMinutes * SECONDS_PER_MINUTE)
            : null;

        List<ResourceEventsResult> results = new ArrayList<>();
        results.add(eventsService.getEventsForResource(namespace, topicResourceName,
            StrimziConstants.KindValues.KAFKA_TOPIC, sinceTime));
        if (clusterName != null) {
            collectPods(namespace,
                Map.of(KubernetesConstants.Labels.APP_NAME, StrimziConstants.EntityOperator.APP_NAME_VALUE,
                    ResourceLabels.STRIMZI_CLUSTER_LABEL, clusterName),
                results, sinceTime);
        }
        return buildResponse(topicResourceName, namespace, results);
    }

    /**
     * Resolve a {@code resourceKind} argument to its canonical spelling, ignoring case and
     * surrounding whitespace.
     *
     * @param resourceKind the kind as given by the caller
     * @return the canonical kind, e.g. {@code KafkaBridge} for {@code kafkabridge}
     */
    private static String canonicalKind(final String resourceKind) {
        if (resourceKind == null || resourceKind.isBlank()) {
            throw McpErrors.invalidParams("resourceKind is required. Supported values: "
                + SUPPORTED_RESOURCE_KINDS);
        }
        String trimmed = resourceKind.trim();
        return SUPPORTED_RESOURCE_KINDS.stream()
            .filter(kind -> kind.equalsIgnoreCase(trimmed))
            .findFirst()
            .orElseThrow(() -> McpErrors.invalidParams("Unsupported resourceKind '" + trimmed
                + "'. Supported values: " + SUPPORTED_RESOURCE_KINDS));
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
        String resolvedNs = resolveCustomResourceNamespace(namespace, name, kind);

        List<ResourceEventsResult> results = new ArrayList<>();
        results.add(eventsService.getEventsForResource(resolvedNs, name, kind, sinceTime));
        collectPods(resolvedNs,
            Map.of(ResourceLabels.STRIMZI_CLUSTER_LABEL, name,
                   ResourceLabels.STRIMZI_KIND_LABEL, kind),
            results, sinceTime);

        return buildResponse(name, resolvedNs, results);
    }

    /**
     * Use the given namespace, or find the one namespace that holds a custom resource of this
     * kind and name. Query failures (RBAC, missing CRD) propagate as-is.
     */
    private String resolveCustomResourceNamespace(final String namespace, final String name,
                                                  final String kind) {
        if (namespace != null) {
            return namespace;
        }
        Class<? extends HasMetadata> resourceClass = CR_CLASSES.get(kind);
        if (resourceClass == null) {
            throw new IllegalStateException("No custom resource class registered for kind " + kind);
        }
        List<String> namespaces = k8sService.queryResourcesInAnyNamespace(resourceClass).stream()
            .filter(resource -> name.equals(resource.getMetadata().getName()))
            .map(resource -> resource.getMetadata().getNamespace())
            .distinct()
            .sorted()
            .toList();
        if (namespaces.isEmpty()) {
            throw McpErrors.notFound(kind, name, null);
        }
        if (namespaces.size() > 1) {
            throw McpErrors.ambiguous(kind, name, namespaces);
        }
        return namespaces.getFirst();
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
        throw McpErrors.invalidParams(
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
