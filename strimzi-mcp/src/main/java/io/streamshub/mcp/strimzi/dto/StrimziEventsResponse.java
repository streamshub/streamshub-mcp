/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.streamshub.mcp.common.dto.ResourceEventsResult;

import java.time.Instant;
import java.util.List;

/**
 * Response containing Kubernetes events for a Strimzi Kafka cluster
 * and its related resources (pods, PVCs, node pools).
 *
 * @param clusterName the Kafka cluster name
 * @param namespace   the Kubernetes namespace
 * @param totalEvents the total number of events found
 * @param resources   events grouped by resource
 * @param timestamp   the time this result was generated
 * @param message     a human-readable summary
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record StrimziEventsResponse(
    @JsonProperty("cluster_name") String clusterName,
    @JsonProperty("namespace") String namespace,
    @JsonProperty("total_events") int totalEvents,
    @JsonProperty("resources") List<ResourceEventsResult> resources,
    @JsonProperty("timestamp") Instant timestamp,
    @JsonProperty("message") String message
) {

    /**
     * Creates a response with events grouped by resource.
     *
     * @param clusterName the Kafka cluster name
     * @param namespace   the Kubernetes namespace
     * @param resources   events grouped by resource
     * @return a response with event data
     */
    public static StrimziEventsResponse of(String clusterName, String namespace,
                                            List<ResourceEventsResult> resources) {
        int total = resources.stream().mapToInt(r -> r.events().size()).sum();
        long resourceCount = resources.stream().filter(r -> !r.events().isEmpty()).count();
        String msg = total > 0
            ? String.format("Found %d events across %d resources for cluster '%s'",
                total, resourceCount, clusterName)
            : String.format("No events found for cluster '%s'", clusterName);
        return new StrimziEventsResponse(clusterName, namespace, total, resources,
            Instant.now(), msg);
    }

    /**
     * Creates an empty response when no events are found.
     *
     * @param clusterName the Kafka cluster name
     * @param namespace   the Kubernetes namespace
     * @return an empty response
     */
    public static StrimziEventsResponse empty(String clusterName, String namespace) {
        return new StrimziEventsResponse(clusterName, namespace, 0, List.of(),
            Instant.now(),
            String.format("No events found for cluster '%s' in namespace '%s'",
                clusterName, namespace));
    }
}
