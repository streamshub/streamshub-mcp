/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.dto.kafkanodepool;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
/**
 * Status of a PersistentVolumeClaim backing a KafkaNodePool broker.
 *
 * @param name  the PVC name (e.g. {@code data-my-cluster-broker-np-0})
 * @param phase the PVC phase (e.g. {@code Bound}, {@code Pending})
 * @param bound whether the PVC phase is {@code Bound}
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record KafkaNodePoolPvcStatus(
    @JsonProperty("name") String name,
    @JsonProperty("phase") String phase,
    @JsonProperty("bound") boolean bound
) {

    private static final String PHASE_BOUND = "Bound";

    /**
     * Creates a PVC status from its name and phase.
     *
     * @param name  the PVC name
     * @param phase the PVC phase
     * @return a new PVC status
     */
    public static KafkaNodePoolPvcStatus of(final String name, final String phase) {
        return new KafkaNodePoolPvcStatus(name, phase, PHASE_BOUND.equals(phase));
    }
}
