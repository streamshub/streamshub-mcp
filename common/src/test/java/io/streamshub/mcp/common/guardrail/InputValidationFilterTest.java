/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.guardrail;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Unit tests for {@link InputValidationFilter}.
 */
class InputValidationFilterTest {

    private final InputValidationFilter filter = new InputValidationFilter();

    InputValidationFilterTest() {
    }

    @Test
    void testStripsControlCharacters() {
        Object[] params = new Object[]{"hello\u0000world", "clean"};
        Object[] result = filter.filterInput("test_tool", params);
        assertEquals("helloworld", result[0]);
        assertEquals("clean", result[1]);
    }

    @Test
    void testPreservesNewlinesAndTabs() {
        Object[] params = new Object[]{"line1\nline2\ttab\rreturn"};
        Object[] result = filter.filterInput("test_tool", params);
        assertEquals("line1\nline2\ttab\rreturn", result[0]);
    }

    @Test
    void testPassesThroughNonStringParams() {
        Object[] params = new Object[]{"text", 42, true, null};
        Object[] result = filter.filterInput("test_tool", params);
        assertArrayEquals(params, result);
    }

    @Test
    void testDoesNotAllocateWhenUnchanged() {
        Object[] params = new Object[]{"clean", "text"};
        Object[] result = filter.filterInput("test_tool", params);
        assertSame(params, result);
    }

    @Test
    void testStripControlCharsStatic() {
        assertEquals("clean", InputValidationFilter.stripControlChars("clean"));
        assertEquals("ab", InputValidationFilter.stripControlChars("a\u0007b"));
        assertNull(InputValidationFilter.stripControlChars(null));
    }
}
