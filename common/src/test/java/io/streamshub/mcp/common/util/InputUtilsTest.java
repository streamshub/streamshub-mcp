/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.util;

import io.quarkiverse.mcp.server.McpException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        McpException ex = assertThrows(McpException.class,
            () -> InputUtils.validateK8sName("My-Cluster", "cluster name"));
        assertTrue(ex.getMessage().contains("lowercase"));
    }

    @Test
    void testValidateK8sNameRejectsSpecialChars() {
        assertThrows(McpException.class,
            () -> InputUtils.validateK8sName("my_cluster", "name"));
        assertThrows(McpException.class,
            () -> InputUtils.validateK8sName("my cluster", "name"));
    }

    @Test
    void testValidateK8sNameRejectsTooLong() {
        String longName = "a".repeat(254);
        McpException ex = assertThrows(McpException.class,
            () -> InputUtils.validateK8sName(longName, "name"));
        assertTrue(ex.getMessage().contains("maximum length"));
    }

    @Test
    void testValidateK8sNameRejectsStartingWithDash() {
        assertThrows(McpException.class,
            () -> InputUtils.validateK8sName("-invalid", "name"));
    }

    @Test
    void testNormalizeNameKeepsCase() {
        assertEquals("My_Topic", InputUtils.normalizeName("  My_Topic "));
        assertNull(InputUtils.normalizeName(" "));
        assertNull(InputUtils.normalizeName("null"));
        assertNull(InputUtils.normalizeName(null));
    }

    @Test
    void testIsValidK8sName() {
        assertTrue(InputUtils.isValidK8sName("my-topic"));
        assertFalse(InputUtils.isValidK8sName("my_topic"));
        assertFalse(InputUtils.isValidK8sName("MyTopic"));
        assertFalse(InputUtils.isValidK8sName(null));
    }

    @Test
    void testValidateKafkaTopicNameAcceptsKafkaNames() {
        for (String name : new String[] {"my-topic", "my_topic", "MyTopic", "__consumer_offsets",
            "__transaction_state", "a.b.c", "a".repeat(249)}) {
            assertDoesNotThrow(() -> InputUtils.validateKafkaTopicName(name, "topic name"), name);
        }
        assertDoesNotThrow(() -> InputUtils.validateKafkaTopicName(null, "topic name"));
    }

    @Test
    void testValidateKafkaTopicNameRejectsIllegalNames() {
        for (String name : new String[] {".", "..", "has space", "slash/name", "semi;colon", "a".repeat(250),
            "new\nline", "uni\u00e9code"}) {
            assertThrows(McpException.class, () -> InputUtils.validateKafkaTopicName(name, "topic name"), name);
        }
    }
}
