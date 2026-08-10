/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.elasticsearch.util;

import java.util.regex.Pattern;

/**
 * Sanitizes user-supplied values before interpolating them into Elasticsearch queries.
 * Prevents query injection by validating field names against the Elasticsearch naming
 * specification and escaping special characters in field values.
 */
public final class ElasticsearchQuerySanitizer {

    /** Valid Elasticsearch field name pattern: starts with letter, underscore, or @. */
    private static final Pattern FIELD_NAME_PATTERN =
        Pattern.compile("^[@a-zA-Z_][@a-zA-Z0-9_.]*$");

    private ElasticsearchQuerySanitizer() {
        // Utility class — no instantiation
    }

    /**
     * Validates a field name against the Elasticsearch naming specification.
     *
     * @param name the field name to validate
     * @return the validated field name
     * @throws IllegalArgumentException if the name is invalid
     */
    public static String sanitizeFieldName(String name) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("Field name must not be null or empty");
        }
        if (!FIELD_NAME_PATTERN.matcher(name).matches()) {
            throw new IllegalArgumentException(
                String.format("Invalid Elasticsearch field name '%s'. Must match pattern: %s",
                    name, FIELD_NAME_PATTERN.pattern()));
        }
        return name;
    }

    /**
     * Escapes special characters in a field value for safe interpolation
     * into Elasticsearch query JSON. Escapes backslash, double-quote, newline,
     * carriage return, tab, backspace, and form feed.
     *
     * @param value the field value to escape
     * @return the escaped field value safe for use in Elasticsearch queries
     */
    public static String sanitizeValue(String value) {
        if (value == null) {
            return "";
        }
        return value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
            .replace("\b", "\\b")
            .replace("\f", "\\f");
    }
}
