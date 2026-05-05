/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.dto.kafkabridge;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.streamshub.mcp.common.dto.PodSummaryResponse;

/**
 * Response representing KafkaBridge pods containment: a bridge contains pods.
 *
 * @param bridgeName the KafkaBridge name that contains these pods
 * @param namespace  the Kubernetes namespace
 * @param podSummary the summary of pods contained within this bridge
 */
public record KafkaBridgePodsResponse(
    @JsonProperty("bridge_name") String bridgeName,
    @JsonProperty("namespace") String namespace,
    @JsonProperty("pod_summary") PodSummaryResponse podSummary
) {

    /**
     * Creates a response showing that this bridge contains the given pods.
     *
     * @param bridgeName the bridge that contains these pods
     * @param namespace  the namespace
     * @param podSummary the pods contained within the bridge
     * @return a bridge pods response
     */
    public static KafkaBridgePodsResponse of(String bridgeName, String namespace,
                                              PodSummaryResponse podSummary) {
        return new KafkaBridgePodsResponse(bridgeName, namespace, podSummary);
    }

    /**
     * Creates an empty response when the bridge contains no pods.
     *
     * @param bridgeName the bridge name
     * @param namespace  the namespace
     * @return an empty bridge pods response
     */
    public static KafkaBridgePodsResponse empty(String bridgeName, String namespace) {
        return new KafkaBridgePodsResponse(bridgeName, namespace, PodSummaryResponse.empty(namespace));
    }
}
