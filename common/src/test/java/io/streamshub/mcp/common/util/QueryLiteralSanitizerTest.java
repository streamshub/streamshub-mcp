/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link QueryLiteralSanitizer}.
 */
class QueryLiteralSanitizerTest {

    QueryLiteralSanitizerTest() {
    }

    @Test
    void validateLabelNameAcceptsLettersDigitsAndUnderscore() {
        assertEquals("pod_name", QueryLiteralSanitizer.validateLabelName("pod_name", "label name"));
        assertEquals("_internal1", QueryLiteralSanitizer.validateLabelName("_internal1", "label name"));
    }

    @Test
    void validateLabelNameRejectsNullAndEmptyAndNamesTheKind() {
        IllegalArgumentException nullName = assertThrows(IllegalArgumentException.class,
            () -> QueryLiteralSanitizer.validateLabelName(null, "LogQL label name"));
        IllegalArgumentException emptyName = assertThrows(IllegalArgumentException.class,
            () -> QueryLiteralSanitizer.validateLabelName("", "PromQL label name"));

        assertEquals("LogQL label name must not be null or empty", nullName.getMessage());
        assertEquals("PromQL label name must not be null or empty", emptyName.getMessage());
    }

    @Test
    void validateLabelNameRejectsInvalidCharactersAndNamesTheKind() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> QueryLiteralSanitizer.validateLabelName("a-b", "LogQL label name"));

        assertTrue(e.getMessage().contains("Invalid LogQL label name 'a-b'"));
        assertThrows(IllegalArgumentException.class,
            () -> QueryLiteralSanitizer.validateLabelName("1abc", "label name"));
    }

    @Test
    void escapeStringLiteralEscapesBackslashQuoteAndNewline() {
        assertEquals("a\\\\b", QueryLiteralSanitizer.escapeStringLiteral("a\\b"));
        assertEquals("say \\\"hi\\\"", QueryLiteralSanitizer.escapeStringLiteral("say \"hi\""));
        assertEquals("l1\\nl2", QueryLiteralSanitizer.escapeStringLiteral("l1\nl2"));
    }

    @Test
    void escapeStringLiteralEscapesBackslashBeforeQuote() {
        // A value ending in a backslash must not be able to escape the closing quote of the literal,
        // and a backslash directly before a quote must be doubled, not merged into the quote escape.
        assertEquals("a\\\\\\\"b", QueryLiteralSanitizer.escapeStringLiteral("a\\\"b"));
        assertEquals("end\\\\", QueryLiteralSanitizer.escapeStringLiteral("end\\"));
    }

    @Test
    void escapeStringLiteralHandlesNullAndPlainText() {
        assertEquals("", QueryLiteralSanitizer.escapeStringLiteral(null));
        assertEquals("kafka-prod", QueryLiteralSanitizer.escapeStringLiteral("kafka-prod"));
    }
}
