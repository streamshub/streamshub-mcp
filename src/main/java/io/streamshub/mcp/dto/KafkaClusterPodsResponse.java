/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Response representing the proper containment relationship: Cluster contains Pods.
 * This DTO correctly models that clusters own/contain pods, not the other way around.
 *
 * @param clusterName the Kafka cluster name that contains these pods
 * @param namespace   the Kubernetes namespace
 * @param podSummary  the summary of pods contained within this cluster
 */
public record KafkaClusterPodsResponse(
    @JsonProperty("cluster_name") String clusterName,
    @JsonProperty("namespace") String namespace,
    @JsonProperty("pod_summary") PodSummaryResponse podSummary
) {

    /**
     * Creates a response showing that this cluster contains the given pods.
     *
     * @param clusterName the cluster that contains these pods
     * @param namespace   the namespace
     * @param podSummary  the pods contained within the cluster
     * @return a properly modeled cluster-contains-pods response
     */
    public static KafkaClusterPodsResponse of(String clusterName, String namespace, PodSummaryResponse podSummary) {
        return new KafkaClusterPodsResponse(clusterName, namespace, podSummary);
    }

    /**
     * Creates an empty response when the cluster contains no pods.
     *
     * @param clusterName the cluster name
     * @param namespace   the namespace
     * @return an empty cluster pods response
     */
    public static KafkaClusterPodsResponse empty(String clusterName, String namespace) {
        return new KafkaClusterPodsResponse(
            clusterName,
            namespace,
            PodSummaryResponse.empty(namespace)
        );
    }
}