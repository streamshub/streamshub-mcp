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
import io.quarkiverse.mcp.server.ToolManager;
import io.quarkiverse.mcp.server.ToolOutputGuardrail;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Output guardrail that enforces a maximum response size, measured in serialized bytes.
 *
 * <p>When the response is too large, the byte budget is shared across all large text fields with
 * {@link FairShare}: fields under their share stay whole, larger ones are cut by {@link TextTruncator}, and
 * budget a field cannot use goes to the others. {@link TextTruncator} cuts at line boundaries and keeps the
 * newest whole lines of every pod in pod logs. The response then carries a root {@code "truncated": true},
 * a note appended to a root {@code message} that names the tool's own options for requesting less, and on
 * every truncated {@code logs} field's object {@code has_more: true} and {@code log_lines} = the number of
 * lines shown in {@code logs}.</p>
 *
 * <p>When the non-text content alone (for example metric series) leaves no room for the text fields,
 * the response is left unchanged and a warning is logged, because shortening text cannot make it fit.</p>
 *
 * <p>This guardrail operates on both the text-content block and structuredContent,
 * keeping them in sync after truncation.</p>
 */
@Singleton
@SupportedExecutionModels({ExecutionModel.WORKER_THREAD, ExecutionModel.VIRTUAL_THREAD})
public class ResponseSizeLimitGuardrail implements ToolOutputGuardrail {

    private static final Logger LOG = Logger.getLogger(ResponseSizeLimitGuardrail.class);
    private static final String TRUNCATED_FIELD = "truncated";
    private static final String MESSAGE_FIELD = "message";
    private static final String LOGS_FIELD = "logs";
    private static final String HAS_MORE_FIELD = "has_more";
    private static final String LOG_LINES_FIELD = "log_lines";
    /** Size of {@code ,"truncated":true} added to the root object. */
    private static final int TRUNCATED_FLAG_BYTES = 17;
    private static final int MAX_MIN_KEPT_BYTES = 1024;
    /**
     * Smallest size a shortened text field needs: its truncation marker (line or character marker,
     * including pod logs that fall back to line mode) fits in this many bytes.
     */
    private static final int MARKER_FLOOR_BYTES = 128;
    /**
     * Tool arguments that let an agent request less, in the order the truncation note names them. The note
     * names only the ones the truncated tool declares, so it never points an agent at an argument it lacks.
     */
    private static final List<Lever> LEVERS = List.of(
        new Lever("tailLines", "fewer lines (tailLines)"),
        new Lever("sinceMinutes", "a shorter time window (sinceMinutes)"),
        new Lever("podNames", "specific pods (podNames)"),
        new Lever("filter", "a filter"));

    @Inject
    ObjectMapper mapper;

    @ConfigProperty(name = "mcp.guardrail.max-response-bytes", defaultValue = "50000")
    int maxResponseBytes;

    ResponseSizeLimitGuardrail() {
    }

    @Override
    public void apply(final ToolOutputContext ctx) {
        GuardedResponses.guard(ctx, mapper, GuardedResponses.OnError.FAIL_OPEN,
            tree -> fitToLimit(tree, ctx.getTool()));
    }

