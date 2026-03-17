/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Kafka listener information including name, type, and bootstrap address.
 *
 * @param name             the listener name
 * @param type             the listener type (e.g., internal, route, loadbalancer)
 * @param bootstrapAddress the bootstrap address for this listener
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ListenerInfo(
    @JsonProperty("name") String name,
    @JsonProperty("type") String type,
    @JsonProperty("bootstrap_address") String bootstrapAddress
) {
    /**
     * Creates a listener info from the given parameters.
     *
     * @param name             the listener name
     * @param type             the listener type
     * @param bootstrapAddress the bootstrap address
     * @return a new ListenerInfo instance
     */
    public static ListenerInfo of(final String name, final String type,
                                  final String bootstrapAddress) {
        return new ListenerInfo(name, type, bootstrapAddress);
    }
}
