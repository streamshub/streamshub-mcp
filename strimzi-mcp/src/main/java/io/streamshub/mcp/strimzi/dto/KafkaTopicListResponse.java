/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Paginated response containing Kafka topics for a cluster.
 *
 * @param topics  the topics in the current page
 * @param total   the total number of topics matching the query
 * @param offset  the offset of the first topic in this page
 * @param limit   the maximum number of topics per page
 * @param hasMore whether more topics exist beyond this page
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record KafkaTopicListResponse(
    @JsonProperty("topics") List<KafkaTopicResponse> topics,
    @JsonProperty("total") int total,
    @JsonProperty("offset") int offset,
    @JsonProperty("limit") int limit,
    @JsonProperty("has_more") boolean hasMore
) {

    /**
     * Creates a paginated topic list response.
     *
     * @param topics  the topics in the current page
     * @param total   the total number of topics
     * @param offset  the offset of the first topic
     * @param limit   the maximum number of topics per page
     * @param hasMore whether more topics exist beyond this page
     * @return a new paginated topic list response
     */
    public static KafkaTopicListResponse of(final List<KafkaTopicResponse> topics,
                                             final int total,
                                             final int offset,
                                             final int limit,
                                             final boolean hasMore) {
        return new KafkaTopicListResponse(topics, total, offset, limit, hasMore);
    }
}
