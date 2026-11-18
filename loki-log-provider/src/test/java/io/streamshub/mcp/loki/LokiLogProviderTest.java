/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.loki;

import io.quarkus.test.junit.QuarkusTest;
import io.streamshub.mcp.common.dto.LogFetchRequest;
import io.streamshub.mcp.common.service.log.LogFilterUtils;
import io.streamshub.mcp.loki.util.LogQLSanitizer;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link LokiLogProvider} verifying LogQL query construction
 * and response parsing.
 */
@QuarkusTest
class LokiLogProviderTest {

    private static final Instant NOW = Instant.parse("2026-11-13T12:00:00Z");
    /** Drops records that are still JSON after line_format because their message field is empty. */
    private static final String EXCLUDE_EMPTY_MESSAGE = " !~ \"^\\\\{.*\\\"message\\\":\\\"\\\"\"";
    private static final String UNWRAP = " | json mcp_message=\"message\""
        + " | line_format \"{{if .mcp_message}}{{.mcp_message}}{{else}}{{__line__}}{{end}}\""
        + EXCLUDE_EMPTY_MESSAGE
        + " | drop mcp_message, __error__, __error_details__";
    private static final String ERRORS_FILTER =
        " |~ \"" + LogQLSanitizer.sanitizeLabelValue(LogFilterUtils.ERRORS_REGEX) + "\"";
    private static final String WARNINGS_FILTER =
        " |~ \"" + LogQLSanitizer.sanitizeLabelValue(LogFilterUtils.WARNINGS_REGEX) + "\"";

    @Inject
    LokiLogProvider lokiLogCollectorProvider;

    LokiLogProviderTest() {
    }

    @Test
    void testBuildLogQueryDefaultLabels() {
        String query = lokiLogCollectorProvider.buildLogQuery(
            "kafka-prod", "my-cluster-kafka-0", null, null, null);

        assertEquals("{namespace=\"kafka-prod\", pod=\"my-cluster-kafka-0\"}" + UNWRAP, query);
    }

    @Test
    void testExtractLogLinesFromResponse() {
        LokiResponse response = new LokiResponse("success", new LokiResponse.LokiData(
            "streams",
            List.of(new LokiResponse.LokiStream(
                Map.of("namespace", "kafka", "pod", "test-pod"),
                List.of(
                    List.of("1000000000000000000", "first line"),
                    List.of("2000000000000000000", "second line"),
                    List.of("3000000000000000000", "third line")
                )
            ))
        ));

        String result = lokiLogCollectorProvider.extractLogLines(response);

        assertNotNull(result);
        assertEquals("first line\nsecond line\nthird line\n", result);
    }

    @Test
    void testExtractLogLinesSortsChronologically() {
        LokiResponse response = new LokiResponse("success", new LokiResponse.LokiData(
            "streams",
            List.of(new LokiResponse.LokiStream(
                Map.of(),
                List.of(
                    List.of("3000000000000000000", "third"),
                    List.of("1000000000000000000", "first"),
                    List.of("2000000000000000000", "second")
                )
            ))
        ));

        String result = lokiLogCollectorProvider.extractLogLines(response);

        assertEquals("first\nsecond\nthird\n", result);
    }

    @Test
    void testExtractLogLinesMergesMultipleStreams() {
        LokiResponse response = new LokiResponse("success", new LokiResponse.LokiData(
            "streams",
            List.of(
                new LokiResponse.LokiStream(Map.of(), List.of(
                    List.of("1000000000000000000", "stream1-line1"),
                    List.of("3000000000000000000", "stream1-line2")
                )),
                new LokiResponse.LokiStream(Map.of(), List.of(
                    List.of("2000000000000000000", "stream2-line1")
                ))
            )
        ));

        String result = lokiLogCollectorProvider.extractLogLines(response);

        assertEquals("stream1-line1\nstream2-line1\nstream1-line2\n", result);
    }

    @Test
    void testExtractLogLinesReturnsNullForEmptyResponse() {
        LokiResponse response = new LokiResponse("success", new LokiResponse.LokiData(
            "streams", List.of()
        ));

        assertNull(lokiLogCollectorProvider.extractLogLines(response));
    }

    @Test
    void testExtractLogLinesReturnsNullForNullData() {
        assertNull(lokiLogCollectorProvider.extractLogLines(new LokiResponse("success", null)));
        assertNull(lokiLogCollectorProvider.extractLogLines(null));
    }

    @Test
    void testBuildLogQueryEscapesSpecialCharacters() {
        String query = lokiLogCollectorProvider.buildLogQuery(
            "ns\"} | logfmt", "pod-0", null, null, null);

        assertEquals("{namespace=\"ns\\\"} | logfmt\", pod=\"pod-0\"}" + UNWRAP, query);
    }

