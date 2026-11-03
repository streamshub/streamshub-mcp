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
    void validateLabelNameRejectsNullAndEmpty() {
        assertThrows(IllegalArgumentException.class,
            () -> QueryLiteralSanitizer.validateLabelName(null, "label name"));
        assertThrows(IllegalArgumentException.class,
            () -> QueryLiteralSanitizer.validateLabelName("", "label name"));
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
    void escapeStringLiteralHandlesNullAndPlainText() {
        assertEquals("", QueryLiteralSanitizer.escapeStringLiteral(null));
        assertEquals("kafka-prod", QueryLiteralSanitizer.escapeStringLiteral("kafka-prod"));
    }
}
