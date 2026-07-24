/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.dto.kafkamirrormaker2;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Wrapper response for a list of KafkaMirrorMaker2 instances.
 *
 * @param items the list of KafkaMirrorMaker2 responses
 * @param count the number of items
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record KafkaMirrorMaker2ListResponse(
    @JsonProperty("items") List<KafkaMirrorMaker2Response> items,
    @JsonProperty("count") int count
) {
}
