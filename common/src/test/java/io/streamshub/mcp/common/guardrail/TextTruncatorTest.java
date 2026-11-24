/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.guardrail;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link TextTruncator}: byte-exact JSON size accounting, pod-aware truncation that keeps
 * the newest whole lines of every pod, the line and character fallbacks, and the fair-share cap.
 */
class TextTruncatorTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final TextTruncator truncator = new TextTruncator(mapper);

    TextTruncatorTest() {
    }

    @Test
    void testFixtureReproducesEscapingOverhead() {
        String logs = GuardrailTestData.podLogs(12, 200);

        assertTrue(truncator.jsonSize(logs) - logs.length() > 80_000,
            "escaping overhead alone must exceed an 80 KB limit");
    }

    @Test
    void testFitWithinBudgetIsUnchanged() {
        String text = "first line\nsecond line\n";

        TextTruncator.Truncation result = truncator.fit(text, 1_000);

        assertFalse(result.changed());
        assertSame(text, result.text());
    }

    @Test
    void testFitPodLogsKeepsNewestWholeLinesOfEveryPod() throws Exception {
        TextTruncator.Truncation result = truncator.fit(GuardrailTestData.podLogs(12, 200), 60_000);

        int size = truncator.jsonSize(result.text());
        assertTrue(size <= 60_000, "size " + size);
        assertTrue(size >= 51_000, "size " + size);
        Map<String, List<String>> sections = sections(result.text());
        assertEquals(12, sections.size());
        int contentLines = 0;
        for (int p = 0; p < 12; p++) {
            List<String> lines = sections.get("=== Pod: mcp-cluster-broker-np1-" + p + " ===");
            assertTrue(lines != null, "missing pod " + p);
            assertTrue(lines.stream().anyMatch(l -> l.contains("line 199\"")), "newest line of pod " + p);
            assertTrue(lines.stream().anyMatch(l -> l.matches(
                "\\[\\.\\.\\.response truncated: \\d+ of 200 lines omitted]")), "marker of pod " + p);
            for (String line : lines) {
                if (!line.startsWith(TextTruncator.MARKER_PREFIX)) {
                    assertDoesNotThrow(() -> mapper.readTree(line), line);
                    contentLines++;
                }
            }
        }
        assertEquals(contentLines, result.keptLines());
        assertTrue(result.changed());
    }

    @Test
    void testFitPodLogsKeepsSmallSectionWhole() {
        StringBuilder text = new StringBuilder("=== Pod: a ===\n");
        for (int i = 0; i < 5; i++) {
            text.append(GuardrailTestData.viaqLine(1, i)).append('\n');
        }
        text.append("=== Pod: b ===\n");
        for (int i = 0; i < 2_000; i++) {
            text.append(GuardrailTestData.viaqLine(2, i)).append('\n');
        }

        TextTruncator.Truncation result = truncator.fit(text.toString(), 20_000);

        Map<String, List<String>> sections = sections(result.text());
        List<String> podA = sections.get("=== Pod: a ===");
        assertEquals(5, podA.size());
        assertTrue(podA.stream().noneMatch(l -> l.startsWith(TextTruncator.MARKER_PREFIX)));
        assertTrue(sections.get("=== Pod: b ===").getFirst().startsWith(TextTruncator.MARKER_PREFIX));
        assertTrue(truncator.jsonSize(result.text()) <= 20_000);
    }

    @Test
    void testFitPodLogsGivesBudgetSectionsCannotUseToOtherSections() {
        String text = GuardrailTestData.podLogs(12, 200, (pod, i) -> GuardrailTestData.viaqLine(pod, i, 400));
        int lineBytes = truncator.jsonSize(GuardrailTestData.viaqLine(0, 0, 400));
        assertEquals(4_037, lineBytes, "precondition: 4 KB records");

        TextTruncator.Truncation result = truncator.fit(text, 70_000);

        // Only whole records are kept, so at most one record of budget may stay unused.
        int size = truncator.jsonSize(result.text());
        assertTrue(size <= 70_000 && size > 70_000 - lineBytes, "size " + size);
        Map<String, List<String>> sections = sections(result.text());
        assertEquals(12, sections.size());
        for (Map.Entry<String, List<String>> section : sections.entrySet()) {
            assertTrue(section.getValue().stream().anyMatch(l -> !l.startsWith(TextTruncator.MARKER_PREFIX)),
                "no record kept for " + section.getKey());
        }
    }

    @Test
    void testFitPodLogsGivesShareOfOversizedNewestLineToOtherPods() {
        String text = GuardrailTestData.podLogs(4, 200) + GuardrailTestData.viaqLine(3, 200, 2_800) + "\n";
        int normalLine = truncator.jsonSize(GuardrailTestData.viaqLine(0, 0));

        TextTruncator.Truncation result = truncator.fit(text, 70_000);

        int size = truncator.jsonSize(result.text());
        assertTrue(size <= 70_000 && size >= 70_000 - 4 * normalLine, "size " + size);
        // Pod 3's newest record (~25.6 KB) is larger than its share: never half a record, so pod 3 keeps
        // only its header and the marker, and its share goes to the other pods.
        assertTrue(result.text().endsWith("=== Pod: mcp-cluster-broker-np1-3 ===\n"
            + "[...response truncated: 201 of 201 lines omitted]\n"), result.text().substring(
                Math.max(0, result.text().length() - 200)));
    }

    @Test
    void testFitPodLogsKeepsUnavailableHeader() {
        StringBuilder text = new StringBuilder("=== Pod: p1 ===\n");
        for (int i = 0; i < 300; i++) {
            text.append(GuardrailTestData.viaqLine(1, i)).append('\n');
        }
        text.append("=== Pod: p2 === (logs unavailable: timeout)\n");
        text.append("=== Pod: p3 ===\n");
        for (int i = 0; i < 300; i++) {
            text.append(GuardrailTestData.viaqLine(3, i)).append('\n');
        }
        text.append("--- container: kafka ---\n");
        text.append(GuardrailTestData.viaqLine(3, 300)).append('\n');

        TextTruncator.Truncation result = truncator.fit(text.toString(), 20_000);

        assertTrue(result.text().contains("=== Pod: p2 === (logs unavailable: timeout)\n"), result.text());
        assertTrue(result.text().contains("\n--- container: kafka ---\n"));
        assertTrue(truncator.jsonSize(result.text()) <= 20_000);
    }

    @Test
    void testFitFallsBackToLinesWhenHeadersAloneExceedBudget() {
        TextTruncator.Truncation result = truncator.fit(GuardrailTestData.podLogs(200, 5), 2_000);

        assertTrue(truncator.jsonSize(result.text()) <= 2_000);
        assertTrue(Pattern.compile("\\[\\.\\.\\.response truncated: \\d+ of 1200 lines omitted]$")
            .matcher(result.text().stripTrailing()).find(), result.text());
    }

    @Test
    void testFitPlainTextKeepsHeadLines() {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 10_000; i++) {
            text.append("line ").append(i).append('\n');
        }

        TextTruncator.Truncation result = truncator.fit(text.toString(), 5_000);

        assertTrue(result.text().startsWith("line 0\n"));
        assertTrue(Pattern.compile("\\[\\.\\.\\.response truncated: \\d+ of 10000 lines omitted]$")
            .matcher(result.text().stripTrailing()).find(), result.text());
        assertTrue(truncator.jsonSize(result.text()) <= 5_000);
    }

    @Test
    void testFitCountsMultiByteCharactersInBytes() {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 1_000; i++) {
            text.append("é".repeat(50)).append(" 😀\n");
        }

        TextTruncator.Truncation result = truncator.fit(text.toString(), 10_000);

        assertTrue(result.text().getBytes(StandardCharsets.UTF_8).length + 2 <= 10_000);
        assertTrue(truncator.jsonSize(result.text()) <= 10_000);
    }

    @Test
    void testFitSingleLineDoesNotSplitSurrogatePairs() {
        TextTruncator.Truncation result = truncator.fit("😀".repeat(5_000), 1_001);

        assertTrue(truncator.jsonSize(result.text()) <= 1_001);
        assertEquals(result.text(),
            new String(result.text().getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8));
        assertEquals(0, result.keptLines());
    }

    @Test
    void testFitLineLongerThanBudgetKeepsOnlyHeaderAndMarker() {
        String line = "{\"@timestamp\":\"2026-11-13T11:15:59.607345973Z\",\"message\":\"" + "x".repeat(4_950) + "\"}";
        String text = "=== Pod: p1 ===\n" + line + "\n";

        TextTruncator.Truncation result = truncator.fit(text, 1_000);

        assertEquals("=== Pod: p1 ===\n[...response truncated: 1 of 1 lines omitted]\n", result.text());
        assertEquals(0, result.keptLines());
        assertFalse(result.text().contains("{\"@timestamp"));
    }

    @Test
    void testFitNeverGrowsText() {
        String text = "abcdefghijklmnopqrstuvwxyz0123";

        TextTruncator.Truncation result = truncator.fit(text, 10);

        assertFalse(result.changed());
        assertSame(text, result.text());
    }

    private static Map<String, List<String>> sections(final String text) {
        Map<String, List<String>> sections = new LinkedHashMap<>();
        List<String> current = null;
        for (String line : text.split("\n")) {
            if (line.startsWith("=== Pod: ")) {
                current = new ArrayList<>();
                sections.put(line, current);
            } else if (current != null) {
                current.add(line);
            }
        }
        return sections;
    }
}