    @Test
    void testBuildLogQueryWithErrorsFilter() {
        String query = lokiLogCollectorProvider.buildLogQuery(
            "kafka", "pod-0", "errors", null, null);

        assertEquals("{namespace=\"kafka\", pod=\"pod-0\"}" + UNWRAP + ERRORS_FILTER, query);
        // The regex backslashes are doubled once for the LogQL string literal.
        assertTrue(query.contains("\\\\b(?:ERROR|FATAL)\\\\b"), query);
    }

    @Test
    void testBuildLogQueryWithWarningsFilter() {
        String query = lokiLogCollectorProvider.buildLogQuery(
            "kafka", "pod-0", "warnings", null, null);

        assertEquals("{namespace=\"kafka\", pod=\"pod-0\"}" + UNWRAP + WARNINGS_FILTER, query);
    }

    @Test
    void testBuildLogQueryWithCustomRegex() {
        String query = lokiLogCollectorProvider.buildLogQuery(
            "kafka", "pod-0", "OOM|timeout", null, null);

        assertEquals(
            "{namespace=\"kafka\", pod=\"pod-0\"}" + UNWRAP + " |~ \"OOM|timeout\"",
            query);
    }

    @Test
    void testBuildLogQueryWithKeywords() {
        String query = lokiLogCollectorProvider.buildLogQuery(
            "kafka", "pod-0", null, List.of("OOM", "timeout"), null);

        assertEquals(
            "{namespace=\"kafka\", pod=\"pod-0\"}" + UNWRAP + " |~ \"(?i)(OOM|timeout)\"",
            query);
    }

    @Test
    void testBuildLogQueryEscapesKeywordMetacharacters() {
        String query = lokiLogCollectorProvider.buildLogQuery(
            "kafka", "pod-0", null, List.of("a.b", "x|y", "(open"), null);

        // The keyword is escaped for RE2 first and then for the LogQL string literal,
        // so each regex backslash appears doubled in the query text.
        assertEquals(
            "{namespace=\"kafka\", pod=\"pod-0\"}" + UNWRAP + " |~ \"(?i)(a\\\\.b|x\\\\|y|\\\\(open)\"",
            query);
    }

    @Test
    void testBuildLogQueryWithFilterAndKeywords() {
        String query = lokiLogCollectorProvider.buildLogQuery(
            "kafka", "pod-0", "errors", List.of("OOM"), null);

        assertEquals(
            "{namespace=\"kafka\", pod=\"pod-0\"}" + UNWRAP + ERRORS_FILTER + " |~ \"(?i)(OOM)\"",
            query);
    }

    @Test
    void testExtractLogLinesSkipsInvalidEntries() {
        LokiResponse response = new LokiResponse("success", new LokiResponse.LokiData(
            "streams",
            List.of(new LokiResponse.LokiStream(
                Map.of(),
                List.of(
                    List.of("1000000000000000000", "valid line"),
                    List.of("only-timestamp")
                )
            ))
        ));

        String result = lokiLogCollectorProvider.extractLogLines(response);

        assertEquals("valid line\n", result);
    }

    @Test
    void testBuildLogQueryWithContainer() {
        String query = lokiLogCollectorProvider.buildLogQuery(
            "kafka-prod", "my-cluster-kafka-0", null, null, "kafka");

        assertEquals("{namespace=\"kafka-prod\", pod=\"my-cluster-kafka-0\", container=\"kafka\"}" + UNWRAP, query);
    }

    @Test
    void testBuildLogQueryBlankContainerOmitsLabel() {
        String query = lokiLogCollectorProvider.buildLogQuery(
            "kafka-prod", "my-cluster-kafka-0", null, null, "  ");

        assertEquals("{namespace=\"kafka-prod\", pod=\"my-cluster-kafka-0\"}" + UNWRAP, query);
    }

    @Test
    void testLineFiltersRunAfterUnwrap() {
        String query = lokiLogCollectorProvider.buildLogQuery(
            "kafka", "pod-0", "errors", List.of("OOM"), null);

        assertTrue(query.indexOf("| drop ") >= 0, query);
        assertTrue(query.indexOf("| drop ") < query.indexOf(" |~ "), query);
    }

    @Test
    void testRawModeOmitsUnwrapStage() {
        String query = lokiLogCollectorProvider.buildLogQuery(
            "kafka", "pod-0", "errors", null, null, null);

        assertEquals("{namespace=\"kafka\", pod=\"pod-0\"}" + ERRORS_FILTER, query);
        assertFalse(query.contains("!~"), query);
    }

    @Test
    void testCustomMessageFieldIsUsed() {
        String query = lokiLogCollectorProvider.buildLogQuery(
            "kafka", "pod-0", null, null, null, "log.message");

        assertTrue(query.contains("| json mcp_message=\"log.message\" |"), query);
        // The empty-message exclusion matches the last segment of a dotted path.
        assertTrue(query.contains(EXCLUDE_EMPTY_MESSAGE + " | drop "), query);
    }

