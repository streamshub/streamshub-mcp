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
import io.quarkiverse.mcp.server.ExecutionModel;
import io.quarkiverse.mcp.server.SupportedExecutionModels;
import io.quarkiverse.mcp.server.ToolOutputGuardrail;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.Map;

/**
 * Output guardrail that enforces a maximum response size by truncating
 * the largest text fields when the serialized response exceeds the limit.
 *
 * <p>This guardrail operates on both the text-content block and structuredContent,
 * keeping them in sync after truncation.</p>
 */
@Singleton
@SupportedExecutionModels({ExecutionModel.WORKER_THREAD, ExecutionModel.VIRTUAL_THREAD})
public class ResponseSizeLimitGuardrail implements ToolOutputGuardrail {

    private static final Logger LOG = Logger.getLogger(ResponseSizeLimitGuardrail.class);
    private static final String TRUNCATION_NOTICE = "\n[...response truncated to stay within size limit]";
    private static final int MAX_TRUNCATION_ITERATIONS = 10;
    private static final int MIN_FIELD_LENGTH = 100;

    @Inject
    ObjectMapper mapper;

    @ConfigProperty(name = "mcp.guardrail.max-response-bytes", defaultValue = "500000")
    int maxResponseBytes;

    ResponseSizeLimitGuardrail() {
    }

    @Override
    public void apply(final ToolOutputContext ctx) {
        GuardedResponses.guard(ctx, mapper, GuardedResponses.OnError.FAIL_OPEN,
            this::truncateLargestFields);
    }

    /**
     * Truncate the largest text fields in a JSON tree until it fits within the size limit.
     *
     * @param tree the JSON tree to truncate
     * @return true if the tree was modified, false otherwise
     */
    boolean truncateLargestFields(final JsonNode tree) {
        try {
            int currentSize = mapper.writeValueAsBytes(tree).length;
            if (currentSize <= maxResponseBytes) {
                return false;
            }

            LOG.infof("Response exceeds size limit (%d bytes > %d), truncating",
                currentSize, maxResponseBytes);

            int iteration = 0;
            boolean modified = false;

            while (currentSize > maxResponseBytes && iteration < MAX_TRUNCATION_ITERATIONS) {
                LargestField largest = findLargestTextField(tree);
                if (largest == null) {
                    break;
                }

                // A field can only be shortened if, after truncation to at least MIN_FIELD_LENGTH
                // characters plus the truncation notice, it stays shorter than the original. If not,
                // no smaller field can help either, so stop.
                int maxUsefulLength = largest.length() - TRUNCATION_NOTICE.length() - 1;
                if (maxUsefulLength < MIN_FIELD_LENGTH) {
                    break;
                }

                // NOTE: currentSize/excess are BYTE counts (serialized JSON) while length()/target/newLength
                // are CHARACTER counts. For multi-byte UTF-8 text one pass removes fewer bytes than `excess`,
                // so convergence may take several passes; this is bounded by MAX_TRUNCATION_ITERATIONS and the
                // guardrail is fail-open, so an over-limit response is acceptable best-effort behaviour.
                int excess = currentSize - maxResponseBytes;
                int target = largest.length() - excess - TRUNCATION_NOTICE.length();
                int newLength = Math.max(MIN_FIELD_LENGTH, Math.min(maxUsefulLength, target));
                largest.replace(largest.value().substring(0, newLength) + TRUNCATION_NOTICE);

                currentSize = mapper.writeValueAsBytes(tree).length;
                iteration++;
                modified = true;
            }

            return modified;
        } catch (Exception e) {
            LOG.warnf("Could not enforce size limit: %s", e.getMessage());
            return false;
        }
    }

    /**
     * Find the largest text field in a JSON tree by character length, tracking a direct
     * reference to its containing node so it can be replaced in place.
     *
     * @param node the JSON node to search
     * @return the largest text field, or null if no text fields exist
     */
    LargestField findLargestTextField(final JsonNode node) {
        if (node == null) {
            return null;
        }
        LargestField largest = null;
        if (node.isObject()) {
            ObjectNode obj = (ObjectNode) node;
            for (Map.Entry<String, JsonNode> entry : obj.properties()) {
                JsonNode value = entry.getValue();
                LargestField candidate = value.isTextual()
                    ? LargestField.of(obj, entry.getKey(), value.asText())
                    : findLargestTextField(value);
                largest = larger(largest, candidate);
            }
        } else if (node.isArray()) {
            ArrayNode arr = (ArrayNode) node;
            for (int i = 0; i < arr.size(); i++) {
                JsonNode element = arr.get(i);
                LargestField candidate = element.isTextual()
                    ? LargestField.of(arr, i, element.asText())
                    : findLargestTextField(element);
                largest = larger(largest, candidate);
            }
        }
        return largest;
    }

    private static LargestField larger(final LargestField current, final LargestField candidate) {
        if (candidate == null) {
            return current;
        }
        if (current == null || candidate.length() > current.length()) {
            return candidate;
        }
        return current;
    }

    /**
     * A text field found during tree traversal, holding a direct reference to its containing
     * node so the value can be replaced in place without re-navigating a string path.
     *
     * @param parent    the containing {@link ObjectNode} or {@link ArrayNode}
     * @param fieldName the object field name, or null when the value lives in an array
     * @param index     the array index, or -1 when the value lives in an object
     * @param value     the current text value
     * @param length    the character length of the value
     */
    record LargestField(JsonNode parent, String fieldName, int index, String value, int length) {

        /**
         * Creates a reference to a text value held in an object field.
         *
         * @param parent    the containing object node
         * @param fieldName the field name
         * @param value     the current text value
         * @return the field reference
         */
        static LargestField of(final ObjectNode parent, final String fieldName, final String value) {
            return new LargestField(parent, fieldName, -1, value, value.length());
        }

        /**
         * Creates a reference to a text value held at an array index.
         *
         * @param parent the containing array node
         * @param index  the array index
         * @param value  the current text value
         * @return the field reference
         */
        static LargestField of(final ArrayNode parent, final int index, final String value) {
            return new LargestField(parent, null, index, value, value.length());
        }

        /**
         * Replaces this field's value in its containing node.
         *
         * @param newValue the replacement text
         */
        void replace(final String newValue) {
            if (fieldName != null) {
                ((ObjectNode) parent).set(fieldName, new TextNode(newValue));
            } else {
                ((ArrayNode) parent).set(index, new TextNode(newValue));
            }
        }
    }
}
