/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.guardrail;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
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
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link LogRedactionGuardrail}.
 */
class LogRedactionGuardrailTest {

    private LogRedactionGuardrail guardrail;
    private ObjectMapper objectMapper;

    LogRedactionGuardrailTest() {
    }

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        guardrail = new LogRedactionGuardrail();
        guardrail.mapper = objectMapper;
        guardrail.enabled = true;
        guardrail.customPatterns = Optional.empty();
        guardrail.init();
    }

    @Test
    void testRedactsBearerToken() throws JsonProcessingException {
        LogDto dto = new LogDto("Authorization: Bearer eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9.xyz");
        FakeToolOutputContext ctx = createContext(dto);
        guardrail.apply(ctx);

        // Verify redaction happened in both text and structuredContent
        ToolResponse response = ctx.capturedResponse;
        assertNotNull(response);
        String textContent = extractTextContent(response);
        assertTrue(textContent.contains("Bearer [REDACTED]"));
        assertFalse(textContent.contains("eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9.xyz"));

        // Verify structuredContent is also redacted
        JsonNode structuredContent = (JsonNode) response.structuredContent();
        assertNotNull(structuredContent);
        String logs = structuredContent.get("logs").asText();
        assertTrue(logs.contains("Bearer [REDACTED]"));
    }

    @Test
    void testRedactsPassword() throws JsonProcessingException {
        LogDto dto = new LogDto("password=mysecretpassword123");
        FakeToolOutputContext ctx = createContext(dto);
        guardrail.apply(ctx);

        ToolResponse response = ctx.capturedResponse;
        String textContent = extractTextContent(response);
        assertTrue(textContent.contains("password=[REDACTED]"));
        assertFalse(textContent.contains("mysecretpassword123"));
    }

    @Test
    void testRedactsPasswordWithColon() throws JsonProcessingException {
        LogDto dto = new LogDto("password: secretvalue");
        FakeToolOutputContext ctx = createContext(dto);
        guardrail.apply(ctx);

        ToolResponse response = ctx.capturedResponse;
        String textContent = extractTextContent(response);
        assertTrue(textContent.contains("password=[REDACTED]"));
        assertFalse(textContent.contains("secretvalue"));
    }

    @Test
    void testRedactsApiKey() throws JsonProcessingException {
        LogDto dto = new LogDto("api_key=sk-1234567890abcdef");
        FakeToolOutputContext ctx = createContext(dto);
        guardrail.apply(ctx);

        ToolResponse response = ctx.capturedResponse;
        String textContent = extractTextContent(response);
        assertTrue(textContent.contains("api_key=[REDACTED]"));
        assertFalse(textContent.contains("sk-1234567890abcdef"));
    }

    @Test
    void testRedactsConnectionString() throws JsonProcessingException {
        LogDto dto = new LogDto("jdbc:postgresql://admin:secret@db.example.com:5432/mydb");
        FakeToolOutputContext ctx = createContext(dto);
        guardrail.apply(ctx);

        ToolResponse response = ctx.capturedResponse;
        String textContent = extractTextContent(response);
        assertTrue(textContent.contains("://[REDACTED]@db.example.com"));
        assertFalse(textContent.contains("admin:secret"));
    }

    @Test
    void testDoesNotRedactAcrossNewlines() throws JsonProcessingException {
        String multiline = "connecting to server://hostname\nother_user:not_a_password@elsewhere";
        LogDto dto = new LogDto(multiline);
        FakeToolOutputContext ctx = createContext(dto);
        guardrail.apply(ctx);

        // No redaction should occur, so setResponse should not be called
        assertNull(ctx.capturedResponse);

        // Test CRLF
        String crlf = "connecting to server://hostname\r\nother_user:not_a_password@elsewhere";
        LogDto dto2 = new LogDto(crlf);
        FakeToolOutputContext ctx2 = createContext(dto2);
        guardrail.apply(ctx2);

        // No redaction should occur, so setResponse should not be called
        assertNull(ctx2.capturedResponse);
    }

    @Test
    void testRedactsBase64Token() throws JsonProcessingException {
        String longToken = "eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9eyJhbGciOiJSUzI1Ni";
        LogDto dto = new LogDto("token: " + longToken);
        FakeToolOutputContext ctx = createContext(dto);
        guardrail.apply(ctx);

        ToolResponse response = ctx.capturedResponse;
        String textContent = extractTextContent(response);
        assertFalse(textContent.contains(longToken));
    }

    @Test
    void testPreservesCleanText() throws JsonProcessingException {
        String clean = "INFO 2025-01-01 Kafka broker started successfully";
        LogDto dto = new LogDto(clean);
        FakeToolOutputContext ctx = createContext(dto);
        guardrail.apply(ctx);

        // No modification should occur, so setResponse should not be called
        assertNull(ctx.capturedResponse);
    }

    @Test
    void testHandlesNullAndEmpty() throws JsonProcessingException {
        // Null
        LogDto dto1 = new LogDto(null);
        FakeToolOutputContext ctx1 = createContext(dto1);
        guardrail.apply(ctx1);
        assertNull(ctx1.capturedResponse);

        // Empty
        LogDto dto2 = new LogDto("");
        FakeToolOutputContext ctx2 = createContext(dto2);
        guardrail.apply(ctx2);
        assertNull(ctx2.capturedResponse);
    }

    @Test
    void testDisabledFilterPassesThrough() throws JsonProcessingException {
        guardrail.enabled = false;
        LogDto dto = new LogDto("Bearer secrettoken123");
        FakeToolOutputContext ctx = createContext(dto);
        guardrail.apply(ctx);

        // No modification should occur when disabled
        assertNull(ctx.capturedResponse);
    }

    @Test
    void testRedactsDtoFields() throws JsonProcessingException {
        LogDto dto = new LogDto("password=secret123 and api-key=abc123");
        FakeToolOutputContext ctx = createContext(dto);
        guardrail.apply(ctx);

        ToolResponse response = ctx.capturedResponse;
        String textContent = extractTextContent(response);
        assertFalse(textContent.contains("secret123"));
        assertFalse(textContent.contains("abc123"));
    }

    @Test
    void testCustomPatternRedacts() throws JsonProcessingException {
        guardrail.customPatterns = Optional.of(List.of("(?i)ssn\\s*[=:]\\s*\\d{3}-\\d{2}-\\d{4}"));
        guardrail.init();

        LogDto dto = new LogDto("User ssn=123-45-6789 logged in");
        FakeToolOutputContext ctx = createContext(dto);
        guardrail.apply(ctx);

        ToolResponse response = ctx.capturedResponse;
        String textContent = extractTextContent(response);
        assertFalse(textContent.contains("123-45-6789"));
        assertTrue(textContent.contains("[REDACTED]"));
    }

    @Test
    void testCustomPatternMergedWithDefaults() throws JsonProcessingException {
        guardrail.customPatterns = Optional.of(List.of("(?i)internal-id\\s*=\\s*\\S+"));
        guardrail.init();

        LogDto dto = new LogDto("Bearer secret123 internal-id=abc-999");
        FakeToolOutputContext ctx = createContext(dto);
        guardrail.apply(ctx);

        ToolResponse response = ctx.capturedResponse;
        String textContent = extractTextContent(response);
        assertFalse(textContent.contains("secret123"));
        assertFalse(textContent.contains("abc-999"));
    }

    @Test
    void testInvalidCustomPatternSkipped() throws JsonProcessingException {
        guardrail.customPatterns = Optional.of(List.of("[invalid", "(?i)valid-pattern=\\S+"));
        guardrail.init();

        LogDto dto = new LogDto("password=secret valid-pattern=sensitive");
        FakeToolOutputContext ctx = createContext(dto);
        guardrail.apply(ctx);

        ToolResponse response = ctx.capturedResponse;
        String textContent = extractTextContent(response);
        assertFalse(textContent.contains("secret"));
        assertFalse(textContent.contains("sensitive"));
    }

    @Test
    void testEmptyCustomPatterns() throws JsonProcessingException {
        guardrail.customPatterns = Optional.of(List.of());
        guardrail.init();

        LogDto dto = new LogDto("password=secret123");
        FakeToolOutputContext ctx = createContext(dto);
        guardrail.apply(ctx);

        ToolResponse response = ctx.capturedResponse;
        String textContent = extractTextContent(response);
        assertFalse(textContent.contains("secret123"));
    }

    @Test
    void testMultipleCustomPatterns() throws JsonProcessingException {
        guardrail.customPatterns = Optional.of(List.of(
            "(?i)x-custom-header:\\s*\\S+",
            "(?i)account-id=\\S+",
            "(?i)session-token=\\S+"
        ));
        guardrail.init();

        LogDto dto = new LogDto("x-custom-header: val1 account-id=A123 session-token=tok456");
        FakeToolOutputContext ctx = createContext(dto);
        guardrail.apply(ctx);

        ToolResponse response = ctx.capturedResponse;
        String textContent = extractTextContent(response);
        assertFalse(textContent.contains("val1"));
        assertFalse(textContent.contains("A123"));
        assertFalse(textContent.contains("tok456"));
    }

    @Test
    void testRedactionFailureReturnsErrorNotRawPayload() throws JsonProcessingException {
        // Create a context with a response that will cause GuardedResponses to fail
        // We'll use a broken ObjectMapper to force serialization failure
        ObjectMapper brokenMapper = new ObjectMapper() {
            @Override
            public String writeValueAsString(Object value) throws JsonProcessingException {
                throw new JsonProcessingException("Forced serialization failure") { };
            }
        };
        brokenMapper.registerModule(new JavaTimeModule());
        guardrail.mapper = brokenMapper;

        LogDto dto = new LogDto("password=secret123");
        FakeToolOutputContext ctx = createContext(dto);
        guardrail.apply(ctx);

        // Verify response is an error, not the raw payload
        ToolResponse response = ctx.capturedResponse;
        assertNotNull(response);
        assertTrue(response.isError());
        String errorContent = extractTextContent(response);
        assertTrue(errorContent.contains("could not be sanitized"));
        assertFalse(errorContent.contains("secret123"));
    }

    @Test
    void testRedactionUpdatesBothTextAndStructuredContent() throws JsonProcessingException {
        LogDto dto = new LogDto("password=secret123");
        FakeToolOutputContext ctx = createContext(dto);
        guardrail.apply(ctx);

        ToolResponse response = ctx.capturedResponse;
        assertNotNull(response);

        // Check text content
        String textContent = extractTextContent(response);
        assertTrue(textContent.contains("password=[REDACTED]"));
        assertFalse(textContent.contains("secret123"));

        // Check structuredContent
        JsonNode structuredContent = (JsonNode) response.structuredContent();
        assertNotNull(structuredContent);
        String logs = structuredContent.get("logs").asText();
        assertTrue(logs.contains("password=[REDACTED]"));
        assertFalse(logs.contains("secret123"));
    }

    @Test
    void testRedactsSecretKey() throws JsonProcessingException {
        LogDto dto = new LogDto("secret_key=sk-abc123def456");
        FakeToolOutputContext ctx = createContext(dto);
        guardrail.apply(ctx);

        ToolResponse response = ctx.capturedResponse;
        String textContent = extractTextContent(response);
        assertTrue(textContent.contains("secret_key=[REDACTED]"));
        assertFalse(textContent.contains("sk-abc123def456"));
    }

    @Test
    void testRedactsGenericSecret() throws JsonProcessingException {
        LogDto dto = new LogDto("client.secret: topsecretvalue");
        FakeToolOutputContext ctx = createContext(dto);
        guardrail.apply(ctx);

        ToolResponse response = ctx.capturedResponse;
        String textContent = extractTextContent(response);
        assertTrue(textContent.contains("secret=[REDACTED]"));
        assertFalse(textContent.contains("topsecretvalue"));
    }

    @Test
    void testRedactsToken() throws JsonProcessingException {
        LogDto dto = new LogDto("token=eyJhbGciOiJIUzI1NiJ9xyz");
        FakeToolOutputContext ctx = createContext(dto);
        guardrail.apply(ctx);

        ToolResponse response = ctx.capturedResponse;
        String textContent = extractTextContent(response);
        assertTrue(textContent.contains("token=[REDACTED]"));
        assertFalse(textContent.contains("eyJhbGciOiJIUzI1NiJ9xyz"));
    }

    @Test
    void testRedactsPemPrivateKey() throws JsonProcessingException {
        String pem = "-----BEGIN RSA PRIVATE KEY-----\nMIIEpAIBAAKCAQEA123\nabc==\n-----END RSA PRIVATE KEY-----";
        LogDto dto = new LogDto("keystore dump: " + pem);
        FakeToolOutputContext ctx = createContext(dto);
        guardrail.apply(ctx);

        ToolResponse response = ctx.capturedResponse;
        String textContent = extractTextContent(response);
        assertTrue(textContent.contains("[REDACTED]"));
        assertFalse(textContent.contains("BEGIN RSA PRIVATE KEY"));
        assertFalse(textContent.contains("MIIEpAIBAAKCAQEA123"));
    }

    @Test
    void testDoesNotRedactDottedTokenConfigKey() throws JsonProcessingException {
        LogDto dto = new LogDto("delegation.token.max.lifetime.ms=604800000");
        FakeToolOutputContext ctx = createContext(dto);
        guardrail.apply(ctx);

        // No modification should occur, so setResponse should not be called
        assertNull(ctx.capturedResponse);
    }

    /**
     * Create a fake ToolOutputContext with a response containing both TextContent and structuredContent.
     */
    private FakeToolOutputContext createContext(LogDto dto) throws JsonProcessingException {
        String json = objectMapper.writeValueAsString(dto);
        JsonNode tree = objectMapper.valueToTree(dto);
        ToolResponse response = new ToolResponse(
            false,
            List.of(new TextContent(json)),
            tree,
            null
        );
        return new FakeToolOutputContext(response);
    }

    /**
     * Extract text content from the first TextContent block in the response.
     */
    private String extractTextContent(ToolResponse response) {
        if (response.content() == null || response.content().isEmpty()) {
            return "";
        }
        return ((TextContent) response.content().get(0)).text();
    }

    /**
     * Fake ToolOutputContext for testing.
     */
    static class FakeToolOutputContext implements ToolOutputGuardrail.ToolOutputContext {
        private final ToolResponse initialResponse;
        ToolResponse capturedResponse;

        FakeToolOutputContext(ToolResponse initialResponse) {
            this.initialResponse = initialResponse;
        }

        @Override
        public ToolResponse getResponse() {
            return initialResponse;
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
