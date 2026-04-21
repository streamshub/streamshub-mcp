/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.dto.kafkaconnect;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.streamshub.mcp.common.dto.PodSummaryResponse;

/**
 * Response representing KafkaConnect pods containment: a Connect cluster contains pods.
 *
 * @param connectName the KafkaConnect cluster name that contains these pods
 * @param namespace   the Kubernetes namespace
 * @param podSummary  the summary of pods contained within this Connect cluster
 */
public record KafkaConnectPodsResponse(
    @JsonProperty("connect_name") String connectName,
    @JsonProperty("namespace") String namespace,
    @JsonProperty("pod_summary") PodSummaryResponse podSummary
) {

    /**
     * Creates a response showing that this Connect cluster contains the given pods.
     *
     * @param connectName the Connect cluster that contains these pods
     * @param namespace   the namespace
     * @param podSummary  the pods contained within the Connect cluster
     * @return a Connect cluster pods response
     */
    public static KafkaConnectPodsResponse of(String connectName, String namespace,
                                               PodSummaryResponse podSummary) {
        return new KafkaConnectPodsResponse(connectName, namespace, podSummary);
    }

    /**
     * Creates an empty response when the Connect cluster contains no pods.
     *
     * @param connectName the Connect cluster name
     * @param namespace   the namespace
     * @return an empty Connect pods response
     */
    public static KafkaConnectPodsResponse empty(String connectName, String namespace) {
        return new KafkaConnectPodsResponse(connectName, namespace, PodSummaryResponse.empty(namespace));
    }
}
