/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Unit tests for {@link InputUtils} input normalization.
 */
class InputUtilsTest {

    InputUtilsTest() {
    }

    @Test
    void testNormalizeValidInput() {
        assertEquals("kafka-prod", InputUtils.normalizeInput("kafka-prod"));
    }

    @Test
    void testNormalizeTrimsWhitespace() {
        assertEquals("kafka-prod", InputUtils.normalizeInput("  kafka-prod  "));
    }

    @Test
    void testNormalizeLowercases() {
        assertEquals("kafka-prod", InputUtils.normalizeInput("KAFKA-PROD"));
    }

    @Test
    void testNormalizeMixedCaseAndWhitespace() {
        assertEquals("my-cluster", InputUtils.normalizeInput("  My-Cluster  "));
    }

    @Test
    void testNormalizeNullReturnsNull() {
        assertNull(InputUtils.normalizeInput(null));
    }

    @Test
    void testNormalizeEmptyReturnsNull() {
        assertNull(InputUtils.normalizeInput(""));
    }

    @Test
    void testNormalizeBlankReturnsNull() {
        assertNull(InputUtils.normalizeInput("   "));
    }

    @Test
    void testNormalizeLiteralNullReturnsNull() {
        assertNull(InputUtils.normalizeInput("null"));
    }

    @Test
    void testNormalizeLiteralNullCaseInsensitive() {
        assertNull(InputUtils.normalizeInput("NULL"));
        assertNull(InputUtils.normalizeInput("Null"));
    }

    @Test
    void testNormalizeLiteralNullWithWhitespace() {
        assertNull(InputUtils.normalizeInput("  null  "));
    }
}
