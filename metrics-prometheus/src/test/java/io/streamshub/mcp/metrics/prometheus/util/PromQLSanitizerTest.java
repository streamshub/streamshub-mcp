/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.metrics.prometheus.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for {@link PromQLSanitizer}.
 */
class PromQLSanitizerTest {

    PromQLSanitizerTest() {
        // default constructor for checkstyle
    }

    @Test
    void validMetricNamePassesThrough() {
        assertEquals("http_requests_total", PromQLSanitizer.sanitizeMetricName("http_requests_total"));
    }

    @Test
    void metricNameWithColonIsValid() {
        assertEquals("namespace:http_requests:rate5m",
            PromQLSanitizer.sanitizeMetricName("namespace:http_requests:rate5m"));
    }

    @Test
    void metricNameStartingWithUnderscoreIsValid() {
        assertEquals("_internal_metric", PromQLSanitizer.sanitizeMetricName("_internal_metric"));
    }

    @Test
    void metricNameWithSpecialCharsThrows() {
        assertThrows(IllegalArgumentException.class,
            () -> PromQLSanitizer.sanitizeMetricName("metric-name"));
    }

    @Test
    void metricNameStartingWithDigitThrows() {
        assertThrows(IllegalArgumentException.class,
            () -> PromQLSanitizer.sanitizeMetricName("1_invalid"));
    }

    @Test
    void emptyMetricNameThrows() {
        assertThrows(IllegalArgumentException.class,
            () -> PromQLSanitizer.sanitizeMetricName(""));
    }

    @Test
    void nullMetricNameThrows() {
        assertThrows(IllegalArgumentException.class,
            () -> PromQLSanitizer.sanitizeMetricName(null));
    }

    @Test
    void injectionInMetricNameIsRejected() {
        assertThrows(IllegalArgumentException.class,
            () -> PromQLSanitizer.sanitizeMetricName("foo\"}or{job=\""));
    }

    @Test
    void validLabelNamePassesThrough() {
        assertEquals("instance", PromQLSanitizer.sanitizeLabelName("instance"));
    }

    @Test
    void labelNameWithColonThrows() {
        assertThrows(IllegalArgumentException.class,
            () -> PromQLSanitizer.sanitizeLabelName("namespace:label"));
    }

    @Test
    void emptyLabelNameThrows() {
        assertThrows(IllegalArgumentException.class,
            () -> PromQLSanitizer.sanitizeLabelName(""));
    }

    @Test
    void nullLabelNameThrows() {
        assertThrows(IllegalArgumentException.class,
            () -> PromQLSanitizer.sanitizeLabelName(null));
    }

    @Test
    void labelValueEscapesBackslash() {
        assertEquals("path\\\\to", PromQLSanitizer.sanitizeLabelValue("path\\to"));
    }

    @Test
    void labelValueEscapesDoubleQuote() {
        assertEquals("say\\\"hello\\\"", PromQLSanitizer.sanitizeLabelValue("say\"hello\""));
    }

    @Test
    void labelValueEscapesNewline() {
        assertEquals("line1\\nline2", PromQLSanitizer.sanitizeLabelValue("line1\nline2"));
    }

    @Test
    void labelValueInjectionIsSafelyEscaped() {
        String malicious = "foo\"}or{job=\"bar";
        String sanitized = PromQLSanitizer.sanitizeLabelValue(malicious);
        assertEquals("foo\\\"}or{job=\\\"bar", sanitized);
    }

    @Test
    void nullLabelValueReturnsEmpty() {
        assertEquals("", PromQLSanitizer.sanitizeLabelValue(null));
    }

    @Test
    void plainLabelValuePassesThrough() {
        assertEquals("simple_value", PromQLSanitizer.sanitizeLabelValue("simple_value"));
    }
}
