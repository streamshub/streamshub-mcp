/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.systemtest.resources;

import io.fabric8.kubernetes.client.CustomResource;
import io.skodjob.kubetest4j.resources.ResourceCondition;
import io.streamshub.mcp.systemtest.enums.ConditionStatus;
import io.streamshub.mcp.systemtest.enums.CustomResourceStatus;
import io.strimzi.api.kafka.model.common.Spec;
import io.strimzi.api.kafka.model.kafka.Status;

/**
 * Generic resource condition checks for Strimzi custom resources.
 */
public final class ResourceConditions {

    private ResourceConditions() {
    }

    /**
     * Returns {@link ResourceCondition} checking that the particular resource is in `Ready: True` state.
     *
     * @return  {@link ResourceCondition} with check for readiness status.
     *
     * @param <T>   CR class that extends {@link CustomResource}.
     */
    public static <T extends CustomResource<? extends Spec, ? extends Status>> ResourceCondition<T> resourceIsReady() {
        return new ResourceCondition<>(resource ->
            checkMatchingConditions(resource, CustomResourceStatus.Ready, ConditionStatus.True),
            "readiness"
        );
    }

    /**
     * Returns {@link ResourceCondition} checking that the particular resource is in desired state with condition status {@code True}.
     *
     * @param customResourceStatus desired CR status
     * @return  {@link ResourceCondition} with check for desired state
     * @param <T>   CR class that extends {@link CustomResource}
     */
    public static <T extends CustomResource<? extends Spec, ? extends Status>> ResourceCondition<T> resourceHasDesiredState(final Enum<?> customResourceStatus) {
        return resourceHasDesiredState(customResourceStatus, ConditionStatus.True);
    }

    /**
     * Returns {@link ResourceCondition} checking that the particular resource is in desired state and with desired condition status.
     *
     * @param customResourceStatus desired CR status
     * @param conditionStatus      desired condition status
     * @return  {@link ResourceCondition} with check for desired state
     * @param <T>   CR class that extends {@link CustomResource}
     */
    public static <T extends CustomResource<? extends Spec, ? extends Status>> ResourceCondition<T> resourceHasDesiredState(final Enum<?> customResourceStatus, final ConditionStatus conditionStatus) {
        return new ResourceCondition<>(resource ->
            checkMatchingConditions(resource, customResourceStatus, conditionStatus),
            String.format("%s state with condition status: %s", customResourceStatus.toString(), conditionStatus.toString())
        );
    }

    /**
     * Method returning boolean value if the particular resource contains desired CR status and condition status inside its
     * `.status.conditions` section.
     *
     * @param resource                  resource with status which should be verified.
     * @param customResourceStatus      desired CR status - for example `Ready` or `NotReady`.
     * @param conditionStatus           desired condition status - `True` or `False`.
     *
     * @return  boolean value if the particular resource contains desired CR status and condition status
     * @param <T>   CR class that extends {@link CustomResource}.
     */
    private static <T extends CustomResource<? extends Spec, ? extends Status>> boolean checkMatchingConditions(T resource, Enum<?> customResourceStatus, ConditionStatus conditionStatus) {
        if (resource.getStatus() != null && resource.getStatus().getConditions() != null) {
            return resource.getStatus()
                .getConditions()
                .stream()
                .anyMatch(condition -> condition.getType().equals(customResourceStatus.toString()) && condition.getStatus().equals(conditionStatus.toString()));
        }
        return false;
    }
}
