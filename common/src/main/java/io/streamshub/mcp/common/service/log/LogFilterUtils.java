/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.service.log;

import org.jboss.logging.Logger;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;

/**
 * Utility for filtering log lines by regex patterns and keywords.
 *
 * <p>Used by log providers that do not support server-side filtering
 * (e.g., {@link KubernetesLogProvider}) to apply filter and keyword
 * matching after fetching raw logs.</p>
 */
public final class LogFilterUtils {

    private static final Logger LOG = Logger.getLogger(LogFilterUtils.class);
    private static final int MAX_FILTER_LENGTH = 200;

    private LogFilterUtils() {
    }

    /**
     * Apply filter and keyword matching to raw log content.
     * Returns only lines that match both the filter pattern and at least one keyword.
     *
     * @param rawLogs  the raw log string (newline-separated)
     * @param filter   optional filter: "errors", "warnings", or a regex pattern
     * @param keywords optional list of keywords for case-insensitive line matching
     * @return the filtered log string, or the original if no filters apply
     */
    public static String applyFilters(final String rawLogs, final String filter,
                                       final List<String> keywords) {
        if (rawLogs == null || rawLogs.isEmpty()) {
            return rawLogs;
        }
        if ((filter == null || filter.isBlank()) && (keywords == null || keywords.isEmpty())) {
            return rawLogs;
        }

        Pattern filterPattern = compileLogFilter(filter);
        List<String> normalizedKeywords = normalizeKeywords(keywords);

        String[] lines = rawLogs.split("\n");
        String filtered = java.util.Arrays.stream(lines)
            .filter(line -> matchesFilter(line, filterPattern))
            .filter(line -> matchesKeywords(line.toUpperCase(Locale.ENGLISH), normalizedKeywords))
            .collect(Collectors.joining("\n"));

        return filtered.isEmpty() ? null : filtered + "\n";
    }

    /**
     * Compile a log filter string into a regex pattern.
     * Supports "errors", "warnings" shortcuts, or a custom regex.
     * Rejects patterns longer than {@value #MAX_FILTER_LENGTH} characters
     * to mitigate ReDoS attacks.
     *
     * @param filter the filter string
     * @return the compiled pattern, or null for no filtering
     */
    static Pattern compileLogFilter(final String filter) {
        if (filter == null || filter.isBlank()) {
            return null;
        }
        String normalized = filter.trim().toLowerCase(Locale.ENGLISH);
        if ("errors".equals(normalized)) {
            return Pattern.compile("(?i)(ERROR|EXCEPTION)");
        }
        if ("warnings".equals(normalized)) {
            return Pattern.compile("(?i)(ERROR|EXCEPTION|WARN)");
        }
        String trimmed = filter.trim();
        if (trimmed.length() > MAX_FILTER_LENGTH) {
            LOG.warnf("Log filter regex too long (%d chars, max %d), returning unfiltered logs",
                trimmed.length(), MAX_FILTER_LENGTH);
            return null;
        }
        try {
            return Pattern.compile(trimmed);
        } catch (PatternSyntaxException e) {
            LOG.warnf("Invalid log filter regex '%s', returning unfiltered logs: %s", filter, e.getMessage());
            return null;
        }
    }

    private static boolean matchesFilter(final String line, final Pattern filterPattern) {
        return filterPattern == null || filterPattern.matcher(line).find();
    }

    private static boolean matchesKeywords(final String upperLine, final List<String> normalizedKeywords) {
        return normalizedKeywords == null || normalizedKeywords.stream().anyMatch(upperLine::contains);
    }

    private static List<String> normalizeKeywords(final List<String> keywords) {
        if (keywords == null || keywords.isEmpty()) {
            return null;
        }
        return keywords.stream()
            .filter(k -> k != null && !k.isBlank())
            .map(k -> k.toUpperCase(Locale.ENGLISH).trim())
            .toList();
    }
}
