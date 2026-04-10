/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.guardrail;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ResponseSizeLimitFilter}.
 */
class ResponseSizeLimitFilterTest {

    private ResponseSizeLimitFilter filter;
    private ObjectMapper objectMapper;

    ResponseSizeLimitFilterTest() {
    }

    @BeforeEach
    void setUp() {
        filter = new ResponseSizeLimitFilter();
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        filter.objectMapper = objectMapper;
        filter.maxResponseBytes = 500;
    }

    @Test
    void testPassesThroughSmallResponse() throws Exception {
        SizeDto input = new SizeDto("short", "data");
        SizeDto result = (SizeDto) filter.filterOutput("test_tool", input);
        assertTrue(objectMapper.writeValueAsBytes(result).length <= 500);
    }

    @Test
    void testTruncatesOversizedResponse() throws Exception {
        String largeContent = "x".repeat(1000);
        SizeDto input = new SizeDto("name", largeContent);
        SizeDto result = (SizeDto) filter.filterOutput("test_tool", input);
        byte[] serialized = objectMapper.writeValueAsBytes(result);
        assertTrue(serialized.length <= 500,
            "Response should be truncated to fit within limit, was " + serialized.length);
    }

    @Test
    void testTruncatedContentContainsNotice() {
        String largeContent = "x".repeat(1000);
        SizeDto input = new SizeDto("name", largeContent);
        SizeDto result = (SizeDto) filter.filterOutput("test_tool", input);
        assertTrue(result.content().contains("[...response truncated"));
    }

    @Test
    void testHandlesNullResult() {
        assertNull(filter.filterOutput("test_tool", null));
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
}
