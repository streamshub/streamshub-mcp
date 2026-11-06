/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.loki.util;

import java.util.regex.Pattern;

/**
 * Sanitizes user-supplied values before interpolating them into LogQL queries.
 * Prevents LogQL injection by validating label names against the Loki naming
 * specification and escaping special characters in label values.
 */
public final class LogQLSanitizer {

    /** Valid LogQL label name pattern: starts with letter or underscore. */
    private static final Pattern LABEL_NAME_PATTERN =
        Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_]*$");

    /** RE2 metacharacters that must be escaped to match text literally. */
    private static final String REGEX_METACHARACTERS = "\\.+*?()|[]{}^$";

    private LogQLSanitizer() {
        // Utility class — no instantiation
    }

    /**
     * Validates a label name against the LogQL naming specification.
     *
     * @param name the label name to validate
     * @return the validated label name
     * @throws IllegalArgumentException if the name is invalid
     */
    public static String sanitizeLabelName(final String name) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("Label name must not be null or empty");
        }
        if (!LABEL_NAME_PATTERN.matcher(name).matches()) {
            throw new IllegalArgumentException(
                String.format("Invalid LogQL label name '%s'. Must match pattern: %s",
                    name, LABEL_NAME_PATTERN.pattern()));
        }
        return name;
    }

    /**
     * Escapes special characters in a label value for safe interpolation
     * into LogQL string literals. Escapes backslash, double-quote, and newline.
     *
     * @param value the label value to escape
     * @return the escaped label value safe for use in LogQL
     */
    public static String sanitizeLabelValue(final String value) {
        if (value == null) {
            return "";
        }
        return value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n");
    }

    /**
     * Escapes RE2 metacharacters so a user-supplied keyword is matched literally inside a LogQL
     * regular expression. Without this a keyword such as {@code a.b}, {@code (} or {@code [} would
     * be interpreted as a pattern, match unrelated lines or make Loki reject the query.
     * The result still has to be passed through {@link #sanitizeLabelValue(String)} before it is
     * placed in a LogQL string literal.
     *
     * @param value the literal text to match
     * @return the text with every regex metacharacter backslash-escaped
     */
    public static String escapeRegexLiteral(final String value) {
        if (value == null) {
            return "";
        }
        StringBuilder escaped = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (REGEX_METACHARACTERS.indexOf(c) >= 0) {
                escaped.append('\\');
            }
            escaped.append(c);
        }
        return escaped.toString();
    }
}
