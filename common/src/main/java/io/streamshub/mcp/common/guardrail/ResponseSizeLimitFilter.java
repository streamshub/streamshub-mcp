/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.guardrail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.Map;

/**
 * Output filter that enforces a maximum response size by truncating
 * the largest text fields when the serialized response exceeds the limit.
 *
 * <p>Runs at priority 400 (last in the filter chain, after all other
 * sanitization and redaction).</p>
 */
@ApplicationScoped
@Priority(400)
public class ResponseSizeLimitFilter implements GuardrailFilter {

    private static final Logger LOG = Logger.getLogger(ResponseSizeLimitFilter.class);
    private static final String TRUNCATION_NOTICE = "\n[...response truncated to stay within size limit]";
    private static final int MAX_TRUNCATION_ITERATIONS = 10;

    @Inject
    ObjectMapper objectMapper;

    @ConfigProperty(name = "mcp.guardrail.max-response-bytes", defaultValue = "500000")
    int maxResponseBytes;

    ResponseSizeLimitFilter() {
    }

    @Override
    public Object filterOutput(final String toolName, final Object result) {
        if (result == null) {
            return null;
        }
        try {
            byte[] serialized = objectMapper.writeValueAsBytes(result);
            if (serialized.length <= maxResponseBytes) {
                return result;
            }

            LOG.infof("Response for tool '%s' exceeds size limit (%d bytes > %d), truncating",
                toolName, serialized.length, maxResponseBytes);

            JsonNode tree = objectMapper.valueToTree(result);
            int iteration = 0;
            int currentSize = serialized.length;

            while (currentSize > maxResponseBytes && iteration < MAX_TRUNCATION_ITERATIONS) {
                LargestField largest = findLargestTextField(tree, "");
                if (largest == null || largest.length <= 0) {
                    break;
                }

                int excess = currentSize - maxResponseBytes;
                int newLength = Math.max(100, largest.length - excess - TRUNCATION_NOTICE.length());
                String truncated = largest.value.substring(0, newLength) + TRUNCATION_NOTICE;
                setTextField(tree, largest.path, truncated);

                currentSize = objectMapper.writeValueAsBytes(tree).length;
                iteration++;
            }

            return objectMapper.treeToValue(tree, result.getClass());
        } catch (Exception e) {
            LOG.debugf("Could not enforce size limit for tool '%s': %s", toolName, e.getMessage());
            return result;
        }
    }

    /**
     * Find the largest text field in a JSON tree by character length.
     *
     * @param node the JSON node to search
     * @param path the current field path
     * @return the largest text field info, or null if no text fields exist
     */
    LargestField findLargestTextField(final JsonNode node, final String path) {
        if (node == null) {
            return null;
        }
        LargestField largest = null;
        if (node.isObject()) {
            ObjectNode obj = (ObjectNode) node;
            for (Map.Entry<String, JsonNode> entry : obj.properties()) {
                String childPath = path.isEmpty() ? entry.getKey() : path + "." + entry.getKey();
                LargestField candidate;
                if (entry.getValue().isTextual()) {
                    String text = entry.getValue().asText();
                    candidate = new LargestField(childPath, text, text.length());
                } else {
                    candidate = findLargestTextField(entry.getValue(), childPath);
                }
                if (candidate != null && (largest == null || candidate.length > largest.length)) {
                    largest = candidate;
                }
            }
        } else if (node.isArray()) {
            ArrayNode arr = (ArrayNode) node;
            for (int i = 0; i < arr.size(); i++) {
                String childPath = path + "[" + i + "]";
                LargestField candidate;
                JsonNode element = arr.get(i);
                if (element.isTextual()) {
                    String text = element.asText();
                    candidate = new LargestField(childPath, text, text.length());
                } else {
                    candidate = findLargestTextField(element, childPath);
                }
                if (candidate != null && (largest == null || candidate.length > largest.length)) {
                    largest = candidate;
                }
            }
        }
        return largest;
    }

    /**
     * Set a text field value at the given dot-notation path in a JSON tree.
     *
     * @param root  the root JSON node
     * @param path  the dot-notation path (e.g., "logs" or "data.content")
     * @param value the new text value
     */
    void setTextField(final JsonNode root, final String path, final String value) {
        String[] parts = path.split("\\.");
        JsonNode current = root;
        for (int i = 0; i < parts.length - 1; i++) {
            current = navigateTo(current, parts[i]);
            if (current == null) {
                return;
            }
        }
        String lastPart = parts[parts.length - 1];
        int arrayIndex = extractArrayIndex(lastPart);
        if (arrayIndex >= 0 && current.isArray()) {
            ((ArrayNode) current).set(arrayIndex, new TextNode(value));
        } else if (current.isObject()) {
            ((ObjectNode) current).set(lastPart, new TextNode(value));
        }
    }

    private JsonNode navigateTo(final JsonNode node, final String segment) {
        int arrayIndex = extractArrayIndex(segment);
        if (arrayIndex >= 0 && node.isArray()) {
            return node.get(arrayIndex);
        }
        return node.get(segment.replaceAll("\\[\\d+]$", ""));
    }

    private int extractArrayIndex(final String segment) {
        int bracketStart = segment.indexOf('[');
        if (bracketStart >= 0) {
            try {
                String indexStr = segment.substring(bracketStart + 1, segment.length() - 1);
                return Integer.parseInt(indexStr);
            } catch (NumberFormatException e) {
                return -1;
            }
        }
        return -1;
    }

    /**
     * Info about the largest text field found during tree traversal.
     *
     * @param path   the dot-notation path to the field
     * @param value  the current field value
     * @param length the character length of the value
     */
    record LargestField(String path, String value, int length) {
    }
}
