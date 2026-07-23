/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.dto.kafkanodepool;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.streamshub.mcp.common.dto.PodSummaryResponse;

import java.util.List;

/**
 * Wrapper response for listing KafkaNodePool pods with item count.
 *
 * @param items the list of pod info summaries
 * @param count the number of items
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record KafkaNodePoolPodsResponse(
    @JsonProperty("items") List<PodSummaryResponse.PodInfo> items,
    @JsonProperty("count") int count
) {
}
