/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.guardrail;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;
import io.streamshub.mcp.common.service.log.LogCollectionService;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Fits one text into a byte budget, measured as the size of the text serialized as a JSON string
 * literal (quotes and escaping included), and cuts only at line boundaries where it can.
 *
 * <ul>
 *   <li><b>Pod logs</b> (the text starts with {@link LogCollectionService#POD_HEADER_PREFIX}): every
 *       pod section keeps its header and its newest whole lines. The budget is shared with
 *       {@link FairShare}: a small section stays whole, and budget a section cannot use goes to the
 *       others. A section whose newest line is larger than its share keeps only its header and marker.</li>
 *   <li><b>Lines</b> (two or more lines, or pod logs whose headers alone do not fit): keeps the first
 *       whole lines.</li>
 *   <li><b>Characters</b> (a single line): keeps the longest prefix that fits, never splitting a
 *       surrogate pair.</li>
 * </ul>
 *
 * <p>Every cut is marked with a line that starts with {@link #MARKER_PREFIX}. Sizes are exact because
 * JSON escaping works per character: a line followed by its newline costs exactly
 * {@code jsonSize(line)} bytes inside the serialized string (the two quote bytes become the two bytes
 * of the escaped newline).</p>
 */
final class TextTruncator {

    /** Start of every truncation marker line. */
    static final String MARKER_PREFIX = "[...response truncated: ";

    private static final int QUOTES = 2;

    private final ObjectMapper mapper;

    /**
     * Create a truncator that measures sizes with the given mapper.
     *
     * @param mapper the mapper that serializes responses
     */
    TextTruncator(final ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * Result of {@link #fit(String, int)}.
     *
     * @param text      the fitted text, or the input when it was not changed
     * @param keptLines the original content lines left whole in {@code text} (headers and markers do not
     *                  count; character mode reports 0)
     * @param changed   whether the text was shortened
     */
    record Truncation(String text, int keptLines, boolean changed) {
    }

    /**
     * Byte length of a text serialized as a JSON string literal, quotes included.
     *
     * @param text the text
     * @return the serialized size in bytes
     */
    int jsonSize(final String text) {
        try {
            return mapper.writeValueAsBytes(TextNode.valueOf(text)).length;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize text to measure its size", e);
        }
    }

    /**
     * Fit a text into {@code maxBytes}. A text that already fits is returned unchanged; a result is
     * never at least as large as the input (the input is returned unchanged instead). The result fits
     * whenever the chosen mode's minimum (headers and markers, or the marker alone) fits.
     *
     * @param text     the text
     * @param maxBytes the budget for the serialized text, quotes included
     * @return the truncation result
     */
    Truncation fit(final String text, final int maxBytes) {
        boolean trailingNewline = text.endsWith("\n");
        List<String> lines = splitLines(text, trailingNewline);
        int originalSize = jsonSize(text);
        if (originalSize <= maxBytes) {
            return new Truncation(text, contentLineCount(lines), false);
        }
        Truncation result = null;
        if (text.startsWith(LogCollectionService.POD_HEADER_PREFIX)) {
            result = fitPodLogs(lines, trailingNewline, maxBytes);
        }
        if (result == null && lines.size() >= 2) {
            result = fitLines(lines, trailingNewline, maxBytes);
        }
        if (result == null) {
            result = fitCharacters(lines.getFirst(), trailingNewline, maxBytes);
        }
        if (jsonSize(result.text()) >= originalSize) {
            return new Truncation(text, contentLineCount(lines), false);
        }
        return result;
    }

    private Truncation fitPodLogs(final List<String> lines, final boolean trailingNewline, final int maxBytes) {
        List<Section> sections = sections(lines);
        long budget = maxBytes - QUOTES;
        for (Section section : sections) {
            budget -= jsonSize(section.header);
            int total = section.content.size();
            if (total > 0) {
                budget -= jsonSize(lineMarker(total, total));
            }
        }
        if (budget < 0) {
            return null;
        }
        long[] sizes = new long[sections.size()];
        for (int i = 0; i < sizes.length; i++) {
            sizes[i] = Arrays.stream(sections.get(i).costs).sum();
        }
        long[] allowance = FairShare.allocate(sizes, budget, (i, cap) -> sections.get(i).usedWithin(cap));
        List<String> out = new ArrayList<>();
        int kept = 0;
        for (int i = 0; i < sizes.length; i++) {
            Section section = sections.get(i);
            out.add(section.header);
            List<String> tail = section.newestWithin(allowance[i]);
            if (tail.size() < section.content.size()) {
                out.add(lineMarker(section.content.size() - tail.size(), section.content.size()));
            }
            out.addAll(tail);
            kept += tail.size();
        }
        return new Truncation(render(out, trailingNewline), kept, true);
    }

    private Truncation fitLines(final List<String> lines, final boolean trailingNewline, final int maxBytes) {
        int total = lines.size();
        long budget = maxBytes - QUOTES - jsonSize(lineMarker(total, total));
        long used = 0;
        int keptCount = 0;
        while (keptCount < total && budget >= 0) {
            long cost = jsonSize(lines.get(keptCount));
            if (used + cost > budget) {
                break;
            }
            used += cost;
            keptCount++;
        }
        List<String> out = new ArrayList<>(lines.subList(0, keptCount));
        out.add(lineMarker(total - keptCount, total));
        return new Truncation(render(out, trailingNewline), contentLineCount(out.subList(0, keptCount)), true);
    }

    private Truncation fitCharacters(final String line, final boolean trailingNewline, final int maxBytes) {
        int low = 0;
        int high = line.length();
        while (low < high) {
            int mid = (low + high + 1) >>> 1;
            if (jsonSize(characterCut(line, mid, trailingNewline)) <= maxBytes) {
                low = mid;
            } else {
                high = mid - 1;
            }
        }
        return new Truncation(characterCut(line, low, trailingNewline), 0, true);
    }

    private static String characterCut(final String line, final int length, final boolean trailingNewline) {
        int end = length > 0 && Character.isHighSurrogate(line.charAt(length - 1)) ? length - 1 : length;
        return line.substring(0, end) + "\n" + MARKER_PREFIX + (line.length() - end) + " of " + line.length()
            + " characters omitted]" + (trailingNewline ? "\n" : "");
    }

    private static String lineMarker(final int omitted, final int total) {
        return MARKER_PREFIX + omitted + " of " + total + " lines omitted]";
    }

    private static List<String> splitLines(final String text, final boolean trailingNewline) {
        String body = trailingNewline ? text.substring(0, text.length() - 1) : text;
        return Arrays.asList(body.split("\n", -1));
    }

    private static String render(final List<String> lines, final boolean trailingNewline) {
        return String.join("\n", lines) + (trailingNewline ? "\n" : "");
    }

    private static int contentLineCount(final List<String> lines) {
        return (int) lines.stream().filter(line -> !line.startsWith(LogCollectionService.POD_HEADER_PREFIX)).count();
    }

    private List<Section> sections(final List<String> lines) {
        List<Section> sections = new ArrayList<>();
        int start = 0;
        while (start < lines.size()) {
            int end = start + 1;
            while (end < lines.size() && !lines.get(end).startsWith(LogCollectionService.POD_HEADER_PREFIX)) {
                end++;
            }
            List<String> content = lines.subList(start + 1, end);
            long[] costs = content.stream().mapToLong(this::jsonSize).toArray();
            sections.add(new Section(lines.get(start), content, costs));
            start = end;
        }
        return sections;
    }

    /**
     * One pod section: its header line, its content lines and the serialized cost of each content line.
     */
    private static final class Section {
        private final String header;
        private final List<String> content;
        private final long[] costs;

        Section(final String header, final List<String> content, final long[] costs) {
            this.header = header;
            this.content = content;
            this.costs = costs;
        }

        /**
         * The newest content lines whose total cost fits the cap, scanning backwards and stopping at
         * the first line that does not fit.
         *
         * @param cap the byte cap for this section's content
         * @return the kept lines in their original order
         */
        List<String> newestWithin(final long cap) {
            return content.subList(firstKept(cap), content.size());
        }

        /**
         * The serialized cost of {@link #newestWithin(long)}.
         *
         * @param cap the byte cap for this section's content
         * @return the bytes the kept lines cost
         */
        long usedWithin(final long cap) {
            return Arrays.stream(costs, firstKept(cap), costs.length).sum();
        }

        private int firstKept(final long cap) {
            long used = 0;
            int first = content.size();
            while (first > 0 && used + costs[first - 1] <= cap) {
                used += costs[first - 1];
                first--;
            }
            return first;
        }
    }
}
