/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.guardrail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.UnaryOperator;

/**
 * Utility for recursively applying a text transformation function
 * to all {@link TextNode} values in a Jackson {@link JsonNode} tree.
 */
final class JsonNodeSanitizer {

    private JsonNodeSanitizer() {
    }

    /**
     * Recursively apply a transformation to all text nodes in the tree.
     * Returns true if any text node was modified.
     *
     * @param node      the JSON node to process
     * @param transform the transformation to apply to each text value
     * @return true if any modifications were made
     */
    static boolean transformTextNodes(final JsonNode node, final UnaryOperator<String> transform) {
        return transformTextNodes(node, (key, value) -> transform.apply(value));
    }

    /**
     * Recursively apply a key-aware transformation to all text nodes in the tree. The key is the
     * field name of the nearest enclosing object property: array elements get the array's field
     * name, and values outside any object get null.
     *
     * @param node      the JSON node to process
     * @param transform the transformation, called with (key, text value)
     * @return true if any modifications were made
     */
    static boolean transformTextNodes(final JsonNode node, final BiFunction<String, String, String> transform) {
        return transform(node, null, transform);
    }

    private static boolean transform(final JsonNode node, final String key,
                                     final BiFunction<String, String, String> transform) {
        if (node == null) {
            return false;
        }
        boolean modified = false;
        if (node.isObject()) {
            ObjectNode obj = (ObjectNode) node;
            for (Map.Entry<String, JsonNode> entry : obj.properties()) {
                if (entry.getValue().isTextual()) {
                    String original = entry.getValue().asText();
                    String transformed = transform.apply(entry.getKey(), original);
                    if (!original.equals(transformed)) {
                        obj.set(entry.getKey(), new TextNode(transformed));
                        modified = true;
                    }
                } else {
                    modified |= transform(entry.getValue(), entry.getKey(), transform);
                }
            }
        } else if (node.isArray()) {
            ArrayNode arr = (ArrayNode) node;
            for (int i = 0; i < arr.size(); i++) {
                JsonNode element = arr.get(i);
                if (element.isTextual()) {
                    String original = element.asText();
                    String transformed = transform.apply(key, original);
                    if (!original.equals(transformed)) {
                        arr.set(i, new TextNode(transformed));
                        modified = true;
                    }
                } else {
                    modified |= transform(element, key, transform);
                }
            }
        }
        return modified;
    }
}
