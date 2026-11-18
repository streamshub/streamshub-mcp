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
 *
 * <p>It also holds the single definition of the {@code errors} and {@code warnings}
 * filter shortcuts ({@link #ERRORS_REGEX}, {@link #WARNINGS_REGEX}). The Kubernetes
 * provider, the Loki provider (as a LogQL line filter) and the error counter in
 * {@link LogCollectionService} all use them. The patterns match whole words, so
 * configuration keys such as {@code errors.tolerance} do not count as errors. They use
 * only constructs that both Java regex and RE2 (Loki) support.</p>
 */
public final class LogFilterUtils {

    /**
     * Pattern for the {@code errors} filter: an ERROR or FATAL level token (any case,
     * e.g. Go's {@code level=error}) or an exception/error class name such as
     * {@code IOException} or {@code OutOfMemoryError} (case-sensitive).
     */
    public static final String ERRORS_REGEX = "(?i:\\b(?:ERROR|FATAL)\\b)|\\b\\w*(?:Exception|Error)\\b";

    /**
     * Pattern for the {@code warnings} filter: everything {@link #ERRORS_REGEX} matches
     * plus a WARN or WARNING level token (any case).
     */
    public static final String WARNINGS_REGEX =
        "(?i:\\b(?:ERROR|FATAL|WARN|WARNING)\\b)|\\b\\w*(?:Exception|Error)\\b";

    private static final Logger LOG = Logger.getLogger(LogFilterUtils.class);
    private static final int MAX_FILTER_LENGTH = 200;
    private static final Pattern ERRORS_PATTERN = Pattern.compile(ERRORS_REGEX);
    private static final Pattern WARNINGS_PATTERN = Pattern.compile(WARNINGS_REGEX);

    private LogFilterUtils() {
    }

    /**
     * Resolve a filter shortcut or custom regex into a regex pattern string.
     *
     * @param filter the filter: {@code errors}, {@code warnings} (trimmed, case-insensitive),
     *               or a custom regex
     * @return {@link #ERRORS_REGEX} or {@link #WARNINGS_REGEX} for the shortcuts, the trimmed
     *         custom regex otherwise, or null when the filter is null or blank
     */
    public static String resolveFilterRegex(final String filter) {
        if (filter == null || filter.isBlank()) {
            return null;
        }
        String trimmed = filter.trim();
        String normalized = trimmed.toLowerCase(Locale.ROOT);
        if ("errors".equals(normalized)) {
            return ERRORS_REGEX;
        }
        if ("warnings".equals(normalized)) {
            return WARNINGS_REGEX;
        }
        return trimmed;
    }

    /**
     * Whether a log line counts as an error: it matches {@link #ERRORS_REGEX}.
     *
     * @param line the log line
     * @return true if the line has an ERROR/FATAL level or an exception/error class name
     */
    public static boolean isErrorLine(final String line) {
        return line != null && ERRORS_PATTERN.matcher(line).find();
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
     * Supports "errors", "warnings" shortcuts (see {@link #resolveFilterRegex(String)}),
     * or a custom regex. Rejects custom patterns longer than {@value #MAX_FILTER_LENGTH}
     * characters to mitigate ReDoS attacks.
     *
     * @param filter the filter string
     * @return the compiled pattern, or null for no filtering
     */
    static Pattern compileLogFilter(final String filter) {
        String regex = resolveFilterRegex(filter);
        if (regex == null) {
            return null;
        }
        if (ERRORS_REGEX.equals(regex)) {
            return ERRORS_PATTERN;
        }
        if (WARNINGS_REGEX.equals(regex)) {
            return WARNINGS_PATTERN;
        }
        if (regex.length() > MAX_FILTER_LENGTH) {
            LOG.warnf("Log filter regex too long (%d chars, max %d), returning unfiltered logs",
                regex.length(), MAX_FILTER_LENGTH);
            return null;
        }
        try {
            return Pattern.compile(regex);
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
