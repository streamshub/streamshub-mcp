/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.service.log;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link LogFilterUtils#applyFilters}, covering filter shortcuts,
 * untrusted-regex handling (over-length and invalid), keyword matching, and empty results.
 */
class LogFilterUtilsTest {

    private static final String LOGS = String.join("\n",
        "INFO all good",
        "ERROR something failed",
        "WARN be careful",
        "DEBUG trace line");

    LogFilterUtilsTest() {
    }

    @Test
    void testNullRawLogsReturnedAsIs() {
        assertNull(LogFilterUtils.applyFilters(null, "errors", List.of("x")));
    }

    @Test
    void testEmptyRawLogsReturnedAsIs() {
        assertEquals("", LogFilterUtils.applyFilters("", "errors", List.of("x")));
    }

    @Test
    void testNoFilterNoKeywordsReturnsRawUnchanged() {
        assertEquals(LOGS, LogFilterUtils.applyFilters(LOGS, null, null));
        assertEquals(LOGS, LogFilterUtils.applyFilters(LOGS, "   ", List.of()));
    }

    @Test
    void testErrorsShortcutMatchesErrorAndException() {
        String result = LogFilterUtils.applyFilters(
            "ERROR boom\njava.lang.NullPointerException here\nINFO fine", "errors", null);
        assertTrue(result.contains("ERROR boom"));
        assertTrue(result.contains("NullPointerException"));
        assertFalse(result.contains("INFO fine"));
    }

    @Test
    void testWarningsShortcutAlsoMatchesWarn() {
        String result = LogFilterUtils.applyFilters(LOGS, "warnings", null);
        assertTrue(result.contains("ERROR something failed"));
        assertTrue(result.contains("WARN be careful"));
        assertFalse(result.contains("INFO all good"));
        assertFalse(result.contains("DEBUG trace line"));
    }

    @Test
    void testCustomRegexFilters() {
        String result = LogFilterUtils.applyFilters(LOGS, "be care", null);
        assertTrue(result.contains("WARN be careful"));
        assertFalse(result.contains("ERROR something failed"));
    }

    @Test
    void testOverLengthRegexReturnsUnfiltered() {
        String longFilter = "a".repeat(201);
        String result = LogFilterUtils.applyFilters(LOGS, longFilter, null);
        // null pattern => every line passes the filter; no keywords => raw lines retained
        assertTrue(result.contains("INFO all good"));
        assertTrue(result.contains("DEBUG trace line"));
    }

    @Test
    void testInvalidRegexReturnsUnfiltered() {
        String result = LogFilterUtils.applyFilters(LOGS, "[unclosed", null);
        assertTrue(result.contains("INFO all good"));
        assertTrue(result.contains("ERROR something failed"));
    }

    @Test
    void testFilterAndKeywordAreAnded() {
        // filter keeps ERROR + WARN lines; keyword "careful" then keeps only the WARN line
        String result = LogFilterUtils.applyFilters(LOGS, "warnings", List.of("careful"));
        assertTrue(result.contains("WARN be careful"));
        assertFalse(result.contains("ERROR something failed"));
    }

    @Test
    void testKeywordMatchIsCaseInsensitive() {
        String result = LogFilterUtils.applyFilters(LOGS, null, List.of("error"));
        assertTrue(result.contains("ERROR something failed"));
        assertFalse(result.contains("INFO all good"));
    }

    @Test
    void testAllLinesFilteredReturnsNull() {
        assertNull(LogFilterUtils.applyFilters(LOGS, "NOTHING_MATCHES_THIS", null));
    }
}
