/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.loki.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit tests for {@link LogQLSanitizer}.
 */
class LogQLSanitizerTest {

    LogQLSanitizerTest() {
    }

    @Test
    void testSanitizeLabelValueEscapesBackslash() {
        assertEquals("path\\\\to\\\\file", LogQLSanitizer.sanitizeLabelValue("path\\to\\file"));
    }

    @Test
    void testSanitizeLabelValueEscapesQuotes() {
        assertEquals("say \\\"hello\\\"", LogQLSanitizer.sanitizeLabelValue("say \"hello\""));
    }

    @Test
    void testSanitizeLabelValueEscapesNewlines() {
        assertEquals("line1\\nline2", LogQLSanitizer.sanitizeLabelValue("line1\nline2"));
    }

    @Test
    void testSanitizeLabelValueHandlesNull() {
        assertEquals("", LogQLSanitizer.sanitizeLabelValue(null));
    }

    @Test
    void testSanitizeLabelValueHandlesEmpty() {
        assertEquals("", LogQLSanitizer.sanitizeLabelValue(""));
    }

    @Test
    void testSanitizeLabelValuePassesSafeValues() {
        assertEquals("kafka-prod", LogQLSanitizer.sanitizeLabelValue("kafka-prod"));
        assertEquals("my-cluster-kafka-0", LogQLSanitizer.sanitizeLabelValue("my-cluster-kafka-0"));
    }

    @Test
    void testSanitizeLabelValuePreventsInjection() {
        String malicious = "test\"} | logfmt";
        String sanitized = LogQLSanitizer.sanitizeLabelValue(malicious);
        assertEquals("test\\\"} | logfmt", sanitized);
    }

    @Test
    void testSanitizeLabelValuePreventsInjectionWithBackslashEscape() {
        String malicious = "test\\\"} | logfmt";
        String sanitized = LogQLSanitizer.sanitizeLabelValue(malicious);
        assertEquals("test\\\\\\\"} | logfmt", sanitized);
    }

    @Test
    void testSanitizeLabelNameValidName() {
        assertEquals("namespace", LogQLSanitizer.sanitizeLabelName("namespace"));
        assertEquals("pod_name", LogQLSanitizer.sanitizeLabelName("pod_name"));
        assertEquals("_internal", LogQLSanitizer.sanitizeLabelName("_internal"));
    }

    @Test
    void testSanitizeLabelNameRejectsNull() {
        assertThrows(IllegalArgumentException.class, () -> LogQLSanitizer.sanitizeLabelName(null));
    }

    @Test
    void testSanitizeLabelNameRejectsEmpty() {
        assertThrows(IllegalArgumentException.class, () -> LogQLSanitizer.sanitizeLabelName(""));
    }

    @Test
    void testSanitizeLabelNameRejectsInvalidNames() {
        assertThrows(IllegalArgumentException.class, () -> LogQLSanitizer.sanitizeLabelName("123abc"));
        assertThrows(IllegalArgumentException.class, () -> LogQLSanitizer.sanitizeLabelName("name-with-dash"));
        assertThrows(IllegalArgumentException.class, () -> LogQLSanitizer.sanitizeLabelName("name.with.dot"));
    }
}
