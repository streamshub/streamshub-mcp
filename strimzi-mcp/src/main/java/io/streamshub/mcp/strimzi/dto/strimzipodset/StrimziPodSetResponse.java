/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.dto.strimzipodset;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * Rolling-update state of a single StrimziPodSet.
 *
 * @param name         the StrimziPodSet name
 * @param namespace    the Kubernetes namespace
 * @param cluster      the owning Kafka cluster name (from the {@code strimzi.io/cluster} label)
 * @param pods         number of pods managed by this StrimziPodSet
 * @param readyPods    number of managed pods that are ready
 * @param currentPods  number of managed pods on the current revision
 * @param podRevisions per-pod revision map (pod name to {@code strimzi.io/revision}), present only
 *                     when a rolling update is in progress (pods on more than one revision)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record StrimziPodSetResponse(
    @JsonProperty("name") String name,
    @JsonProperty("namespace") String namespace,
    @JsonProperty("cluster") String cluster,
    @JsonProperty("pods") int pods,
    @JsonProperty("ready_pods") int readyPods,
    @JsonProperty("current_pods") int currentPods,
    @JsonProperty("pod_revisions") Map<String, String> podRevisions
) {
    /**
     * Creates a new StrimziPodSet response.
     *
     * @param name         the StrimziPodSet name
     * @param namespace    the namespace
     * @param cluster      the owning Kafka cluster name
     * @param pods         number of managed pods
     * @param readyPods    number of ready pods
     * @param currentPods  number of pods on the current revision
     * @param podRevisions per-pod revision map, or null when all pods share one revision
     * @return new response instance
     */
    public static StrimziPodSetResponse of(
        final String name,
        final String namespace,
        final String cluster,
        final int pods,
        final int readyPods,
        final int currentPods,
        final Map<String, String> podRevisions
    ) {
        return new StrimziPodSetResponse(name, namespace, cluster, pods, readyPods, currentPods, podRevisions);
    }
}
