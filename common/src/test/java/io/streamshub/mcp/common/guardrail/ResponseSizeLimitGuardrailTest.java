/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.guardrail;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ResponseSizeLimitGuardrail}.
 */
class ResponseSizeLimitGuardrailTest {

    private ResponseSizeLimitGuardrail guardrail;
    private ObjectMapper objectMapper;

    ResponseSizeLimitGuardrailTest() {
    }

    @BeforeEach
    void setUp() {
        guardrail = new ResponseSizeLimitGuardrail();
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        guardrail.mapper = objectMapper;
        guardrail.maxResponseBytes = 500;
    }

    @Test
    void testPassesThroughSmallResponse() throws Exception {
        SizeDto input = new SizeDto("short", "data");
        String json = objectMapper.writeValueAsString(input);

        ToolResponse response = new ToolResponse(
            false,
            List.of(new TextContent(json)),
            objectMapper.valueToTree(input),
            null
        );

        TestToolOutputContext ctx = new TestToolOutputContext(response);
        guardrail.apply(ctx);

        // Small response should not be modified
        ToolResponse result = ctx.getResponse();
        assertNotNull(result);
        byte[] serialized = objectMapper.writeValueAsBytes(result.structuredContent());
        assertTrue(serialized.length <= 500);
    }

    @Test
    void testTruncatesOversizedResponse() throws Exception {
        String largeContent = "x".repeat(1000);
        SizeDto input = new SizeDto("name", largeContent);
        String json = objectMapper.writeValueAsString(input);

        ToolResponse response = new ToolResponse(
            false,
            List.of(new TextContent(json)),
            objectMapper.valueToTree(input),
            null
        );

        TestToolOutputContext ctx = new TestToolOutputContext(response);
        guardrail.apply(ctx);

        ToolResponse result = ctx.getResponse();
        assertNotNull(result);
        byte[] serialized = objectMapper.writeValueAsBytes(result.structuredContent());
        assertTrue(serialized.length <= 500,
            "Response should be truncated to fit within limit, was " + serialized.length);
    }

    @Test
    void testTruncatedContentContainsNotice() throws Exception {
        String largeContent = "x".repeat(1000);
        SizeDto input = new SizeDto("name", largeContent);
        String json = objectMapper.writeValueAsString(input);

        ToolResponse response = new ToolResponse(
            false,
            List.of(new TextContent(json)),
            objectMapper.valueToTree(input),
            null
        );

        TestToolOutputContext ctx = new TestToolOutputContext(response);
        guardrail.apply(ctx);

        ToolResponse result = ctx.getResponse();
        assertNotNull(result);
        String resultJson = objectMapper.writeValueAsString(result.structuredContent());
        assertTrue(resultJson.contains("[...response truncated"),
            "Truncated response should contain truncation notice");
    }

    @Test
    void testHandlesNullStructuredContent() {
        ToolResponse response = new ToolResponse(
            false,
            List.of(new TextContent("{\"name\":\"test\",\"content\":\"data\"}")),
            null,
            null
        );

        TestToolOutputContext ctx = new TestToolOutputContext(response);
        guardrail.apply(ctx);

        // Should handle gracefully (FAIL_OPEN) - original response preserved
        ToolResponse result = ctx.getResponse();
        assertNotNull(result);
    }

    @Test
    void testStructurePreserved() throws Exception {
        String largeContent = "x".repeat(1000);
        SizeDto input = new SizeDto("testName", largeContent);
        String json = objectMapper.writeValueAsString(input);

        ToolResponse response = new ToolResponse(
            false,
            List.of(new TextContent(json)),
            objectMapper.valueToTree(input),
            null
        );

        TestToolOutputContext ctx = new TestToolOutputContext(response);
        guardrail.apply(ctx);

        ToolResponse result = ctx.getResponse();
        assertNotNull(result);

        // Verify structure preserved - the name field should survive truncation
        SizeDto resultDto = objectMapper.treeToValue(
            objectMapper.valueToTree(result.structuredContent()),
            SizeDto.class
        );
        assertEquals("testName", resultDto.name(),
            "Sibling field should survive truncation");
    }

