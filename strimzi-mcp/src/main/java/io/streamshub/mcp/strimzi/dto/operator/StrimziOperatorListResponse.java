/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.dto.operator;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Wrapper response for a list of Strimzi operators.
 *
 * @param items the list of Strimzi operator responses
 * @param count the number of items
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record StrimziOperatorListResponse(
    @JsonProperty("items") List<StrimziOperatorResponse> items,
    @JsonProperty("count") int count
) {
}
