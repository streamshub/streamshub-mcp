/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.util;

import java.util.regex.Pattern;

/**
 * Shared building blocks for the query-language sanitizers (PromQL, LogQL). Both languages use the
 * same label-name grammar and the same double-quoted string-literal escaping, so the rules live
 * here once and the provider modules only add their own language-specific checks.
 */
public final class QueryLiteralSanitizer {

    /** Valid label name pattern shared by Prometheus and Loki: starts with letter or underscore. */
    private static final Pattern LABEL_NAME_PATTERN =
        Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_]*$");

    private QueryLiteralSanitizer() {
        // Utility class — no instantiation
    }

    /**
     * Validates a label name against the Prometheus/Loki naming specification.
     *
     * @param name        the label name to validate
     * @param description the kind of name, used in the error message (for example {@code "LogQL label name"})
     * @return the validated label name
     * @throws IllegalArgumentException if the name is null, empty or does not match the specification
     */
    public static String validateLabelName(final String name, final String description) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("Label name must not be null or empty");
        }
        if (!LABEL_NAME_PATTERN.matcher(name).matches()) {
            throw new IllegalArgumentException(
                String.format("Invalid %s '%s'. Must match pattern: %s",
                    description, name, LABEL_NAME_PATTERN.pattern()));
        }
        return name;
    }

    /**
     * Escapes backslash, double-quote and newline so a value can be placed inside a double-quoted
     * PromQL or LogQL string literal without ending the literal.
     *
     * @param value the value to escape, may be null
     * @return the escaped value, or an empty string when the input is null
     */
    public static String escapeStringLiteral(final String value) {
        if (value == null) {
            return "";
        }
        return value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n");
    }
}
