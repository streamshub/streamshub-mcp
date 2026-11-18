/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.service.log;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

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

    @Test
    void testErrorsShortcutIgnoresErrorsConfigKeys() {
        assertNull(LogFilterUtils.applyFilters(LogLineFixtures.CONNECT_CONFIG_DUMP, "errors", null));
    }

    @Test
    void testErrorsShortcutMatchesLevelAndExceptionNotStackFrames() {
        String input = String.join("\n", LogLineFixtures.ERROR_LINE, LogLineFixtures.EXCEPTION_LINE,
            LogLineFixtures.STACK_FRAME, LogLineFixtures.WARN_LINE);

        String result = LogFilterUtils.applyFilters(input, "errors", null);

        assertEquals(LogLineFixtures.ERROR_LINE + "\n" + LogLineFixtures.EXCEPTION_LINE + "\n", result);
    }

    @Test
    void testWarningsShortcutIgnoresConfigDump() {
        String input = LogLineFixtures.BROKER_CONFIG_LINE + "\n" + LogLineFixtures.CONNECT_CONFIG_DUMP
            + LogLineFixtures.WARN_LINE;

        String result = LogFilterUtils.applyFilters(input, "warnings", null);

        assertEquals(LogLineFixtures.WARN_LINE + "\n", result);
    }

    @Test
    void testIsErrorLine() {
        assertTrue(LogFilterUtils.isErrorLine(LogLineFixtures.ERROR_LINE));
        assertTrue(LogFilterUtils.isErrorLine(LogLineFixtures.EXCEPTION_LINE));
        assertTrue(LogFilterUtils.isErrorLine(LogLineFixtures.OOM_LINE));
        assertTrue(LogFilterUtils.isErrorLine(LogLineFixtures.GO_ERROR_LINE));

        for (String configLine : LogLineFixtures.CONNECT_CONFIG_DUMP.split("\n")) {
            assertFalse(LogFilterUtils.isErrorLine(configLine), configLine);
        }
        assertFalse(LogFilterUtils.isErrorLine(LogLineFixtures.STACK_FRAME));
        assertFalse(LogFilterUtils.isErrorLine(LogLineFixtures.WARN_LINE));
        assertFalse(LogFilterUtils.isErrorLine(LogLineFixtures.BROKER_CONFIG_LINE));
    }

    /**
     * One table for the {@code errors} and {@code warnings} rules: the line, whether it counts as
     * an error, whether the {@code warnings} filter matches it.
     *
     * @return the rule cases
     */
    static Stream<Arguments> errorRuleCases() {
        return Stream.of(
            // ERROR/FATAL level tokens, in any case
            Arguments.of("2026-11-13 11:02:44,512 ERROR [ControllerApis nodeId=8] Unexpected error handling request",
                true, true),
            Arguments.of("level=error msg=\"cannot connect to broker\"", true, true),
            Arguments.of("{\"level\":\"ERROR\",\"message\":\"Failed to connect to broker\"}", true, true),
            Arguments.of("2026-11-13 11:00:00,000 FATAL [main] Exiting Kafka", true, true),
            Arguments.of("2026-11-13 11:00:00,000 INFO [main] Unexpected error, retrying", true, true),
            // Exception and error class names (case-sensitive suffix)
            Arguments.of("org.apache.kafka.common.errors.TimeoutException: Timed out waiting for a node assignment",
                true, true),
            Arguments.of("Caused by: java.io.IOException: Broken pipe", true, true),
            Arguments.of("java.lang.OutOfMemoryError: Java heap space", true, true),
            // WARN/WARNING level tokens: warnings only
            Arguments.of("2026-11-13 11:05:01,002 WARN [RaftManager id=8] Connection to node 3 could not be established",
                false, true),
            Arguments.of("WARNING: An illegal reflective access operation has occurred", false, true),
            // Config keys, identifiers, stack frames and plain INFO lines
            Arguments.of("\terrors.tolerance = none", false, false),
            Arguments.of("\terrors.deadletterqueue.topic.name = dlq", false, false),
            Arguments.of("\tat org.apache.kafka.clients.admin.KafkaAdminClient$Call.fail(KafkaAdminClient.java:1065)",
                false, false),
            Arguments.of("2026-11-13 11:00:00,000 INFO [main] Reported error_count=0", false, false),
            Arguments.of("2026-11-13 11:00:00,000 INFO [main] Kafka Server started", false, false),
            // Counted by the old substring rule, not any more: "exception" is not a level token,
            // and only a class-name suffix in this exact case counts
            Arguments.of("Caught exception while polling, retrying", false, false),
            Arguments.of("EXCEPTION IN THREAD main", false, false),
            // Known gap (also in the old rule): klog severity letters are not level tokens
            Arguments.of("E1118 10:00:00.000000       1 main.go:42] cannot connect to broker", false, false)
        );
    }

    @ParameterizedTest(name = "[{index}] error={1} warning={2}: {0}")
    @MethodSource("errorRuleCases")
    void testErrorAndWarningRules(final String line, final boolean error, final boolean warning) {
        assertEquals(error, LogFilterUtils.isErrorLine(line), "isErrorLine: " + line);
        assertEquals(error, LogFilterUtils.applyFilters(line, "errors", null) != null, "errors filter: " + line);
        assertEquals(warning, LogFilterUtils.applyFilters(line, "warnings", null) != null, "warnings filter: " + line);
    }

    @Test
    void testHasLineFilters() {
        assertTrue(LogFilterUtils.hasLineFilters("errors", null));
        assertTrue(LogFilterUtils.hasLineFilters("OOM|timeout", List.of()));
        assertTrue(LogFilterUtils.hasLineFilters(null, List.of("  ", "OOM")));

        assertFalse(LogFilterUtils.hasLineFilters(null, null));
        assertFalse(LogFilterUtils.hasLineFilters("  ", List.of()));
        assertFalse(LogFilterUtils.hasLineFilters(null, Arrays.asList(" ", null)));
    }

    @Test
    void testResolveFilterRegex() {
        assertNull(LogFilterUtils.resolveFilterRegex(null));
        assertNull(LogFilterUtils.resolveFilterRegex("  "));
        assertEquals(LogFilterUtils.ERRORS_REGEX, LogFilterUtils.resolveFilterRegex(" Errors "));
        assertEquals(LogFilterUtils.WARNINGS_REGEX, LogFilterUtils.resolveFilterRegex("WARNINGS"));
        assertEquals("OOM|timeout", LogFilterUtils.resolveFilterRegex(" OOM|timeout "));
    }
}
