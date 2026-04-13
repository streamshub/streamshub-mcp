/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Kubernetes events grouped by the resource they relate to.
 *
 * @param resourceKind the resource kind (e.g., Pod, Deployment, Kafka)
 * @param resourceName the resource name
 * @param events       the list of events for this resource
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ResourceEventsResult(
    @JsonProperty("resource_kind") String resourceKind,
    @JsonProperty("resource_name") String resourceName,
    @JsonProperty("events") List<EventInfo> events
) {

    /**
     * A single Kubernetes event.
     *
     * @param type      the event type (Normal, Warning)
     * @param reason    the short reason string (e.g., Scheduled, FailedMount)
     * @param message   the human-readable event message
     * @param count     the number of times this event occurred
     * @param firstTime when the event was first observed
     * @param lastTime  when the event was last observed
     * @param source    the component that generated the event
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record EventInfo(
        @JsonProperty("type") String type,
        @JsonProperty("reason") String reason,
        @JsonProperty("message") String message,
        @JsonProperty("count") Integer count,
        @JsonProperty("first_time") String firstTime,
        @JsonProperty("last_time") String lastTime,
        @JsonProperty("source") String source
    ) {
    }
}