    /**
     * Fit a JSON tree into {@code mcp.guardrail.max-response-bytes} by sharing the byte budget across
     * its large text fields.
     *
     * @param tree the JSON tree to fit
     * @param tool the tool that produced the response, or null when unknown; its arguments decide which
     *             levers the truncation note names
     * @return true if the tree was modified, false otherwise
     */
    boolean fitToLimit(final JsonNode tree, final ToolManager.ToolInfo tool) {
        try {
            int size = mapper.writeValueAsBytes(tree).length;
            if (size <= maxResponseBytes) {
                return false;
            }
            TextTruncator truncator = new TextTruncator(mapper);
            List<TextFieldRef> candidates = new ArrayList<>();
            List<Long> sizes = new ArrayList<>();
            for (TextFieldRef field : collectTextFields(tree)) {
                long fieldSize = truncator.jsonSize(field.value());
                if (fieldSize > minKeptBytes()) {
                    candidates.add(field);
                    sizes.add(fieldSize);
                }
            }
            if (candidates.isEmpty()) {
                LOG.warnf("Response of %d bytes exceeds mcp.guardrail.max-response-bytes=%d"
                    + " but has no large text field to shorten; leaving it unchanged", size, maxResponseBytes);
                return false;
            }
            String suffix = truncationNote(tool);
            long reserve = reserveBytes(tree, truncator, suffix);
            long[] sizeArray = sizes.stream().mapToLong(Long::longValue).toArray();
            long fixed = size - sizes.stream().mapToLong(Long::longValue).sum();
            if (!budgetReachable(fixed, sizeArray, reserve)) {
                LOG.warnf("Response of %d bytes exceeds mcp.guardrail.max-response-bytes=%d, but its non-text"
                    + " content alone (%d bytes) leaves no room for the text fields; leaving it unchanged",
                    size, maxResponseBytes, fixed);
                return false;
            }
            int cut = truncateCandidates(candidates, sizeArray, maxResponseBytes - fixed - reserve, truncator);
            if (cut == 0) {
                return false;
            }
            markTruncated(tree, suffix);
            LOG.infof("Response of %d bytes truncated to fit mcp.guardrail.max-response-bytes=%d (%d of %d text"
                + " fields cut)", size, maxResponseBytes, cut, candidates.size());
            return true;
        } catch (Exception e) {
            LOG.warnf("Could not enforce size limit: %s", e.getMessage());
            return false;
        }
    }

    /**
     * Collect every text value in a JSON tree, with a direct reference to its containing node so it
     * can be replaced in place.
     *
     * @param node the JSON node to search
     * @return the text fields, in document order
     */
    private List<TextFieldRef> collectTextFields(final JsonNode node) {
        List<TextFieldRef> fields = new ArrayList<>();
        collect(node, fields);
        return fields;
    }

    /**
     * The smallest serialized size a text field must exceed to be shortened.
     *
     * @return {@code min(1024, maxResponseBytes / 4)}
     */
    private int minKeptBytes() {
        return Math.min(MAX_MIN_KEPT_BYTES, maxResponseBytes / 4);
    }

    private void collect(final JsonNode node, final List<TextFieldRef> fields) {
        if (node == null) {
            return;
        }
        if (node.isObject()) {
            ObjectNode obj = (ObjectNode) node;
            for (Map.Entry<String, JsonNode> entry : obj.properties()) {
                if (entry.getValue().isTextual()) {
                    fields.add(new TextFieldRef(obj, entry.getKey(), -1, entry.getValue().asText()));
                } else {
                    collect(entry.getValue(), fields);
                }
            }
        } else if (node.isArray()) {
            ArrayNode arr = (ArrayNode) node;
            for (int i = 0; i < arr.size(); i++) {
                if (arr.get(i).isTextual()) {
                    fields.add(new TextFieldRef(arr, null, i, arr.get(i).asText()));
                } else {
                    collect(arr.get(i), fields);
                }
            }
        }
    }

    /**
     * The note appended to a truncated response's {@code message}. It names the {@link #LEVERS} the tool
     * declares, so the agent knows how to request less, and falls back to a generic hint when the tool
     * declares none or is unknown.
     *
     * @param tool the tool that produced the response, or null when unknown
     * @return the note, starting with a space
     */
    private String truncationNote(final ToolManager.ToolInfo tool) {
        String prefix = " [Response truncated to fit mcp.guardrail.max-response-bytes=" + maxResponseBytes;
        List<String> levers = declaredLevers(tool);
        if (levers.isEmpty()) {
            return prefix + "; narrow the request to see the rest.]";
        }
        String joined;
        if (levers.size() == 1) {
            joined = levers.getFirst();
        } else {
            joined = String.join(", ", levers.subList(0, levers.size() - 1)) + ", or " + levers.getLast();
        }
        return prefix + ". To see the rest, request less: " + joined + ".]";
    }

    private static List<String> declaredLevers(final ToolManager.ToolInfo tool) {
        if (tool == null || tool.arguments() == null) {
            return List.of();
        }
        Set<String> declared = tool.arguments().stream()
            .map(ToolManager.ToolArgument::name)
            .collect(Collectors.toSet());
        return LEVERS.stream()
            .filter(lever -> declared.contains(lever.argument()))
            .map(Lever::phrase)
            .toList();
    }

