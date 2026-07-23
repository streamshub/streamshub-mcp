/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.dto.draincleaner;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Wrapper response for a list of Drain Cleaner deployments.
 *
 * @param items the list of Drain Cleaner responses
 * @param count the number of items
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DrainCleanerListResponse(
    @JsonProperty("items") List<DrainCleanerResponse> items,
    @JsonProperty("count") int count
) {
}
