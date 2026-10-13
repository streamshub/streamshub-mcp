/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.opensearch.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OpenSearchQuerySanitizerTest {

    OpenSearchQuerySanitizerTest() {
    }

    @Test
    void testSanitizeValueEscapesQuotes() {
        assertEquals("say \\\"hello\\\"", OpenSearchQuerySanitizer.sanitizeValue("say \"hello\""));
    }

    @Test
    void testSanitizeValueEscapesBackslash() {
        assertEquals("path\\\\to\\\\file", OpenSearchQuerySanitizer.sanitizeValue("path\\to\\file"));
    }

    @Test
    void testSanitizeValueEscapesNewlines() {
        assertEquals("line1\\nline2", OpenSearchQuerySanitizer.sanitizeValue("line1\nline2"));
    }

    @Test
    void testSanitizeValueEscapesTabs() {
        assertEquals("col1\\tcol2", OpenSearchQuerySanitizer.sanitizeValue("col1\tcol2"));
    }

    @Test
    void testSanitizeValueEscapesCarriageReturn() {
        assertEquals("line1\\rline2", OpenSearchQuerySanitizer.sanitizeValue("line1\rline2"));
    }

    @Test
    void testSanitizeValueEscapesBackspace() {
        assertEquals("test\\bvalue", OpenSearchQuerySanitizer.sanitizeValue("test\bvalue"));
    }

    @Test
    void testSanitizeValueEscapesFormFeed() {
        assertEquals("page1\\fpage2", OpenSearchQuerySanitizer.sanitizeValue("page1\fpage2"));
    }

    @Test
    void testSanitizeValueHandlesNull() {
        assertEquals("", OpenSearchQuerySanitizer.sanitizeValue(null));
    }

    @Test
    void testSanitizeValueHandlesEmpty() {
        assertEquals("", OpenSearchQuerySanitizer.sanitizeValue(""));
    }

    @Test
    void testSanitizeValuePassesSafeValues() {
        assertEquals("kafka-prod", OpenSearchQuerySanitizer.sanitizeValue("kafka-prod"));
        assertEquals("my-cluster-kafka-0", OpenSearchQuerySanitizer.sanitizeValue("my-cluster-kafka-0"));
    }

    @Test
    void testSanitizeValuePreventsInjection() {
        String malicious = "test\"}]}}}";
        String sanitized = OpenSearchQuerySanitizer.sanitizeValue(malicious);
        assertEquals("test\\\"}]}}}",  sanitized);
    }

    @Test
    void testSanitizeFieldNameValidName() {
        assertEquals("kubernetes.namespace_name", OpenSearchQuerySanitizer.sanitizeFieldName("kubernetes.namespace_name"));
        assertEquals("@timestamp", OpenSearchQuerySanitizer.sanitizeFieldName("@timestamp"));
        assertEquals("message", OpenSearchQuerySanitizer.sanitizeFieldName("message"));
    }

    @Test
    void testSanitizeFieldNameRejectsNull() {
        assertThrows(IllegalArgumentException.class, () -> OpenSearchQuerySanitizer.sanitizeFieldName(null));
    }

    @Test
    void testSanitizeFieldNameRejectsEmpty() {
        assertThrows(IllegalArgumentException.class, () -> OpenSearchQuerySanitizer.sanitizeFieldName(""));
    }

    @Test
    void testSanitizeFieldNameRejectsInvalidNames() {
        assertThrows(IllegalArgumentException.class, () -> OpenSearchQuerySanitizer.sanitizeFieldName("field with spaces"));
        assertThrows(IllegalArgumentException.class, () -> OpenSearchQuerySanitizer.sanitizeFieldName("field\"injection"));
    }
}
