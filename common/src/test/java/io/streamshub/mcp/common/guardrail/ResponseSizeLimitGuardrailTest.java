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
import io.quarkiverse.mcp.server.ToolManager.ToolArgument;
import io.quarkiverse.mcp.server.ToolManager.ToolInfo;
import io.quarkiverse.mcp.server.ToolOutputGuardrail;
import io.quarkiverse.mcp.server.ToolResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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

        // Verify structure preserved - the name field should survive truncation. Read it from the tree:
        // the root also carries "truncated": true, which SizeDto does not declare.
        JsonNode tree = objectMapper.valueToTree(result.structuredContent());
        assertEquals("testName", tree.get("name").asText(),
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

    @Test
    void testHeavilyEscapedPodLogsAreNotCollapsed() throws Exception {
        guardrail.maxResponseBytes = 80_000;
        String logs = GuardrailTestData.podLogs(12, 200);
        assertTrue(new TextTruncator(objectMapper).jsonSize(logs) - logs.length() > 80_000,
            "precondition: the escaping overhead alone exceeds the limit");
        LogsDto input = new LogsDto("mcp-cluster", podNames(12), 2_400, false, logs,
            "Logs retrieved from 12 pods (no errors found)");

        JsonNode tree = resultTree(applyTo(input, toolWithArguments("clusterName", "namespace", "filter",
            "keywords", "sinceMinutes", "startTime", "endTime", "tailLines", "previous", "container", "podNames")));

        int size = objectMapper.writeValueAsBytes(tree).length;
        assertTrue(size <= 80_000, "size " + size);
        // only whole 424-byte lines are cut, so at most one line of budget is unused
        assertTrue(size >= 79_000, "size " + size);
        String resultLogs = tree.get("logs").asText();
        for (int p = 0; p < 12; p++) {
            assertTrue(resultLogs.contains("=== Pod: mcp-cluster-broker-np1-" + p + " ===\n"), "pod " + p);
        }
        List<String> content = contentLines(resultLogs);
        for (String line : content) {
            assertNotNull(objectMapper.readTree(line), line);
        }
        assertTrue(tree.get("truncated").asBoolean());
        assertTrue(tree.get("has_more").asBoolean());
        assertEquals(content.size(), tree.get("log_lines").asInt());
        assertTrue(tree.get("message").asText().endsWith(" [Response truncated to fit "
            + "mcp.guardrail.max-response-bytes=80000. To see the rest, request less: fewer lines (tailLines),"
            + " a shorter time window (sinceMinutes), specific pods (podNames), or a filter.]"),
            tree.get("message").asText());
    }

    @Test
    void testTruncationNoteNamesOnlyTheLeversTheToolDeclares() throws Exception {
        guardrail.maxResponseBytes = 80_000;
        LogsDto input = new LogsDto("mcp-cluster", podNames(12), 2_400, false, GuardrailTestData.podLogs(12, 200),
            "Diagnosis complete");

        JsonNode tree = resultTree(applyTo(input, toolWithArguments("clusterName", "namespace", "sinceMinutes")));

        // a diagnostic tool has no tailLines or filter, so the note must not send the agent to them
        assertTrue(tree.get("message").asText().endsWith(" [Response truncated to fit "
            + "mcp.guardrail.max-response-bytes=80000. To see the rest, request less: "
            + "a shorter time window (sinceMinutes).]"), tree.get("message").asText());
    }

    @Test
    void testTruncationNoteIsGenericWhenToolDeclaresNoLevers() throws Exception {
        guardrail.maxResponseBytes = 80_000;
        LogsDto input = new LogsDto("mcp-cluster", podNames(12), 2_400, false, GuardrailTestData.podLogs(12, 200),
            "Diagnosis complete");
        String generic = " [Response truncated to fit mcp.guardrail.max-response-bytes=80000;"
            + " narrow the request to see the rest.]";

        String withoutLevers = resultTree(applyTo(input, toolWithArguments("clusterName", "namespace")))
            .get("message").asText();
        String withoutToolInfo = resultTree(applyTo(input)).get("message").asText();

        assertTrue(withoutLevers.endsWith(generic), withoutLevers);
        assertTrue(withoutToolInfo.endsWith(generic), withoutToolInfo);
    }

    @Test
    void testMoreLinesNeverReturnLessContent() throws Exception {
        guardrail.maxResponseBytes = 80_000;
        for (int lines : new int[] {100, 1_000}) {
            LogsDto input = new LogsDto("mcp-cluster", podNames(12), 12 * lines, false,
                GuardrailTestData.podLogs(12, lines), "Logs retrieved from 12 pods");

            int size = objectMapper.writeValueAsBytes(resultTree(applyTo(input))).length;

            // only whole 424-byte lines are cut, so at most one line of budget is unused
            assertTrue(size >= 79_000 && size <= 80_000, lines + " lines per pod gave " + size + " bytes");
        }
    }

    @Test
    void testSharesBudgetAcrossLogSections() throws Exception {
        guardrail.maxResponseBytes = 80_000;
        String operatorLogs = GuardrailTestData.podLogs(1, 75);
        String analysis = "a".repeat(3_000);
        CompositeDto input = new CompositeDto(
            new LogsDto("mcp-cluster", podNames(12), 4_800, false, GuardrailTestData.podLogs(12, 400), "cluster"),
            new LogsDto("mcp-cluster", podNames(1), 75, false, operatorLogs, "operator"),
            analysis, "Diagnosis complete");

        JsonNode tree = resultTree(applyTo(input));

        assertEquals(analysis, tree.get("analysis").asText());
        assertEquals(operatorLogs, tree.get("operator_logs").get("logs").asText());
        assertTrue(tree.get("cluster_logs").get("logs").asText().contains(TextTruncator.MARKER_PREFIX));
        assertTrue(objectMapper.writeValueAsBytes(tree).length <= 80_000);

        CompositeDto twoLarge = new CompositeDto(
            new LogsDto("mcp-cluster", podNames(12), 768, false, GuardrailTestData.podLogs(12, 64), "cluster"),
            new LogsDto("mcp-cluster", podNames(12), 768, false, GuardrailTestData.podLogs(12, 64), "operator"),
            "short analysis", "Diagnosis complete");
        TextTruncator truncator = new TextTruncator(objectMapper);
        assertTrue(truncator.jsonSize(twoLarge.clusterLogs().logs()) > 290_000, "precondition: ~300 KB section");

        JsonNode twoTree = resultTree(applyTo(twoLarge));

        for (String section : List.of("cluster_logs", "operator_logs")) {
            String sectionLogs = twoTree.get(section).get("logs").asText();
            assertTrue(sectionLogs.contains(TextTruncator.MARKER_PREFIX), section);
            assertTrue(truncator.jsonSize(sectionLogs) >= 30_000, section + " kept " + truncator.jsonSize(sectionLogs));
        }
        assertTrue(objectMapper.writeValueAsBytes(twoTree).length <= 80_000);
    }

    @Test
    void testGivesUnusedShareOfOneFieldToTheOthers() throws Exception {
        guardrail.maxResponseBytes = 80_000;
        String operatorLogs = GuardrailTestData.podLogs(1, 50,
            (pod, i) -> GuardrailTestData.viaqLine(pod, i, 2_750));
        CompositeDto input = new CompositeDto(
            new LogsDto("mcp-cluster", podNames(12), 4_800, false, GuardrailTestData.podLogs(12, 400), "cluster"),
            new LogsDto("mcp-cluster", podNames(1), 50, false, operatorLogs, "operator"),
            "short analysis", "Diagnosis complete");

        JsonNode tree = resultTree(applyTo(input));

        // operator_logs (the smaller field) keeps one whole ~25 KB record of its ~39 KB share; the ~14 KB
        // it cannot use goes to cluster_logs instead of being lost
        int size = objectMapper.writeValueAsBytes(tree).length;
        assertTrue(size <= 80_000 && size >= 78_000, "size " + size);
        assertTrue(tree.get("operator_logs").get("logs").asText()
            .contains("[...response truncated: 49 of 50 lines omitted]"));
        assertEquals(1, tree.get("operator_logs").get("log_lines").asInt());
    }

    @Test
    void testLeavesResponseUnchangedWhenNonTextContentExceedsLimit() throws Exception {
        guardrail.maxResponseBytes = 80_000;
        List<Double> samples = new ArrayList<>();
        for (int i = 0; i < 20_000; i++) {
            samples.add(i + 0.123456789);
        }
        SamplesDto input = new SamplesDto(samples, "n".repeat(50_000));

        TestToolOutputContext ctx = applyTo(input);

        assertNull(ctx.capturedResponse, "response must be left unchanged");
    }

    @Test
    void testTruncatesWhenNonTextContentIsJustUnderLimit() throws Exception {
        guardrail.maxResponseBytes = 80_000;
        List<Double> samples = new ArrayList<>();
        while (objectMapper.writeValueAsBytes(samples).length < 79_300) {
            samples.add(samples.size() + 0.123456789);
        }
        int samplesBytes = objectMapper.writeValueAsBytes(samples).length;
        assertTrue(samplesBytes < 80_000 && samplesBytes + 1_024 > 80_000,
            "precondition: non-text content is under the limit but within one kept-share of it");
        MixedDto input = new MixedDto(samples, GuardrailTestData.podLogs(12, 50));

        JsonNode tree = resultTree(applyTo(input));

        assertTrue(objectMapper.writeValueAsBytes(tree).length <= 80_000);
        assertTrue(tree.get("truncated").asBoolean());
        assertEquals(samples.size(), tree.get("samples").size(), "non-text content is never cut");
    }

    @Test
    void testTruncatesPlainTextResponse() {
        guardrail.maxResponseBytes = 5_000;
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 20_000; i++) {
            text.append("line ").append(i).append('\n');
        }
        TestToolOutputContext ctx = new TestToolOutputContext(
            new ToolResponse(false, List.of(new TextContent(text.toString())), null, null));

        guardrail.apply(ctx);

        ToolResponse result = ctx.getResponse();
        String resultText = ((TextContent) result.content().getFirst()).text();
        assertTrue(resultText.getBytes(StandardCharsets.UTF_8).length <= 5_000);
        assertTrue(resultText.stripTrailing()
                .matches("(?s).*\\[\\.\\.\\.response truncated: \\d+ of 20000 lines omitted]"),
            resultText.substring(Math.max(0, resultText.length() - 120)));
        assertNull(result.structuredContent());
    }

    private TestToolOutputContext applyTo(final Object dto) throws Exception {
        return applyTo(dto, null);
    }

    private TestToolOutputContext applyTo(final Object dto, final ToolInfo tool) throws Exception {
        TestToolOutputContext ctx = new TestToolOutputContext(new ToolResponse(
            false,
            List.of(new TextContent(objectMapper.writeValueAsString(dto))),
            objectMapper.valueToTree(dto),
            null), tool);
        guardrail.apply(ctx);
        return ctx;
    }

    private static ToolInfo toolWithArguments(final String... names) {
        List<ToolArgument> arguments = new ArrayList<>();
        for (String name : names) {
            arguments.add(new ToolArgument(name, name, false, String.class, null));
        }
        ToolInfo tool = mock(ToolInfo.class);
        when(tool.arguments()).thenReturn(arguments);
        return tool;
    }

    private JsonNode resultTree(final TestToolOutputContext ctx) {
        assertNotNull(ctx.capturedResponse, "response should have been truncated");
        return objectMapper.valueToTree(ctx.capturedResponse.structuredContent());
    }

    private static List<String> podNames(final int pods) {
        List<String> names = new ArrayList<>();
        for (int p = 0; p < pods; p++) {
            names.add("mcp-cluster-broker-np1-" + p);
        }
        return names;
    }

    private static List<String> contentLines(final String logs) {
        List<String> lines = new ArrayList<>();
        for (String line : logs.split("\n")) {
            if (!line.startsWith("=== Pod: ") && !line.startsWith(TextTruncator.MARKER_PREFIX)) {
                lines.add(line);
            }
        }
        return lines;
    }

    /**
     * Test DTO shaped like a log tool response.
     *
     * @param clusterName the cluster name
     * @param pods        the pod names
     * @param logLines    the number of log lines
     * @param hasMore     whether more lines are available
     * @param logs        the log text
     * @param message     the summary message
     */
    public record LogsDto(
        @JsonProperty("cluster_name") String clusterName,
        @JsonProperty("pods") List<String> pods,
        @JsonProperty("log_lines") int logLines,
        @JsonProperty("has_more") boolean hasMore,
        @JsonProperty("logs") String logs,
        @JsonProperty("message") String message
    ) {
    }

    /**
     * Test DTO shaped like a composite diagnostic with two log sections.
     *
     * @param clusterLogs  the cluster log section
     * @param operatorLogs the operator log section
     * @param analysis     the analysis text
     * @param message      the summary message
     */
    public record CompositeDto(
        @JsonProperty("cluster_logs") LogsDto clusterLogs,
        @JsonProperty("operator_logs") LogsDto operatorLogs,
        @JsonProperty("analysis") String analysis,
        @JsonProperty("message") String message
    ) {
    }

    /**
     * Test DTO with a large number series next to a log text.
     *
     * @param samples the samples
     * @param logs    the log text
     */
    public record MixedDto(
        @JsonProperty("samples") List<Double> samples,
        @JsonProperty("logs") String logs
    ) {
    }

    /**
     * Test DTO whose bulk is non-text (a number series).
     *
     * @param samples the samples
     * @param note    a text note
     */
    public record SamplesDto(
        @JsonProperty("samples") List<Double> samples,
        @JsonProperty("note") String note
    ) {
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
        private final ToolInfo tool;
        ToolResponse capturedResponse;

        TestToolOutputContext(ToolResponse response) {
            this(response, null);
        }

        TestToolOutputContext(ToolResponse response, ToolInfo tool) {
            this.initialResponse = response;
            this.tool = tool;
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
            return tool;
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