    @Test
    void testTruncatesTextInsideTopLevelArray() throws Exception {
        String largeContent = "x".repeat(1000);
        ListDto input = new ListDto("keepme", List.of("small", largeContent));

        ToolResponse response = new ToolResponse(
            false,
            List.of(new TextContent(objectMapper.writeValueAsString(input))),
            objectMapper.valueToTree(input),
            null
        );

        TestToolOutputContext ctx = new TestToolOutputContext(response);
        guardrail.apply(ctx);

        ToolResponse result = ctx.getResponse();
        assertNotNull(result);
        JsonNode tree = objectMapper.valueToTree(result.structuredContent());

        // No bracketed junk key should be written onto the root object
        assertFalse(tree.has("items[1]"),
            "Guardrail must not add a bracketed field name to the object");
        // The large array element itself must be truncated
        String secondElement = tree.get("items").get(1).asText();
        assertTrue(secondElement.contains("[...response truncated"),
            "Large text inside a top-level array should be truncated");
        // Sibling field preserved
        assertEquals("keepme", tree.get("name").asText());
        // Within the size limit
        byte[] serialized = objectMapper.writeValueAsBytes(result.structuredContent());
        assertTrue(serialized.length <= 500,
            "Response should be truncated to fit within limit, was " + serialized.length);
    }

    @Test
    void testTruncatesTextInArrayOfObjects() throws Exception {
        String largeContent = "x".repeat(1000);
        NestedDto input = new NestedDto("keepme", List.of(new Inner(largeContent)));

        ToolResponse response = new ToolResponse(
            false,
            List.of(new TextContent(objectMapper.writeValueAsString(input))),
            objectMapper.valueToTree(input),
            null
        );

        TestToolOutputContext ctx = new TestToolOutputContext(response);
        guardrail.apply(ctx);

        ToolResponse result = ctx.getResponse();
        assertNotNull(result);
        byte[] serialized = objectMapper.writeValueAsBytes(result.structuredContent());
        assertTrue(serialized.length <= 500,
            "Response should be truncated to fit within limit, was " + serialized.length);

        JsonNode tree = objectMapper.valueToTree(result.structuredContent());
        String msg = tree.get("items").get(0).get("msg").asText();
        assertTrue(msg.contains("[...response truncated"),
            "Large text inside an array of objects should be truncated");
    }

    @Test
    void testDoesNotEnlargeResponseWhenLargestFieldTooSmallToTruncate() throws Exception {
        guardrail.maxResponseBytes = 100;
        // Field is over the 100-byte limit but too short to truncate without the notice enlarging it
        SizeDto input = new SizeDto("n", "x".repeat(130));
        JsonNode inputTree = objectMapper.valueToTree(input);
        int originalSize = objectMapper.writeValueAsBytes(inputTree).length;

        ToolResponse response = new ToolResponse(
            false,
            List.of(new TextContent(objectMapper.writeValueAsString(input))),
            inputTree,
            null
        );

        TestToolOutputContext ctx = new TestToolOutputContext(response);
        guardrail.apply(ctx);

        ToolResponse result = ctx.getResponse();
        assertNotNull(result);
        int resultSize = objectMapper.writeValueAsBytes(result.structuredContent()).length;
        assertTrue(resultSize <= originalSize,
            "Guardrail must never enlarge a response (was " + originalSize + ", now " + resultSize + ")");
    }

    /**
     * Test DTO for response size limit tests.
     *
     * @param name    the name field
     * @param content the content field (large)
     */
    public record SizeDto(
        @JsonProperty("name") String name,
        @JsonProperty("content") String content
    ) {
    }

    /**
     * Test DTO with a top-level list of strings.
     *
     * @param name  the name field
     * @param items the list of string items (may contain large values)
     */
    public record ListDto(
        @JsonProperty("name") String name,
        @JsonProperty("items") List<String> items
    ) {
    }

    /**
     * Test DTO with a top-level list of nested objects.
     *
     * @param name  the name field
     * @param items the list of nested objects (may contain large text)
     */
    public record NestedDto(
        @JsonProperty("name") String name,
        @JsonProperty("items") List<Inner> items
    ) {
    }

    /**
     * Nested object holding a single text field.
     *
     * @param msg the message field (may be large)
     */
    public record Inner(
        @JsonProperty("msg") String msg
    ) {
    }

    /**
     * Fake implementation of ToolOutputContext for testing.
     */
    static class TestToolOutputContext implements ToolOutputGuardrail.ToolOutputContext {
        private final ToolResponse initialResponse;
        ToolResponse capturedResponse;

        TestToolOutputContext(ToolResponse response) {
            this.initialResponse = response;
        }

        @Override
        public ToolResponse getResponse() {
            return capturedResponse != null ? capturedResponse : initialResponse;
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
            return null;
        }

        @Override
        public Meta getMeta() {
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
