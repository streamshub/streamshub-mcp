/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.tool.diagnostic;

import io.quarkiverse.mcp.server.InputRequiredException;
import io.quarkiverse.mcp.server.test.McpAssured;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.streamshub.mcp.common.dto.ConditionInfo;
import io.streamshub.mcp.common.dto.ReplicasInfo;
import io.streamshub.mcp.strimzi.dto.kafkamirrormaker2.KafkaMirrorMaker2DiagnosticReport;
import io.streamshub.mcp.strimzi.dto.kafkamirrormaker2.KafkaMirrorMaker2Response;
import io.streamshub.mcp.strimzi.service.kafkamirrormaker2.KafkaMirrorMaker2DiagnosticService;
import io.vertx.core.json.JsonObject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * End-to-end MRTR tests for the diagnostic tools driven by a STATELESS streamable HTTP client.
 *
 * <p>These exercise the full tool-invocation path — the {@code @Guarded} {@code GuardrailInterceptor},
 * {@code @WrapBusinessError}, and the MCP framework's {@code input_required} conversion — which the
 * service-layer unit tests bypass by calling {@code diagnose(...)} directly. Without the interceptor
 * passing {@link InputRequiredException} through unwrapped, the stateless client would receive a
 * wrapped tool error instead of an {@code input_required} result and MRTR would never work
 * end-to-end (regression guard for #251).</p>
 */
@QuarkusTest
class DiagnosticToolsStatelessMrtrTest {

    @ConfigProperty(name = "quarkus.http.test-port")
    int testPort;

    @InjectMock
    KafkaMirrorMaker2DiagnosticService mirrorMakerDiagnosticService;

    private McpAssured.McpStreamableTestClient client;

    DiagnosticToolsStatelessMrtrTest() {
    }

    @BeforeEach
    void setUp() {
        McpAssured.baseUri = URI.create("http://localhost:" + testPort);
        client = McpAssured.newStreamableClient()
            .setStateless()
            .build()
            .connect();
    }

    @AfterEach
    void tearDown() {
        if (client != null) {
            client.disconnect();
        }
    }

    /**
     * Round 1: a stateless client with no gathered input triggers an {@code input_required} result.
     * This is the exact path the {@code @Guarded} interceptor previously broke by wrapping
     * {@link InputRequiredException} in a {@code ToolCallException}.
     */
    @Test
    void testStatelessInputRequiredPropagatesAsInputRequiredResult() {
        when(mirrorMakerDiagnosticService.diagnose(
                any(), any(), any(), any(), any(), any(), any(), any()))
            .thenThrow(InputRequiredException.builder().setRequestState("kafka").build());

        client.when()
            .toolsCall("diagnose_kafka_mirror_maker")
            .withArguments(Map.of("mirrorMakerName", "my-mm2", "namespace", "kafka"))
            .withRawAssert(raw -> {
                String json = raw.encode();
                assertTrue(json.contains("input_required"),
                    "Stateless client must receive an input_required result, got: " + json);
            })
            .send()
            .thenAssertResults();
    }

    /**
     * Round 2: the client retries the same tool call carrying the gathered input; the diagnostic
     * completes and returns the report through the stateless transport.
     */
    @Test
    void testStatelessRetryWithInputResponsesReturnsReport() {
        KafkaMirrorMaker2Response mm2 = KafkaMirrorMaker2Response.summary(
            "my-mm2", "kafka", "Ready", ReplicasInfo.of(1, 1), "target-cluster",
            List.of("source-cluster"),
            List.of(ConditionInfo.of("Ready", "True", null, null, null)), null);
        KafkaMirrorMaker2DiagnosticReport report = KafkaMirrorMaker2DiagnosticReport.of(
            mm2, null, null, null, "Root cause: source cluster connectivity",
            List.of("mm2_status"), null);

        when(mirrorMakerDiagnosticService.diagnose(
                any(), any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(report);

        client.when()
            .toolsCall("diagnose_kafka_mirror_maker")
            .withArguments(Map.of("mirrorMakerName", "my-mm2", "namespace", "kafka"))
            .withInputResponses(new JsonObject().put("analysis", new JsonObject()))
            .withRequestState("kafka")
            .withAssert(response -> {
                assertFalse(response.isError());
                String json = response.content().getFirst().asText().text();
                assertTrue(json.contains("my-mm2"));
                assertTrue(json.contains("Root cause"));
            })
            .send()
            .thenAssertResults();
    }
}