    @Test
    void testEmptyMessageExclusionUsesCustomField() {
        String query = lokiLogCollectorProvider.buildLogQuery(
            "kafka", "pod-0", null, null, null, "msg");

        assertTrue(query.contains(" !~ \"^\\\\{.*\\\"msg\\\":\\\"\\\"\" | drop "), query);
    }

    @Test
    void testEmptyMessageRecordsExcludedBeforeLineFilters() {
        String query = lokiLogCollectorProvider.buildLogQuery(
            "kafka", "pod-0", "warnings", List.of("restricted"), null);

        int exclusion = query.indexOf(EXCLUDE_EMPTY_MESSAGE);
        assertTrue(exclusion > query.indexOf("| line_format "), query);
        assertTrue(exclusion < query.indexOf(" |~ "), query);
    }

    @Test
    void testInvalidMessageFieldIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> lokiLogCollectorProvider.buildLogQuery(
            "kafka", "pod-0", null, null, null, "msg\"} | x"));
    }

    // ---- query window ----

    @Test
    void testWindowWithoutTimeRangeOrFilterUsesMaxLookback() {
        LokiLogProvider.QueryWindow window = lokiLogCollectorProvider.resolveWindow(
            request(null, null, null, null, null), NOW);

        assertEquals(NOW.minus(Duration.ofHours(720)), window.start());
        assertEquals(NOW, window.end());
    }

    @Test
    void testWindowWithFilterAndNoTimeRangeUsesFilterLookback() {
        LokiLogProvider.QueryWindow window = lokiLogCollectorProvider.resolveWindow(
            request(null, "errors", null, null, null), NOW);

        assertEquals(NOW.minusSeconds(3600), window.start());
        assertEquals(NOW, window.end());
    }

    @Test
    void testWindowWithKeywordsOnlyUsesFilterLookback() {
        LokiLogProvider.QueryWindow window = lokiLogCollectorProvider.resolveWindow(
            request(null, null, List.of("OOM"), null, null), NOW);

        assertEquals(NOW.minusSeconds(3600), window.start());
    }

    @Test
    void testWindowBlankKeywordsCountAsUnfiltered() {
        LokiLogProvider.QueryWindow window = lokiLogCollectorProvider.resolveWindow(
            request(null, null, List.of("  "), null, null), NOW);

        assertEquals(NOW.minus(Duration.ofHours(720)), window.start());
    }

    @Test
    void testWindowSinceSecondsWinsOverLookbacks() {
        LokiLogProvider.QueryWindow window = lokiLogCollectorProvider.resolveWindow(
            request(300, "errors", null, null, null), NOW);

        assertEquals(NOW.minusSeconds(300), window.start());
        assertEquals(NOW, window.end());
    }

    @Test
    void testWindowAbsoluteRange() {
        LokiLogProvider.QueryWindow window = lokiLogCollectorProvider.resolveWindow(
            request(null, "errors", null, "2026-11-13T10:00:00Z", "2026-11-13T11:00:00Z"), NOW);

        assertEquals(Instant.parse("2026-11-13T10:00:00Z"), window.start());
        assertEquals(Instant.parse("2026-11-13T11:00:00Z"), window.end());
    }

    @Test
    void testWindowStartTimeOnlyEndsNow() {
        LokiLogProvider.QueryWindow window = lokiLogCollectorProvider.resolveWindow(
            request(null, null, null, "2026-11-13T10:00:00Z", null), NOW);

        assertEquals(Instant.parse("2026-11-13T10:00:00Z"), window.start());
        assertEquals(NOW, window.end());
    }

    @Test
    void testSearchScopeNoteOnlyForFilteredQueriesWithoutTimeRange() {
        String note = lokiLogCollectorProvider.searchScopeNote(request(null, "errors", null, null, null));
        assertNotNull(note);
        assertTrue(note.contains("last 60 minutes"), note);

        assertNull(lokiLogCollectorProvider.searchScopeNote(request(300, "errors", null, null, null)));
        assertNull(lokiLogCollectorProvider.searchScopeNote(request(null, null, null, null, null)));
        assertNull(lokiLogCollectorProvider.searchScopeNote(
            request(null, "errors", null, "2026-11-13T10:00:00Z", "2026-11-13T11:00:00Z")));
    }

    private static LogFetchRequest request(final Integer sinceSeconds, final String filter,
                                           final List<String> keywords, final String startTime,
                                           final String endTime) {
        return new LogFetchRequest("kafka", "pod-0", 100, sinceSeconds, null, filter, keywords,
            startTime, endTime, null);
    }
}
