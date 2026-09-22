/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Reconciliation generation and status tracking information for Kubernetes custom resources.
 *
 * @param generation         the metadata.generation of the custom resource
 * @param observedGeneration the status.observedGeneration reconciled by the operator
 * @param upToDate           whether observedGeneration matches generation (null if status is absent)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReconciliationInfo(
    @JsonProperty("generation") Long generation,
    @JsonProperty("observed_generation") Long observedGeneration,
    @JsonProperty("up_to_date") Boolean upToDate
) {
    /**
     * Creates a ReconciliationInfo instance from metadata generation and status observed generation.
     *
     * @param generation         the metadata generation
     * @param observedGeneration the status observed generation (null if status is absent)
     * @return a new ReconciliationInfo instance
     */
    public static ReconciliationInfo of(final Long generation, final Long observedGeneration) {
        Boolean upToDate = (generation != null && observedGeneration != null)
            ? generation.equals(observedGeneration)
            : null;
        return new ReconciliationInfo(generation, observedGeneration, upToDate);
    }

    /**
     * Creates a ReconciliationInfo instance from a Kubernetes {@code status.observedGeneration} field, which is
     * a primitive {@code long} defaulting to {@code 0} both when the operator has never reconciled the resource
     * and when it genuinely observed generation zero. Treats {@code 0} as "not yet observed" rather than
     * reporting it as a stalled reconciliation.
     *
     * @param generation         the metadata generation
     * @param observedGeneration the raw {@code status.observedGeneration} value (0 if never reconciled)
     * @return a new ReconciliationInfo instance
     */
    public static ReconciliationInfo ofStatus(final Long generation, final long observedGeneration) {
        return of(generation, observedGeneration > 0 ? observedGeneration : null);
    }
}
