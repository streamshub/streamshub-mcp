/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.dto.kafkamirrormaker2;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.streamshub.mcp.common.dto.PodSummaryResponse;
/**
 * Response representing KafkaMirrorMaker2 pods.
 *
 * @param mirrorMakerName the KafkaMirrorMaker2 name
 * @param namespace       the Kubernetes namespace
 * @param podSummary      the summary of pods
 */
public record KafkaMirrorMaker2PodsResponse(
    @JsonProperty("mirror_maker_name") String mirrorMakerName,
    @JsonProperty("namespace") String namespace,
    @JsonProperty("pod_summary") PodSummaryResponse podSummary
) {

    /**
     * Creates a response with pod data.
     *
     * @param mirrorMakerName the MM2 name
     * @param namespace       the namespace
     * @param podSummary      the pod summary
     * @return a pods response
     */
    public static KafkaMirrorMaker2PodsResponse of(final String mirrorMakerName, final String namespace,
                                                    final PodSummaryResponse podSummary) {
        return new KafkaMirrorMaker2PodsResponse(mirrorMakerName, namespace, podSummary);
    }

    /**
     * Creates an empty response when no pods are found.
     *
     * @param mirrorMakerName the MM2 name
     * @param namespace       the namespace
     * @return an empty pods response
     */
    public static KafkaMirrorMaker2PodsResponse empty(final String mirrorMakerName,
                                                       final String namespace) {
        return new KafkaMirrorMaker2PodsResponse(mirrorMakerName, namespace,
            PodSummaryResponse.empty(namespace));
    }
}
