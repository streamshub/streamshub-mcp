/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.dto.operator;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.streamshub.mcp.common.dto.ResourceEventsResult;

import java.time.Instant;
import java.util.List;
/**
 * Response containing Kubernetes events for a Strimzi resource
 * and its related pods (plus PVCs and node pools for Kafka clusters).
 *
 * @param resourceName the Strimzi resource name
 * @param namespace   the Kubernetes namespace
 * @param totalEvents the total number of events found
 * @param resources   events grouped by resource
 * @param timestamp   the time this result was generated
 * @param message     a human-readable summary
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record StrimziEventsResponse(
    @JsonProperty("resource_name") String resourceName,
    @JsonProperty("namespace") String namespace,
    @JsonProperty("total_events") int totalEvents,
    @JsonProperty("resources") List<ResourceEventsResult> resources,
    @JsonProperty("timestamp") Instant timestamp,
    @JsonProperty("message") String message
) {

    /**
     * Creates a response with events grouped by resource.
     *
     * @param resourceName the Strimzi resource name
     * @param namespace   the Kubernetes namespace
     * @param resources   events grouped by resource
     * @return a response with event data
     */
    public static StrimziEventsResponse of(String resourceName, String namespace,
                                            List<ResourceEventsResult> resources) {
        int total = resources.stream().mapToInt(r -> r.events().size()).sum();
        long resourceCount = resources.stream().filter(r -> !r.events().isEmpty()).count();
        String msg = total > 0
            ? String.format("Found %d events across %d resources for resource '%s'",
                total, resourceCount, resourceName)
            : String.format("No events found for resource '%s'", resourceName);
        return new StrimziEventsResponse(resourceName, namespace, total, resources,
            Instant.now(), msg);
    }

    /**
     * Creates an empty response when no events are found.
     *
     * @param resourceName the Strimzi resource name
     * @param namespace   the Kubernetes namespace
     * @return an empty response
     */
    public static StrimziEventsResponse empty(String resourceName, String namespace) {
        return new StrimziEventsResponse(resourceName, namespace, 0, List.of(),
            Instant.now(),
            String.format("No events found for resource '%s' in namespace '%s'",
                resourceName, namespace));
    }
}
