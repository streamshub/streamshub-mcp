/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.guardrail;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkiverse.mcp.server.Content;
import io.quarkiverse.mcp.server.TextContent;
import io.quarkiverse.mcp.server.ToolOutputGuardrail;
import io.quarkiverse.mcp.server.ToolResponse;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.function.Predicate;

/**
 * Shared helper for output guardrails that need to read, mutate, and rewrite
 * a tool response's payload as a JSON tree.
 *
 * <p>This helper ensures that both the text-content block AND {@code structuredContent}
 * stay in sync after mutation, and encodes the failure policy (FAIL_CLOSED vs FAIL_OPEN)
 * for unexpected response shapes or mutation errors.</p>
 */
public final class GuardedResponses {

    private static final Logger LOG = Logger.getLogger(GuardedResponses.class);
    private static final String GENERIC_ERROR_MESSAGE = "Response could not be sanitized";

    private GuardedResponses() {
    }

    /**
     * Policy for handling unexpected response shapes or mutation errors.
     */
    public enum OnError {
        /**
         * Leave the original response intact if mutation fails or shape is unexpected.
         */
        FAIL_OPEN,

        /**
         * Replace the response with a generic error if mutation fails or shape is unexpected.
         */
        FAIL_CLOSED
    }

    /**
     * Apply a mutation to a tool response's JSON payload, keeping text and
     * structuredContent in sync.
     *
     * <p>Reads the response payload as a JSON tree from {@code structuredContent},
     * falling back to parsing a single {@link TextContent} block if structuredContent
     * is null. If the response shape is neither (e.g., image or multi-block content),
     * the tree is null and the mutation is skipped.</p>
     *
     * <p>If {@code mutate.test(tree)} returns false, no change is made (setResponse
     * is not called). If it returns true, the response is rewritten with both the
     * text-content block and structuredContent set to the mutated tree.</p>
     *
     * <p>On unexpected shape or any exception during mutation, a WARN log is emitted.
     * If {@code onError} is {@link OnError#FAIL_CLOSED}, the response is replaced with
     * a generic error. If {@link OnError#FAIL_OPEN}, the original response is left intact.</p>
     *
     * @param ctx     the tool output context
     * @param mapper  the Jackson ObjectMapper to use for JSON serialization
     * @param onError the failure policy
     * @param mutate  the mutation function; returns true if the tree was modified
     */
    public static void guard(final ToolOutputGuardrail.ToolOutputContext ctx,
                             final ObjectMapper mapper,
                             final OnError onError,
                             final Predicate<JsonNode> mutate) {
        ToolResponse response = ctx.getResponse();

        try {
            Object structuredContent = response.structuredContent();

            if (structuredContent != null) {
                // Structured JSON path: mutate the tree and rewrite both text and structuredContent
                JsonNode tree = mapper.valueToTree(structuredContent);
                boolean modified = mutate.test(tree);
                if (!modified) {
                    return;
                }
                String rewrittenJson = mapper.writeValueAsString(tree);
                ToolResponse rewritten = new ToolResponse(
                    response.isError(),
                    List.of(new TextContent(rewrittenJson)),
                    tree,
                    response._meta()
                );
                ctx.setResponse(rewritten);

            } else {
                // No structuredContent - check if we have a single TextContent
                String plainText = extractSingleTextContent(response);
                if (plainText == null) {
                    // Unexpected shape (no content, non-text content, or multiple blocks)
                    handleUnexpectedShape(ctx, onError);
                    return;
                }

                // Try to parse as JSON first
                JsonNode tree = tryParseJson(plainText, mapper);
                if (tree != null) {
                    // Valid JSON - treat as structured
                    boolean modified = mutate.test(tree);
                    if (!modified) {
                        return;
                    }
                    String rewrittenJson = mapper.writeValueAsString(tree);
                    ToolResponse rewritten = new ToolResponse(
                        response.isError(),
                        List.of(new TextContent(rewrittenJson)),
                        tree,
                        response._meta()
                    );
                    ctx.setResponse(rewritten);
                } else {
                    // Plain text (non-JSON) - wrap in container, mutate, extract
                    handlePlainText(ctx, mapper, response, plainText, mutate);
                }
            }

        } catch (Exception e) {
            LOG.warnf("Failed to apply guardrail mutation for tool '%s': %s",
                ctx.getTool() != null ? ctx.getTool().name() : "unknown",
                e.getMessage());
            if (onError == OnError.FAIL_CLOSED) {
                ctx.setResponse(ToolResponse.error(GENERIC_ERROR_MESSAGE));
            }
            // FAIL_OPEN: leave original response intact (do nothing)
        }
    }

    /**
     * Extract plain text from a response with exactly one TextContent block.
     *
     * @param response the tool response
     * @return the text content, or null if the response does not have exactly one TextContent block
     */
    private static String extractSingleTextContent(final ToolResponse response) {
        List<? extends Content> content = response.content();
        if (content == null || content.size() != 1) {
            return null;
        }

        Content firstContent = content.get(0);
        if (!(firstContent instanceof TextContent textContent)) {
            return null;
        }

        String text = textContent.text();
        if (text == null || text.isEmpty()) {
            return null;
        }

        return text;
    }

    /**
     * Try to parse text as JSON. Returns null if the text is not valid JSON (plain text).
     *
     * @param text   the text to parse
     * @param mapper the ObjectMapper
     * @return the parsed JSON tree, or null if the text is not valid JSON
     */
    private static JsonNode tryParseJson(final String text, final ObjectMapper mapper) {
        try {
            return mapper.readTree(text);
        } catch (JsonProcessingException e) {
            // Not valid JSON - this is plain text
            return null;
        }
    }

    /**
     * Handle plain-text (non-JSON) responses by wrapping the text in a temporary container,
     * applying the mutation, and extracting the result.
     *
     * @param ctx       the tool output context
     * @param mapper    the ObjectMapper
     * @param response  the original response
     * @param plainText the plain text content
     * @param mutate    the mutation function
     * @throws JsonProcessingException if JSON operations fail
     */
    private static void handlePlainText(final ToolOutputGuardrail.ToolOutputContext ctx,
                                        final ObjectMapper mapper,
                                        final ToolResponse response,
                                        final String plainText,
                                        final Predicate<JsonNode> mutate) throws JsonProcessingException {
        // Wrap plain text in a temporary container so the mutate predicate can work with it
        com.fasterxml.jackson.databind.node.ObjectNode wrapper = mapper.createObjectNode();
        wrapper.put("value", plainText);

        boolean modified = mutate.test(wrapper);
        if (!modified) {
            return;
        }

        // Extract the mutated text
        String mutatedText = wrapper.get("value").asText();

        // Create response with mutated text, keeping structuredContent null
        ToolResponse rewritten = new ToolResponse(
            response.isError(),
            List.of(new TextContent(mutatedText)),
            null,  // Keep structuredContent null for plain-text responses
            response._meta()
        );
        ctx.setResponse(rewritten);
    }

    /**
     * Handle the case where the response shape is unexpected (e.g., image or multi-block content).
     *
     * @param ctx     the tool output context
     * @param onError the failure policy
     */
    private static void handleUnexpectedShape(final ToolOutputGuardrail.ToolOutputContext ctx,
                                              final OnError onError) {
        LOG.warnf("Response shape is unexpected for tool '%s': cannot apply guardrail",
            ctx.getTool() != null ? ctx.getTool().name() : "unknown");
        if (onError == OnError.FAIL_CLOSED) {
            ctx.setResponse(ToolResponse.error(GENERIC_ERROR_MESSAGE));
        }
        // FAIL_OPEN: leave original response intact (do nothing)
    }
}
