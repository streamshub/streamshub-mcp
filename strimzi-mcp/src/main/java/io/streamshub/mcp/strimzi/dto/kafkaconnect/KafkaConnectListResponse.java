/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.dto.kafkaconnect;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Wrapper response for a list of KafkaConnect clusters.
 *
 * @param items the list of KafkaConnect responses
 * @param count the number of items
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record KafkaConnectListResponse(
    @JsonProperty("items") List<KafkaConnectResponse> items,
    @JsonProperty("count") int count
) {
}
