/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.elasticsearch.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ElasticsearchQuerySanitizerTest {

    ElasticsearchQuerySanitizerTest() {
    }

    @Test
    void testSanitizeValueEscapesQuotes() {
        assertEquals("say \\\"hello\\\"", ElasticsearchQuerySanitizer.sanitizeValue("say \"hello\""));
    }

    @Test
    void testSanitizeValueEscapesBackslash() {
        assertEquals("path\\\\to\\\\file", ElasticsearchQuerySanitizer.sanitizeValue("path\\to\\file"));
    }

    @Test
    void testSanitizeValueEscapesNewlines() {
        assertEquals("line1\\nline2", ElasticsearchQuerySanitizer.sanitizeValue("line1\nline2"));
    }

    @Test
    void testSanitizeValueEscapesTabs() {
        assertEquals("col1\\tcol2", ElasticsearchQuerySanitizer.sanitizeValue("col1\tcol2"));
    }

    @Test
    void testSanitizeValueEscapesCarriageReturn() {
        assertEquals("line1\\rline2", ElasticsearchQuerySanitizer.sanitizeValue("line1\rline2"));
    }

    @Test
    void testSanitizeValueEscapesBackspace() {
        assertEquals("test\\bvalue", ElasticsearchQuerySanitizer.sanitizeValue("test\bvalue"));
    }

    @Test
    void testSanitizeValueEscapesFormFeed() {
        assertEquals("page1\\fpage2", ElasticsearchQuerySanitizer.sanitizeValue("page1\fpage2"));
    }

    @Test
    void testSanitizeValueHandlesNull() {
        assertEquals("", ElasticsearchQuerySanitizer.sanitizeValue(null));
    }

    @Test
    void testSanitizeValueHandlesEmpty() {
        assertEquals("", ElasticsearchQuerySanitizer.sanitizeValue(""));
    }

    @Test
    void testSanitizeValuePassesSafeValues() {
        assertEquals("kafka-prod", ElasticsearchQuerySanitizer.sanitizeValue("kafka-prod"));
        assertEquals("my-cluster-kafka-0", ElasticsearchQuerySanitizer.sanitizeValue("my-cluster-kafka-0"));
    }

    @Test
    void testSanitizeValuePreventsInjection() {
        String malicious = "test\"}]}}}";
        String sanitized = ElasticsearchQuerySanitizer.sanitizeValue(malicious);
        assertEquals("test\\\"}]}}}",  sanitized);
    }

    @Test
    void testSanitizeFieldNameValidName() {
        assertEquals("kubernetes.namespace_name", ElasticsearchQuerySanitizer.sanitizeFieldName("kubernetes.namespace_name"));
        assertEquals("@timestamp", ElasticsearchQuerySanitizer.sanitizeFieldName("@timestamp"));
        assertEquals("message", ElasticsearchQuerySanitizer.sanitizeFieldName("message"));
    }

    @Test
    void testSanitizeFieldNameRejectsNull() {
        assertThrows(IllegalArgumentException.class, () -> ElasticsearchQuerySanitizer.sanitizeFieldName(null));
    }

    @Test
    void testSanitizeFieldNameRejectsEmpty() {
        assertThrows(IllegalArgumentException.class, () -> ElasticsearchQuerySanitizer.sanitizeFieldName(""));
    }

    @Test
    void testSanitizeFieldNameRejectsInvalidNames() {
        assertThrows(IllegalArgumentException.class, () -> ElasticsearchQuerySanitizer.sanitizeFieldName("field with spaces"));
        assertThrows(IllegalArgumentException.class, () -> ElasticsearchQuerySanitizer.sanitizeFieldName("field\"injection"));
    }
}
