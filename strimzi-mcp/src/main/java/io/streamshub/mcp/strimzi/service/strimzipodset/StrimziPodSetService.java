/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.service.strimzipodset;

import com.fasterxml.jackson.databind.ObjectMapper;
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

    @Inject
    ObjectMapper objectMapper;

    StrimziPodSetService() {
    }

    /**
     * List the StrimziPodSets owned by a Kafka cluster with their rolling-update state.
     *
     * @param namespace   the namespace, or null for auto-discovery
     * @param clusterName the Kafka cluster name
     * @return list of StrimziPodSet responses
     */
    public List<StrimziPodSetResponse> listStrimziPodSets(final String namespace, final String clusterName) {
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

        Map<String, String> stale = collectStalePodRevisions(podSet, name, clusterPods);
        Map<String, String> podRevisions = stale.isEmpty() ? null : stale;

        return StrimziPodSetResponse.of(name, namespace, cluster, pods, readyPods, currentPods, podRevisions);
    }

    /**
     * Identify pods of the given StrimziPodSet that are not yet on the desired revision, mapping each
     * stale pod name to its current (outdated) {@code strimzi.io/revision}.
     *
     * <p>The {@code strimzi.io/revision} annotation is a hash of the whole pod definition, so every pod
     * carries a distinct value even in steady state; distinct values across pods therefore do not indicate
     * a rolling update. Following the operator's own logic, a pod is stale only when its live revision
     * differs from the desired revision the operator records in {@code spec.pods[].metadata.annotations}.
     */
    private Map<String, String> collectStalePodRevisions(
        final StrimziPodSet podSet, final String podSetName, final List<Pod> clusterPods) {

        Map<String, String> stale = new LinkedHashMap<>();
        if (podSetName == null) {
            return stale;
        }

        Map<String, String> desiredByPod = desiredRevisions(podSet);

        for (Pod pod : clusterPods) {
            if (pod.getMetadata() == null || !isOwnedBy(pod, podSetName)) {
                continue;
            }
            String podName = pod.getMetadata().getName();
            if (podName == null) {
                continue;
            }
            String desired = desiredByPod.get(podName);
            String current = revisionAnnotation(pod);
            // Stale only when the operator has a desired revision for this pod that differs from the live one.
            if (desired != null && !desired.equals(current)) {
                stale.put(podName, current);
            }
        }
        return stale;
    }

    /**
     * Extract the desired revision per pod from {@code spec.pods[].metadata.annotations}.
     */
    private Map<String, String> desiredRevisions(final StrimziPodSet podSet) {
        Map<String, String> desired = new LinkedHashMap<>();
        if (podSet.getSpec() == null || podSet.getSpec().getPods() == null) {
            return desired;
        }
        for (Map<String, Object> podMap : podSet.getSpec().getPods()) {
            Pod desiredPod = objectMapper.convertValue(podMap, Pod.class);
            if (desiredPod.getMetadata() == null) {
                continue;
            }
            String podName = desiredPod.getMetadata().getName();
            String revision = revisionAnnotation(desiredPod);
            if (podName != null && revision != null) {
                desired.put(podName, revision);
            }
        }
        return desired;
    }

    private String revisionAnnotation(final Pod pod) {
        if (pod.getMetadata() == null || pod.getMetadata().getAnnotations() == null) {
            return null;
        }
        return pod.getMetadata().getAnnotations().get(StrimziConstants.Annotations.REVISION);
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
