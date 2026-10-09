/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.dto.strimzipodset;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Wrapper response for listing StrimziPodSets with item count.
 *
 * @param items the list of StrimziPodSet responses
 * @param count the number of items
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record StrimziPodSetListResponse(
    @JsonProperty("items") List<StrimziPodSetResponse> items,
    @JsonProperty("count") int count
) {
}
