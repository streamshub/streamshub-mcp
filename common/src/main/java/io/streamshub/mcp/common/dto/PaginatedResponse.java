/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Generic paginated response for list operations.
 *
 * @param items     the items in the current page
 * @param total     the total number of items matching the query, including items beyond the
 *                  configured list-size cap when {@code truncated} is true
 * @param offset    the zero-based offset of the first item in this page
 * @param limit     the maximum number of items per page
 * @param hasMore   whether more items exist beyond this page within the pageable window
 * @param truncated {@code true} when the result set exceeded the configured list-size cap, so
 *                  items beyond the cap cannot be reached by paging; null (omitted) otherwise
 * @param <T>       the item type
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PaginatedResponse<T>(
    @JsonProperty("items") List<T> items,
    @JsonProperty("total") int total,
    @JsonProperty("offset") int offset,
    @JsonProperty("limit") int limit,
    @JsonProperty("has_more") boolean hasMore,
    @JsonProperty("truncated") Boolean truncated
) {

    /**
     * Creates a paginated response for a complete (non-truncated) result set.
     *
     * @param items   the items in the current page
     * @param total   the total number of items
     * @param offset  the zero-based offset of the first item
     * @param limit   the maximum number of items per page
     * @param hasMore whether more items exist beyond this page
     * @param <T>     the item type
     * @return a new paginated response
     */
    public static <T> PaginatedResponse<T> of(final List<T> items,
                                               final int total,
                                               final int offset,
                                               final int limit,
                                               final boolean hasMore) {
        return new PaginatedResponse<>(items, total, offset, limit, hasMore, null);
    }

    /**
     * Creates a paginated response whose result set was truncated to a list-size cap.
     *
     * @param items     the items in the current page
     * @param total     the real total number of items, before truncation
     * @param offset    the zero-based offset of the first item
     * @param limit     the maximum number of items per page
     * @param hasMore   whether more items exist beyond this page within the pageable window
     * @param truncated whether the result set was truncated; {@code false} is stored as null
     * @param <T>       the item type
     * @return a new paginated response
     */
    public static <T> PaginatedResponse<T> of(final List<T> items,
                                               final int total,
                                               final int offset,
                                               final int limit,
                                               final boolean hasMore,
                                               final boolean truncated) {
        return new PaginatedResponse<>(items, total, offset, limit, hasMore, truncated ? Boolean.TRUE : null);
    }
}
