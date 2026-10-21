/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.dto.kafkatopic;

import io.streamshub.mcp.common.config.KubernetesConstants;

/**
 * Allowed values for the {@code list_kafka_topics} {@code statusFilter} argument.
 *
 * <p>Rendered as a JSON-schema {@code enum} on the tool input. Each readiness value matches
 * exactly one status computed by the topic service (so {@code NOT_READY} does not absorb
 * {@code ERROR}/{@code UNKNOWN}); {@code UNMANAGED} is orthogonal to readiness and matches
 * topics annotated {@code strimzi.io/managed=false}.</p>
 */
public enum TopicStatusFilter {

    /** Matches topics whose status is {@code Ready}. */
    READY(KubernetesConstants.ResourceStatus.READY),
    /** Matches topics whose status is {@code NotReady} (not {@code Error}/{@code Unknown}). */
    NOT_READY(KubernetesConstants.ResourceStatus.NOT_READY),
    /** Matches topics whose status is {@code Error} (a {@code Ready=False} condition). */
    ERROR(KubernetesConstants.ResourceStatus.ERROR),
    /** Matches topics whose status is {@code Unknown} (no conditions). */
    UNKNOWN(KubernetesConstants.ResourceStatus.UNKNOWN),
    /** Matches unmanaged topics ({@code strimzi.io/managed=false}), regardless of status. */
    UNMANAGED(null);

    private final String matchingStatus;

    TopicStatusFilter(final String matchingStatus) {
        this.matchingStatus = matchingStatus;
    }

    /**
     * Test whether a topic matches this filter.
     *
     * @param topic the topic response to test
     * @return true if the topic's status (or managed state, for {@code UNMANAGED}) matches
     */
    public boolean matches(final KafkaTopicResponse topic) {
        if (this == UNMANAGED) {
            return Boolean.FALSE.equals(topic.managed());
        }
        return matchingStatus.equals(topic.status());
    }
}
