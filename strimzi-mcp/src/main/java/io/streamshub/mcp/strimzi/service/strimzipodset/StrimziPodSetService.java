/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.service.strimzipodset;

import io.fabric8.kubernetes.api.model.Pod;
import io.streamshub.mcp.common.service.KubernetesResourceService;
import io.streamshub.mcp.common.util.InputUtils;
import io.streamshub.mcp.common.util.McpErrors;
import io.streamshub.mcp.strimzi.config.StrimziConstants;
import io.streamshub.mcp.strimzi.dto.strimzipodset.StrimziPodSetResponse;
import io.streamshub.mcp.strimzi.service.kafka.KafkaService;
import io.strimzi.api.ResourceLabels;
import io.strimzi.api.kafka.model.kafka.Kafka;
import io.strimzi.api.kafka.model.podset.StrimziPodSet;
import io.strimzi.api.kafka.model.podset.StrimziPodSetStatus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Business logic for inspecting StrimziPodSet rolling-update state.
 */
@ApplicationScoped
public class StrimziPodSetService {

    private static final Logger LOG = Logger.getLogger(StrimziPodSetService.class);

    @Inject
    KubernetesResourceService k8sService;

    @Inject
    KafkaService kafkaService;

    StrimziPodSetService() {
    }

    /**
     * List the StrimziPodSets owned by a Kafka cluster with their rolling-update state.
     *
     * @param namespace   the namespace, or null for auto-discovery
     * @param clusterName the Kafka cluster name
     * @return list of StrimziPodSet responses
     */
    public List<StrimziPodSetResponse> listPodSets(final String namespace, final String clusterName) {
        String ns = InputUtils.normalizeInput(namespace);
        String normalizedName = InputUtils.normalizeInput(clusterName);

        if (normalizedName == null) {
            throw McpErrors.invalidParams("Cluster name is required");
        }
        InputUtils.validateK8sName(normalizedName, "cluster name");
        InputUtils.validateK8sName(ns, "namespace");

        LOG.infof("Getting StrimziPodSets for cluster=%s (namespace=%s)",
            normalizedName, ns != null ? ns : "auto");

        Kafka kafka = kafkaService.findKafkaCluster(ns, normalizedName);
        String resolvedNs = kafka.getMetadata().getNamespace();

        List<StrimziPodSet> podSets = k8sService.queryResourcesByLabel(
            StrimziPodSet.class, resolvedNs, ResourceLabels.STRIMZI_CLUSTER_LABEL, normalizedName);

        List<Pod> clusterPods = k8sService.queryResourcesByLabel(
            Pod.class, resolvedNs, ResourceLabels.STRIMZI_CLUSTER_LABEL, normalizedName);

        List<StrimziPodSetResponse> responses = podSets.stream()
            .map(podSet -> createResponse(podSet, clusterPods))
            .toList();

        LOG.infof("Found %d StrimziPodSets for Kafka cluster '%s'", responses.size(), normalizedName);
        return responses;
    }

    private StrimziPodSetResponse createResponse(final StrimziPodSet podSet, final List<Pod> clusterPods) {
        String name = podSet.getMetadata() != null ? podSet.getMetadata().getName() : null;
        String namespace = podSet.getMetadata() != null ? podSet.getMetadata().getNamespace() : null;

        String cluster = null;
        if (podSet.getMetadata() != null && podSet.getMetadata().getLabels() != null) {
            cluster = podSet.getMetadata().getLabels().get(ResourceLabels.STRIMZI_CLUSTER_LABEL);
        }

        StrimziPodSetStatus status = podSet.getStatus();
        int pods = status != null ? status.getPods() : 0;
        int readyPods = status != null ? status.getReadyPods() : 0;
        int currentPods = status != null ? status.getCurrentPods() : 0;

        Map<String, String> revisions = collectRevisions(name, clusterPods);
        Map<String, String> podRevisions = isRollingUpdate(revisions) ? revisions : null;

        return StrimziPodSetResponse.of(name, namespace, cluster, pods, readyPods, currentPods, podRevisions);
    }

    /**
     * Collect the {@code strimzi.io/revision} annotation of every pod owned by the given StrimziPodSet.
     */
    private Map<String, String> collectRevisions(final String podSetName, final List<Pod> clusterPods) {
        Map<String, String> revisions = new LinkedHashMap<>();
        if (podSetName == null) {
            return revisions;
        }
        for (Pod pod : clusterPods) {
            if (pod.getMetadata() == null || !isOwnedBy(pod, podSetName)) {
                continue;
            }
            String podName = pod.getMetadata().getName();
            Map<String, String> annotations = pod.getMetadata().getAnnotations();
            String revision = annotations != null ? annotations.get(StrimziConstants.Annotations.REVISION) : null;
            if (podName != null && revision != null) {
                revisions.put(podName, revision);
            }
        }
        return revisions;
    }

    /**
     * A rolling update is in progress when the pod set's pods carry more than one distinct revision.
     */
    private boolean isRollingUpdate(final Map<String, String> revisions) {
        return revisions.values().stream().distinct().count() > 1;
    }

    private boolean isOwnedBy(final Pod pod, final String podSetName) {
        if (pod.getMetadata().getOwnerReferences() == null) {
            return false;
        }
        return pod.getMetadata().getOwnerReferences().stream()
            .anyMatch(ref -> StrimziPodSet.RESOURCE_KIND.equals(ref.getKind())
                && podSetName.equals(ref.getName()));
    }
}
