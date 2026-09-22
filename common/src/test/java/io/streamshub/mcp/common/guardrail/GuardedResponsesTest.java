/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.guardrail;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.quarkiverse.mcp.server.Content;
import io.quarkiverse.mcp.server.ImageContent;
import io.quarkiverse.mcp.server.McpConnection;
import io.quarkiverse.mcp.server.Meta;
import io.quarkiverse.mcp.server.RequestId;
import io.quarkiverse.mcp.server.TextContent;
import io.quarkiverse.mcp.server.ToolManager.ToolInfo;
import io.quarkiverse.mcp.server.ToolOutputGuardrail;
import io.quarkiverse.mcp.server.ToolResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link GuardedResponses}.
 */
class GuardedResponsesTest {

    private ObjectMapper mapper;

    GuardedResponsesTest() {
    }

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    }

    @Test
    void rewritesBothTextAndStructuredContent() throws JsonProcessingException {
        // given: a response with TextContent(json) + structuredContent(pojo)
        Map<String, Object> originalData = Map.of("field", "secret", "other", "value");
        String originalJson = mapper.writeValueAsString(originalData);
        ToolResponse originalResponse = new ToolResponse(
            false,
            List.of(new TextContent(originalJson)),
            originalData,
            Map.of()
        );
        FakeToolOutputContext ctx = new FakeToolOutputContext(originalResponse);

        // when: guard with a mutate function that replaces "secret" with "X"
        Predicate<JsonNode> mutate = tree -> {
            if (tree instanceof ObjectNode obj) {
                if (obj.has("field") && "secret".equals(obj.get("field").asText())) {
                    obj.put("field", "X");
                    return true;
                }
            }
            return false;
        };
        GuardedResponses.guard(ctx, mapper, GuardedResponses.OnError.FAIL_OPEN, mutate);

        // then: setResponse was called with BOTH text block and structuredContent redacted & equal
        ToolResponse rewritten = ctx.capturedResponse;
        assertNotNull(rewritten, "setResponse should have been called");
        assertFalse(rewritten.isError(), "response should not be an error");

        // verify text content
        Content firstContent = rewritten.firstContent();
        assertTrue(firstContent instanceof TextContent, "first content should be TextContent");
        String rewrittenJson = ((TextContent) firstContent).text();
        JsonNode rewrittenTextTree = mapper.readTree(rewrittenJson);
        assertEquals("X", rewrittenTextTree.get("field").asText(), "text content should be redacted");
        assertEquals("value", rewrittenTextTree.get("other").asText(), "other field should be unchanged");

        // verify structuredContent
        Object structuredContent = rewritten.structuredContent();
        assertNotNull(structuredContent, "structuredContent should not be null");
        assertTrue(structuredContent instanceof JsonNode, "structuredContent should be a JsonNode");
        JsonNode structuredTree = (JsonNode) structuredContent;
        assertEquals("X", structuredTree.get("field").asText(), "structuredContent should be redacted");
        assertEquals("value", structuredTree.get("other").asText(), "other field should be unchanged");

        // verify both are equal
        assertEquals(rewrittenTextTree, structuredTree, "text and structuredContent should be equal");
    }

    @Test
    void noChangeMeansNoSetResponse() throws JsonProcessingException {
        // given: a response
        Map<String, Object> data = Map.of("field", "clean");
        String json = mapper.writeValueAsString(data);
        ToolResponse originalResponse = new ToolResponse(
            false,
            List.of(new TextContent(json)),
            data,
            Map.of()
        );
        FakeToolOutputContext ctx = new FakeToolOutputContext(originalResponse);

        // when: mutate returns false (no changes needed)
        Predicate<JsonNode> mutate = tree -> false;
        GuardedResponses.guard(ctx, mapper, GuardedResponses.OnError.FAIL_OPEN, mutate);

        // then: setResponse was never called
        assertNull(ctx.capturedResponse, "setResponse should not have been called");
    }

    @Test
    void redactionFailClosedReturnsGenericError() throws JsonProcessingException {
        // given: a response
        Map<String, Object> data = Map.of("field", "value");
        String json = mapper.writeValueAsString(data);
        ToolResponse originalResponse = new ToolResponse(
            false,
            List.of(new TextContent(json)),
            data,
            Map.of()
        );
        FakeToolOutputContext ctx = new FakeToolOutputContext(originalResponse);

        // when: mutate throws an exception with FAIL_CLOSED
        Predicate<JsonNode> mutate = tree -> {
            throw new RuntimeException("boom");
        };
        GuardedResponses.guard(ctx, mapper, GuardedResponses.OnError.FAIL_CLOSED, mutate);

        // then: response replaced with isError:true generic message, NOT original
        ToolResponse errorResponse = ctx.capturedResponse;
        assertNotNull(errorResponse, "setResponse should have been called");
        assertTrue(errorResponse.isError(), "response should be an error");

        // verify it's a generic error message, not the original
        Content firstContent = errorResponse.firstContent();
        assertTrue(firstContent instanceof TextContent, "first content should be TextContent");
        String errorText = ((TextContent) firstContent).text();
        assertEquals("Response could not be sanitized", errorText, "should be generic error message");
    }

    @Test
    void sizeLimitFailOpenLeavesOriginal() throws JsonProcessingException {
        // given: a response
        Map<String, Object> data = Map.of("field", "value");
        String json = mapper.writeValueAsString(data);
        ToolResponse originalResponse = new ToolResponse(
            false,
            List.of(new TextContent(json)),
            data,
            Map.of()
        );
        FakeToolOutputContext ctx = new FakeToolOutputContext(originalResponse);

        // when: mutate throws an exception with FAIL_OPEN
        Predicate<JsonNode> mutate = tree -> {
            throw new RuntimeException("boom");
        };
        GuardedResponses.guard(ctx, mapper, GuardedResponses.OnError.FAIL_OPEN, mutate);

        // then: original response intact (setResponse not called)
        assertNull(ctx.capturedResponse, "setResponse should not have been called");
    }

    @Test
    void unexpectedShapeFailClosedErrors() {
        // given: content is ImageContent (unexpected shape), no structuredContent
        ToolResponse originalResponse = new ToolResponse(
            false,
            List.of(new ImageContent("data:image/png;base64,iVBORw0KGgo=", "image/png")),
            null,
            Map.of()
        );
        FakeToolOutputContext ctx = new FakeToolOutputContext(originalResponse);

        // when: guard is called with FAIL_CLOSED
        Predicate<JsonNode> mutate = tree -> {
            // should not be called because tree is null
            throw new AssertionError("mutate should not be called for unexpected shape");
        };
        GuardedResponses.guard(ctx, mapper, GuardedResponses.OnError.FAIL_CLOSED, mutate);

        // then: response replaced with error
        ToolResponse errorResponse = ctx.capturedResponse;
        assertNotNull(errorResponse, "setResponse should have been called");
        assertTrue(errorResponse.isError(), "response should be an error");

        Content firstContent = errorResponse.firstContent();
        assertTrue(firstContent instanceof TextContent, "first content should be TextContent");
        String errorText = ((TextContent) firstContent).text();
        assertEquals("Response could not be sanitized", errorText, "should be generic error message");
    }

    @Test
    void textOnlyFallbackParsesTextBlock() throws JsonProcessingException {
        // given: structuredContent is null, single TextContent with JSON
        Map<String, Object> data = Map.of("field", "secret");
        String json = mapper.writeValueAsString(data);
        ToolResponse originalResponse = new ToolResponse(
            false,
            List.of(new TextContent(json)),
            null,  // no structuredContent
            Map.of()
        );
        FakeToolOutputContext ctx = new FakeToolOutputContext(originalResponse);

        // when: guard with a mutate function that replaces "secret" with "Y"
        Predicate<JsonNode> mutate = tree -> {
            if (tree instanceof ObjectNode obj) {
                if (obj.has("field") && "secret".equals(obj.get("field").asText())) {
                    obj.put("field", "Y");
                    return true;
                }
            }
            return false;
        };
        GuardedResponses.guard(ctx, mapper, GuardedResponses.OnError.FAIL_OPEN, mutate);

        // then: text was parsed and mutated
        ToolResponse rewritten = ctx.capturedResponse;
        assertNotNull(rewritten, "setResponse should have been called");

        Content firstContent = rewritten.firstContent();
        assertTrue(firstContent instanceof TextContent, "first content should be TextContent");
        String rewrittenJson = ((TextContent) firstContent).text();
        JsonNode rewrittenTree = mapper.readTree(rewrittenJson);
        assertEquals("Y", rewrittenTree.get("field").asText(), "field should be redacted");

        // verify structuredContent is the tree
        Object structuredContent = rewritten.structuredContent();
        assertNotNull(structuredContent, "structuredContent should be set");
        assertTrue(structuredContent instanceof JsonNode, "structuredContent should be a JsonNode");
        JsonNode structuredTree = (JsonNode) structuredContent;
        assertEquals("Y", structuredTree.get("field").asText(), "structuredContent should match");
    }

    @Test
    void plainTextWithSecretIsRedacted() {
        // given: plain-text (non-JSON) error message with a secret
        String errorMessage = "Kafka cluster 'my-cluster' not found, password=secretpass123";
        ToolResponse originalResponse = new ToolResponse(
            true,  // isError
            List.of(new TextContent(errorMessage)),
            null,  // no structuredContent
            Map.of()
        );
        FakeToolOutputContext ctx = new FakeToolOutputContext(originalResponse);

        // when: guard with a mutate function that redacts "secretpass123"
        Predicate<JsonNode> mutate = tree -> {
            if (tree instanceof ObjectNode obj) {
                if (obj.has("value")) {
                    String text = obj.get("value").asText();
                    if (text.contains("secretpass123")) {
                        obj.put("value", text.replace("secretpass123", "[REDACTED]"));
                        return true;
                    }
                }
            }
            return false;
        };
        GuardedResponses.guard(ctx, mapper, GuardedResponses.OnError.FAIL_CLOSED, mutate);

        // then: text was redacted, structuredContent stays null
        ToolResponse rewritten = ctx.capturedResponse;
        assertNotNull(rewritten, "setResponse should have been called");
        assertTrue(rewritten.isError(), "response should still be an error");

        Content firstContent = rewritten.firstContent();
        assertTrue(firstContent instanceof TextContent, "first content should be TextContent");
        String rewrittenText = ((TextContent) firstContent).text();
        assertEquals("Kafka cluster 'my-cluster' not found, password=[REDACTED]",
            rewrittenText, "secret should be redacted");

        // verify structuredContent is still null (not introduced for plain-text)
        assertNull(rewritten.structuredContent(), "structuredContent should remain null");
    }

    @Test
    void plainTextNoChangeMeansNoSetResponse() {
        // given: plain-text error message with no secrets
        String errorMessage = "Connection refused";
        ToolResponse originalResponse = new ToolResponse(
            true,
            List.of(new TextContent(errorMessage)),
            null,
            Map.of()
        );
        FakeToolOutputContext ctx = new FakeToolOutputContext(originalResponse);

        // when: mutate returns false (no changes needed)
        Predicate<JsonNode> mutate = tree -> {
            if (tree instanceof ObjectNode obj) {
                if (obj.has("value")) {
                    String text = obj.get("value").asText();
                    return text.contains("SECRET_PATTERN_NOT_PRESENT");
                }
            }
            return false;
        };
        GuardedResponses.guard(ctx, mapper, GuardedResponses.OnError.FAIL_CLOSED, mutate);

        // then: setResponse was never called
        assertNull(ctx.capturedResponse, "setResponse should not have been called");
    }

    @Test
    void plainTextTruncationWorks() {
        // given: oversized plain-text message
        String longMessage = "Error message with lots of details: " + "x".repeat(1000);
        ToolResponse originalResponse = new ToolResponse(
            true,
            List.of(new TextContent(longMessage)),
            null,
            Map.of()
        );
        FakeToolOutputContext ctx = new FakeToolOutputContext(originalResponse);

        // when: guard with a truncating mutator (max 100 chars)
        Predicate<JsonNode> mutate = tree -> {
            if (tree instanceof ObjectNode obj) {
                if (obj.has("value")) {
                    String text = obj.get("value").asText();
                    if (text.length() > 100) {
                        obj.put("value", text.substring(0, 100) + "...");
                        return true;
                    }
                }
            }
            return false;
        };
        GuardedResponses.guard(ctx, mapper, GuardedResponses.OnError.FAIL_OPEN, mutate);

        // then: text was truncated
        ToolResponse rewritten = ctx.capturedResponse;
        assertNotNull(rewritten, "setResponse should have been called");

        Content firstContent = rewritten.firstContent();
        assertTrue(firstContent instanceof TextContent, "first content should be TextContent");
        String rewrittenText = ((TextContent) firstContent).text();
        assertTrue(rewrittenText.endsWith("..."), "text should be truncated");
        assertEquals(103, rewrittenText.length(), "truncated text should be 103 chars (100 + '...')");

        // verify structuredContent is still null
        assertNull(rewritten.structuredContent(), "structuredContent should remain null");
    }

    /**
     * Fake implementation of {@link ToolOutputGuardrail.ToolOutputContext} for testing.
     * Records the response passed to {@link #setResponse(ToolResponse)}.
     */
    private static class FakeToolOutputContext implements ToolOutputGuardrail.ToolOutputContext {
        private final ToolResponse originalResponse;
        ToolResponse capturedResponse;

        FakeToolOutputContext(ToolResponse originalResponse) {
            this.originalResponse = originalResponse;
        }

        @Override
        public ToolResponse getResponse() {
            return originalResponse;
        }

        @Override
        public void setResponse(ToolResponse response) {
            if (response == null) {
                throw new IllegalArgumentException("response cannot be null");
            }
            this.capturedResponse = response;
        }

        @Override
        public ToolInfo getTool() {
            // Return a stub ToolInfo - we only need the interface, not a real implementation
            return null;
        }

        @Override
        public Meta getMeta() {
            // Return a stub Meta
            return null;
        }

        @Override
        public RequestId getRequestId() {
            return new RequestId("test-request-id");
        }

        @Override
        public McpConnection getConnection() {
            return null;
        }
    }
}
