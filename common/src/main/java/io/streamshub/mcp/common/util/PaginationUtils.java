/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.util;

import io.streamshub.mcp.common.dto.PaginatedResponse;
import org.jboss.logging.Logger;

import java.util.List;

/**
 * Utility for paginating a list of items with offset/limit semantics.
 */
public final class PaginationUtils {

    private static final Logger LOG = Logger.getLogger(PaginationUtils.class);

    private PaginationUtils() {
    }

    /**
     * Paginates a list of items.
     *
     * @param allItems        the full list of items to paginate
     * @param offset          zero-based offset, or null for 0
     * @param limit           maximum items to return, or null for {@code defaultPageSize}
     * @param defaultPageSize the default page size when limit is null
     * @param maxListSize     the maximum total items to consider (excess items are truncated with a warning)
     * @param <T>             the item type
     * @return a paginated response containing the requested page
     */
    public static <T> PaginatedResponse<T> paginate(final List<T> allItems,
                                                     final Integer offset,
                                                     final Integer limit,
                                                     final int defaultPageSize,
                                                     final int maxListSize) {
        List<T> items = allItems;

        if (items.size() > maxListSize) {
            LOG.warnf("List size %d exceeds maximum of %d, truncating", items.size(), maxListSize);
            items = items.subList(0, maxListSize);
        }

        int effectiveOffset = offset != null ? Math.max(0, offset) : 0;
        int effectiveLimit = limit != null ? Math.max(1, limit) : defaultPageSize;

        int total = items.size();
        int fromIndex = Math.min(effectiveOffset, total);
        int toIndex = Math.min(effectiveOffset + effectiveLimit, total);
        boolean hasMore = toIndex < total;

        List<T> page = items.subList(fromIndex, toIndex);

        return PaginatedResponse.of(page, total, effectiveOffset, effectiveLimit, hasMore);
    }
}
