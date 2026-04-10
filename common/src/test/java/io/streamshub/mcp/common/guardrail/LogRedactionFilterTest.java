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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Unit tests for {@link LogRedactionFilter}.
 */
class LogRedactionFilterTest {

    private LogRedactionFilter filter;

    LogRedactionFilterTest() {
    }

    @BeforeEach
    void setUp() {
        filter = new LogRedactionFilter();
        filter.objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        filter.enabled = true;
    }

    @Test
    void testRedactsBearerToken() {
        String result = filter.applyRedaction("Authorization: Bearer eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9.xyz");
        assertEquals("Authorization: Bearer [REDACTED]", result);
    }

    @Test
    void testRedactsPassword() {
        String result = filter.applyRedaction("password=mysecretpassword123");
        assertEquals("password=[REDACTED]", result);
    }

    @Test
    void testRedactsPasswordWithColon() {
        String result = filter.applyRedaction("password: secretvalue");
        assertEquals("password=[REDACTED]", result);
    }

    @Test
    void testRedactsApiKey() {
        String result = filter.applyRedaction("api_key=sk-1234567890abcdef");
        assertEquals("api_key=[REDACTED]", result);
    }

    @Test
    void testRedactsConnectionString() {
        String result = filter.applyRedaction("jdbc:postgresql://admin:secret@db.example.com:5432/mydb");
        assertEquals("jdbc:postgresql://[REDACTED]@db.example.com:5432/mydb", result);
    }

    @Test
    void testRedactsBase64Token() {
        String longToken = "eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9eyJhbGciOiJSUzI1Ni";
        String result = filter.applyRedaction("token: " + longToken);
        assertFalse(result.contains(longToken));
    }

    @Test
    void testPreservesCleanText() {
        String clean = "INFO 2025-01-01 Kafka broker started successfully";
        assertEquals(clean, filter.applyRedaction(clean));
    }

    @Test
    void testHandlesNullAndEmpty() {
        assertNull(filter.applyRedaction(null));
        assertEquals("", filter.applyRedaction(""));
    }

    @Test
    void testDisabledFilterPassesThrough() {
        filter.enabled = false;
        LogDto input = new LogDto("Bearer secrettoken123");
        LogDto result = (LogDto) filter.filterOutput("test_tool", input);
        assertEquals("Bearer secrettoken123", result.logs());
    }

    @Test
    void testHandlesNullResult() {
        assertNull(filter.filterOutput("test_tool", null));
    }

    @Test
    void testRedactsDtoFields() {
        LogDto input = new LogDto("password=secret123 and api-key=abc123");
        LogDto result = (LogDto) filter.filterOutput("test_tool", input);
        assertFalse(result.logs().contains("secret123"));
        assertFalse(result.logs().contains("abc123"));
    }

    /**
     * Test DTO for log redaction tests.
     *
     * @param logs the log content
     */
    public record LogDto(
        @JsonProperty("logs") String logs
    ) {
    }
}
