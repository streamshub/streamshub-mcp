/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.dto.kafka;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Detailed PodDisruptionBudget information for a Kafka cluster.
 *
 * @param name                 the PDB resource name
 * @param namespace            the namespace
 * @param component            the Strimzi component (e.g., kafka, zookeeper, entity-operator)
 * @param minAvailable         minimum available pods requirement
 * @param maxUnavailable       maximum unavailable pods allowed
 * @param currentHealthy       current number of healthy pods
 * @param desiredHealthy       desired number of healthy pods
 * @param disruptionsAllowed   number of pod disruptions currently allowed
 * @param expectedPods         expected number of pods
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record KafkaPodDisruptionBudgetInfo(
    @JsonProperty("name") String name,
    @JsonProperty("namespace") String namespace,
    @JsonProperty("component") String component,
    @JsonProperty("min_available") String minAvailable,
    @JsonProperty("max_unavailable") String maxUnavailable,
    @JsonProperty("current_healthy") Integer currentHealthy,
    @JsonProperty("desired_healthy") Integer desiredHealthy,
    @JsonProperty("disruptions_allowed") Integer disruptionsAllowed,
    @JsonProperty("expected_pods") Integer expectedPods
) {
    /**
     * Creates a new {@code KafkaPodDisruptionBudgetInfo} instance.
     *
     * @param name               the PDB resource name
     * @param namespace          the namespace
     * @param component          the Strimzi component label value
     * @param minAvailable       minimum available pods requirement
     * @param maxUnavailable     maximum unavailable pods allowed
     * @param currentHealthy     current number of healthy pods
     * @param desiredHealthy     desired number of healthy pods
     * @param disruptionsAllowed number of pod disruptions currently allowed
     * @param expectedPods       expected number of pods
     * @return a new {@code KafkaPodDisruptionBudgetInfo} instance
     */
    public static KafkaPodDisruptionBudgetInfo of(final String name, final String namespace,
                                                   final String component, final String minAvailable,
                                                   final String maxUnavailable, final Integer currentHealthy,
                                                   final Integer desiredHealthy, final Integer disruptionsAllowed,
                                                   final Integer expectedPods) {
        return new KafkaPodDisruptionBudgetInfo(name, namespace, component, minAvailable, maxUnavailable,
            currentHealthy, desiredHealthy, disruptionsAllowed, expectedPods);
    }
}
