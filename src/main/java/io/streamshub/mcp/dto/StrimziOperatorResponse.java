/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Strimzi operator resource information.
 * Follows Kubernetes resource naming convention.
 *
 * @param name          the deployment name (e.g., 'strimzi-cluster-operator')
 * @param namespace     the Kubernetes namespace where the operator is deployed
 * @param ready         whether the operator is ready and healthy
 * @param replicas      the desired number of replicas
 * @param readyReplicas the number of ready replicas
 * @param version       the operator version (extracted from image or labels)
 * @param image         the container image used by the operator (optional)
 * @param uptimeHours   the operator uptime in hours (optional, for status queries)
 * @param status        the overall status (e.g., 'HEALTHY', 'DEGRADED', 'DOWN', 'NOT_DEPLOYED')
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record StrimziOperatorResponse(
    @JsonProperty("name") String name,
    @JsonProperty("namespace") String namespace,
    @JsonProperty("ready") boolean ready,
    @JsonProperty("replicas") Integer replicas,
    @JsonProperty("ready_replicas") Integer readyReplicas,
    @JsonProperty("version") String version,
    @JsonProperty("image") String image,
    @JsonProperty("uptime_hours") String uptimeHours,
    @JsonProperty("status") String status
) {

    /**
     * Create operator response with full deployment details.
     */
    public static StrimziOperatorResponse of(String name, String namespace, boolean ready,
                                             Integer replicas, Integer readyReplicas,
                                             String version, String image, String uptimeHours, String status) {
        return new StrimziOperatorResponse(name, namespace, ready, replicas, readyReplicas, version, image, uptimeHours, status);
    }
}