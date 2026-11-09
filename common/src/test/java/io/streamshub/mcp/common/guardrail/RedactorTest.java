/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.guardrail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the JSON tree walk of {@link Redactor#redactTree(JsonNode)}.
 */
class RedactorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Redactor redactor = new Redactor(true, List.of());

    RedactorTest() {
    }

    private static JsonNode tree(final String json) throws Exception {
        return MAPPER.readTree(json);
    }

    @Test
    void redactTreeRedactsStringArrayUnderSensitiveKey() throws Exception {
        JsonNode node = tree("{\"secrets\":[\"a\",\"b\"]}");

        assertTrue(redactor.redactTree(node));
        assertEquals("{\"secrets\":[\"[REDACTED]\",\"[REDACTED]\"]}", node.toString());
    }

    @Test
    void redactTreeLeavesArrayUnderPlainKey() throws Exception {
        JsonNode node = tree("{\"hosts\":[\"a\",\"b\"]}");

        assertFalse(redactor.redactTree(node));
        assertEquals("{\"hosts\":[\"a\",\"b\"]}", node.toString());
    }

    @Test
    void redactTreeAppliesPatternRulesInsideArrays() throws Exception {
        JsonNode node = tree("{\"lines\":[\"Authorization: Bearer abc.def\"],\"top\":[\"Bearer xyz\"]}");

        assertTrue(redactor.redactTree(node));
        assertEquals("Authorization: Bearer [REDACTED]", node.get("lines").get(0).asText());
        assertEquals("Bearer [REDACTED]", node.get("top").get(0).asText());
    }

    @Test
    void redactTreeRedactsObjectsNestedInArraysByTheirOwnKeys() throws Exception {
        JsonNode node = tree("[{\"name\":\"x\",\"password\":\"p\"}]");

        assertTrue(redactor.redactTree(node));
        assertEquals("[{\"name\":\"x\",\"password\":\"[REDACTED]\"}]", node.toString());
    }

    @Test
    void redactTreeReturnsFalseWhenNothingChanged() throws Exception {
        JsonNode node = tree("{\"name\":\"x\",\"nested\":{\"count\":1,\"items\":[\"a\"]}}");

        assertFalse(redactor.redactTree(node));
    }

    @Test
    void redactTreeKeepsProviderReferenceInSensitiveArray() throws Exception {
        JsonNode node = tree("{\"secrets\":[\"${secrets:ns/name:key}\"]}");

        assertFalse(redactor.redactTree(node));
        assertEquals("${secrets:ns/name:key}", node.get("secrets").get(0).asText());
    }

    @Test
    void redactTreeRedactsNestedArrayInsideSensitiveArray() throws Exception {
        JsonNode node = tree("{\"secrets\":[[\"a\"]]}");

        assertTrue(redactor.redactTree(node));
        assertEquals("{\"secrets\":[[\"[REDACTED]\"]]}", node.toString());
    }
}