    private static long reserveBytes(final JsonNode tree, final TextTruncator truncator, final String suffix) {
        long reserve = 0;
        if (tree.isObject() && !tree.has(TRUNCATED_FIELD)) {
            reserve += TRUNCATED_FLAG_BYTES;
        }
        if (tree.isObject() && tree.path(MESSAGE_FIELD).isTextual()) {
            reserve += truncator.jsonSize(suffix) - 2L;
        }
        return reserve;
    }

    /**
     * Whether shortening the text fields can make the response fit: the non-text content, the
     * reserved flag and note, and every candidate shortened to its marker must fit the limit. Only when
     * they do not is the response left unchanged.
     */
    private boolean budgetReachable(final long fixed, final long[] sizes, final long reserve) {
        long minimum = fixed + reserve;
        for (long fieldSize : sizes) {
            minimum += Math.min(fieldSize, MARKER_FLOOR_BYTES);
        }
        return minimum <= maxResponseBytes;
    }

    /**
     * Share the budget across the candidates with {@link FairShare} and shorten every candidate that does
     * not fit its allowance. Measuring a candidate's usage re-fits its text, at most
     * {@link FairShare#MAX_PASSES} times, plus once to apply the result; responses carry few large fields.
     *
     * @param candidates the large text fields
     * @param sizes      the serialized size of each candidate
     * @param budget     the bytes the candidates may use together
     * @param truncator  the truncator that fits one text
     * @return the number of fields shortened
     */
    private static int truncateCandidates(final List<TextFieldRef> candidates, final long[] sizes,
                                          final long budget, final TextTruncator truncator) {
        long[] allowance = FairShare.allocate(sizes, budget,
            (i, cap) -> truncator.jsonSize(truncator.fit(candidates.get(i).value(), toIntBudget(cap)).text()));
        int cut = 0;
        for (int i = 0; i < candidates.size(); i++) {
            if (allowance[i] >= sizes[i]) {
                continue;
            }
            TextFieldRef field = candidates.get(i);
            TextTruncator.Truncation result = truncator.fit(field.value(), toIntBudget(allowance[i]));
            if (!result.changed()) {
                continue;
            }
            field.replace(result.text());
            updateLogSiblings(field, result.keptLines());
            cut++;
        }
        return cut;
    }

    private static int toIntBudget(final long bytes) {
        return (int) Math.min(bytes, Integer.MAX_VALUE);
    }

    private static void updateLogSiblings(final TextFieldRef field, final int keptLines) {
        if (!LOGS_FIELD.equals(field.fieldName()) || !field.parent().isObject()) {
            return;
        }
        ObjectNode parent = (ObjectNode) field.parent();
        if (parent.path(HAS_MORE_FIELD).isBoolean()) {
            parent.put(HAS_MORE_FIELD, true);
        }
        if (parent.path(LOG_LINES_FIELD).isIntegralNumber()) {
            parent.put(LOG_LINES_FIELD, keptLines);
        }
    }

    private static void markTruncated(final JsonNode tree, final String suffix) {
        if (!tree.isObject()) {
            return;
        }
        ObjectNode root = (ObjectNode) tree;
        root.put(TRUNCATED_FIELD, true);
        if (root.path(MESSAGE_FIELD).isTextual()) {
            root.put(MESSAGE_FIELD, root.get(MESSAGE_FIELD).asText() + suffix);
        }
    }

    /**
     * A text value found during tree traversal, holding a direct reference to its containing
     * node so the value can be replaced in place without re-navigating a string path.
     *
     * @param parent    the containing {@link ObjectNode} or {@link ArrayNode}
     * @param fieldName the object field name, or null when the value lives in an array
     * @param index     the array index, or -1 when the value lives in an object
     * @param value     the current text value
     */
    /**
     * A tool argument that lets an agent request less, and how the truncation note names it.
     *
     * @param argument the tool argument name
     * @param phrase   the phrase the note uses for it
     */
    private record Lever(String argument, String phrase) {
    }

    record TextFieldRef(JsonNode parent, String fieldName, int index, String value) {

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
