/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Replica count information with expected and ready counts.
 * Reusable across any resource that reports replica status (Kafka, deployments, etc.).
 *
 * @param expected the expected number of replicas
 * @param ready    the number of ready replicas
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReplicasInfo(
    @JsonProperty("expected") Integer expected,
    @JsonProperty("ready") Integer ready
) {
    /**
     * Creates a replicas info from the given parameters.
     *
     * @param expected the expected number of replicas
     * @param ready    the number of ready replicas
     * @return a new ReplicasInfo instance
     */
    public static ReplicasInfo of(final Integer expected, final Integer ready) {
        return new ReplicasInfo(expected, ready);
    }
}
