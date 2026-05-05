/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.util;

import io.quarkiverse.mcp.server.ToolCallException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    // ---- validateK8sName tests ----

    @Test
    void testValidateK8sNameAcceptsValidNames() {
        assertDoesNotThrow(() -> InputUtils.validateK8sName("my-cluster", "name"));
        assertDoesNotThrow(() -> InputUtils.validateK8sName("kafka.prod.v2", "name"));
        assertDoesNotThrow(() -> InputUtils.validateK8sName("a", "name"));
        assertDoesNotThrow(() -> InputUtils.validateK8sName("cluster-01", "name"));
    }

    @Test
    void testValidateK8sNameAcceptsNull() {
        assertDoesNotThrow(() -> InputUtils.validateK8sName(null, "name"));
    }

    @Test
    void testValidateK8sNameRejectsUppercase() {
        ToolCallException ex = assertThrows(ToolCallException.class,
            () -> InputUtils.validateK8sName("My-Cluster", "cluster name"));
        assertTrue(ex.getMessage().contains("lowercase"));
    }

    @Test
    void testValidateK8sNameRejectsSpecialChars() {
        assertThrows(ToolCallException.class,
            () -> InputUtils.validateK8sName("my_cluster", "name"));
        assertThrows(ToolCallException.class,
            () -> InputUtils.validateK8sName("my cluster", "name"));
    }

    @Test
    void testValidateK8sNameRejectsTooLong() {
        String longName = "a".repeat(254);
        ToolCallException ex = assertThrows(ToolCallException.class,
            () -> InputUtils.validateK8sName(longName, "name"));
        assertTrue(ex.getMessage().contains("maximum length"));
    }

    @Test
    void testValidateK8sNameRejectsStartingWithDash() {
        assertThrows(ToolCallException.class,
            () -> InputUtils.validateK8sName("-invalid", "name"));
    }
}
