/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.dto.kafka;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Details of PersistentVolumeClaims associated with a Kafka cluster.
 *
 * @param clusterName the Kafka cluster name
 * @param namespace   the Kubernetes namespace
 * @param pvcs        the list of PVC information objects
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record KafkaPvcResponse(
    @JsonProperty("cluster_name") String clusterName,
    @JsonProperty("namespace") String namespace,
    @JsonProperty("pvcs") List<KafkaPvcInfo> pvcs
) {

    /**
     * Creates a new PVC response.
     *
     * @param clusterName the Kafka cluster name
     * @param namespace   the Kubernetes namespace
     * @param pvcs        the list of PVC details
     * @return a new response record
     */
    public static KafkaPvcResponse of(
            final String clusterName,
            final String namespace,
            final List<KafkaPvcInfo> pvcs) {
        return new KafkaPvcResponse(clusterName, namespace, pvcs != null ? pvcs : List.of());
    }
}
