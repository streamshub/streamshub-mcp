/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.util;

import io.quarkiverse.mcp.server.McpException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit tests for {@link TimeRangeValidator}, covering every validation branch.
 */
class TimeRangeValidatorTest {

    TimeRangeValidatorTest() {
    }

    @Test
    void testRejectsBothRelativeAndAbsolute() {
        assertThrows(McpException.class, () -> TimeRangeValidator.validateTimeRangeParameters(
            30, "2026-01-01T00:00:00Z", "2026-01-01T01:00:00Z"));
    }

    @Test
    void testRejectsZeroRangeMinutes() {
        assertThrows(McpException.class, () -> TimeRangeValidator.validateTimeRangeParameters(
            0, null, null));
    }

    @Test
    void testRejectsNegativeRangeMinutes() {
        assertThrows(McpException.class, () -> TimeRangeValidator.validateTimeRangeParameters(
            -5, null, null));
    }

    @Test
    void testRejectsStartWithoutEnd() {
        assertThrows(McpException.class, () -> TimeRangeValidator.validateTimeRangeParameters(
            null, "2026-01-01T00:00:00Z", null));
    }

    @Test
    void testRejectsEndWithoutStart() {
        assertThrows(McpException.class, () -> TimeRangeValidator.validateTimeRangeParameters(
            null, null, "2026-01-01T01:00:00Z"));
    }

    @Test
    void testRejectsStartEqualToEnd() {
        assertThrows(McpException.class, () -> TimeRangeValidator.validateTimeRangeParameters(
            null, "2026-01-01T00:00:00Z", "2026-01-01T00:00:00Z"));
    }

    @Test
    void testRejectsStartAfterEnd() {
        assertThrows(McpException.class, () -> TimeRangeValidator.validateTimeRangeParameters(
            null, "2026-01-01T02:00:00Z", "2026-01-01T01:00:00Z"));
    }

    @Test
    void testRejectsUnparseableIso8601() {
        assertThrows(McpException.class, () -> TimeRangeValidator.validateTimeRangeParameters(
            null, "not-a-date", "also-bad"));
    }

    @Test
    void testAcceptsRelativeOnly() {
        assertDoesNotThrow(() -> TimeRangeValidator.validateTimeRangeParameters(30, null, null));
    }

    @Test
    void testAcceptsValidAbsoluteRange() {
        assertDoesNotThrow(() -> TimeRangeValidator.validateTimeRangeParameters(
            null, "2026-01-01T00:00:00Z", "2026-01-01T01:00:00Z"));
    }

    @Test
    void testAcceptsAllNull() {
        assertDoesNotThrow(() -> TimeRangeValidator.validateTimeRangeParameters(null, null, null));
    }
}
