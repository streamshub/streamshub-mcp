/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.util;

import io.streamshub.mcp.common.dto.PaginatedResponse;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link PaginationUtils}.
 */
class PaginationUtilsTest {

    PaginationUtilsTest() {
    }

    @Test
    void defaultOffsetAndLimit() {
        List<String> items = List.of("a", "b", "c");

        PaginatedResponse<String> result = PaginationUtils.paginate(items, null, null, 100, 5000);

        assertEquals(3, result.items().size());
        assertEquals(3, result.total());
        assertEquals(0, result.offset());
        assertEquals(100, result.limit());
        assertFalse(result.hasMore());
    }

    @Test
    void customOffsetAndLimit() {
        List<String> items = IntStream.range(0, 20).mapToObj(i -> "item-" + i).toList();

        PaginatedResponse<String> result = PaginationUtils.paginate(items, 5, 3, 100, 5000);

        assertEquals(3, result.items().size());
        assertEquals("item-5", result.items().get(0));
        assertEquals("item-7", result.items().get(2));
        assertEquals(20, result.total());
        assertEquals(5, result.offset());
        assertEquals(3, result.limit());
        assertTrue(result.hasMore());
    }

    @Test
    void offsetBeyondTotalReturnsEmptyPage() {
        List<String> items = List.of("a", "b");

        PaginatedResponse<String> result = PaginationUtils.paginate(items, 10, 5, 100, 5000);

        assertTrue(result.items().isEmpty());
        assertEquals(2, result.total());
        assertFalse(result.hasMore());
    }

    @Test
    void negativeOffsetClampedToZero() {
        List<String> items = List.of("a", "b", "c");

        PaginatedResponse<String> result = PaginationUtils.paginate(items, -5, null, 100, 5000);

        assertEquals(0, result.offset());
        assertEquals(3, result.items().size());
    }

    @Test
    void hasMoreBoundary() {
        List<String> items = IntStream.range(0, 10).mapToObj(i -> "item-" + i).toList();

        PaginatedResponse<String> exactFit = PaginationUtils.paginate(items, 0, 10, 100, 5000);
        assertFalse(exactFit.hasMore());

        PaginatedResponse<String> oneShort = PaginationUtils.paginate(items, 0, 9, 100, 5000);
        assertTrue(oneShort.hasMore());
    }

    @Test
    void maxListSizeTruncation() {
        List<String> items = new ArrayList<>(IntStream.range(0, 200).mapToObj(i -> "item-" + i).toList());

        PaginatedResponse<String> result = PaginationUtils.paginate(items, 0, 50, 100, 100);

        assertEquals(100, result.total());
        assertEquals(50, result.items().size());
        assertTrue(result.hasMore());
    }

    @Test
    void emptyInputList() {
        PaginatedResponse<String> result = PaginationUtils.paginate(List.of(), null, null, 100, 5000);

        assertTrue(result.items().isEmpty());
        assertEquals(0, result.total());
        assertFalse(result.hasMore());
    }
}
