/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.tool.diagnostic;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.quarkiverse.mcp.server.ClientCapability;
import io.quarkiverse.mcp.server.McpException;
import io.quarkiverse.mcp.server.test.McpAssured;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.streamshub.mcp.common.dto.ConditionInfo;
import io.streamshub.mcp.common.dto.ReplicasInfo;
import io.streamshub.mcp.common.util.McpErrors;
import io.streamshub.mcp.strimzi.dto.kafkamirrormaker2.KafkaMirrorMaker2Response;
import io.streamshub.mcp.strimzi.service.kafkamirrormaker2.KafkaMirrorMaker2Service;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

/**
 * End-to-end MRTR tests for stateless <em>namespace elicitation</em>, driven by a stateless
 * streamable HTTP client that declares the ELICITATION capability.
 *
 * <p>Unlike {@link DiagnosticToolsStatelessMrtrTest} (which mocks the diagnostic service and
 * exercises only the interceptor/framework passthrough), this test runs the real
 * {@code KafkaMirrorMaker2DiagnosticService} and mocks only its downstream dependencies, so the
 * real {@code NamespaceElicitationHelper.elicitNamespaceMrtr} path — throwing an
 * {@code input_required} for the namespace and parsing the client's selection back — is covered
 * end-to-end (closes the coverage gap for #251).</p>
 */
@QuarkusTest
class DiagnosticToolsStatelessElicitationMrtrTest {

    @ConfigProperty(name = "quarkus.http.test-port")
    int testPort;

    @InjectMock
    KafkaMirrorMaker2Service mirrorMakerService;

    @InjectMock
    KubernetesClient kubernetesClient;

    private McpAssured.McpStreamableTestClient client;

    DiagnosticToolsStatelessElicitationMrtrTest() {
    }

    @BeforeEach
    void setUp() {
        McpAssured.baseUri = URI.create("http://localhost:" + testPort);
        client = McpAssured.newStreamableClient()
            .setStateless()
            .setClientCapabilities(new ClientCapability(ClientCapability.ELICITATION, Map.of()))
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
     * Round 1: an ambiguous namespace on a stateless client that supports elicitation yields an
     * {@code input_required} result listing the candidate namespaces.
     */
    @Test
    void testStatelessAmbiguousNamespaceReturnsInputRequiredWithCandidates() {
        McpException ambiguous = McpErrors.ambiguous(
            "KafkaMirrorMaker2", "my-mm2", List.of("kafka-a", "kafka-b"));
        when(mirrorMakerService.getMirrorMaker(isNull(), eq("my-mm2"))).thenThrow(ambiguous);

        client.when()
            .toolsCall("diagnose_kafka_mirror_maker")
            .withArguments(Map.of("mirrorMakerName", "my-mm2"))
            .withRawAssert(raw -> {
                String json = raw.encode();
                assertTrue(json.contains("input_required"),
                    "Stateless client must receive an input_required result, got: " + json);
                assertTrue(json.contains("kafka-a") && json.contains("kafka-b"),
                    "input_required must list candidate namespaces, got: " + json);
            })
            .send()
            .thenAssertResults();
    }

    /**
     * Round 2: the client retries carrying the selected namespace; the real service resolves the
     * MM2 in that namespace and returns the report (no sampling capability, so analysis is skipped).
     */
    @Test
    void testStatelessNamespaceSelectionResolvesAndReturnsReport() {
        McpException ambiguous = McpErrors.ambiguous(
            "KafkaMirrorMaker2", "my-mm2", List.of("kafka-a", "kafka-b"));
        when(mirrorMakerService.getMirrorMaker(isNull(), eq("my-mm2"))).thenThrow(ambiguous);

        KafkaMirrorMaker2Response mm2 = KafkaMirrorMaker2Response.summary(
            "my-mm2", "kafka-a", "Ready", ReplicasInfo.of(1, 1), "target-cluster",
            List.of("source-cluster"),
            List.of(ConditionInfo.of("Ready", "True", null, null, null)));
        when(mirrorMakerService.getMirrorMaker(eq("kafka-a"), eq("my-mm2"))).thenReturn(mm2);

        client.when()
            .toolsCall("diagnose_kafka_mirror_maker")
            .withArguments(Map.of("mirrorMakerName", "my-mm2"))
            .withInputResponses(new JsonObject().put("namespace", new JsonObject()
                .put("action", "accept")
                .put("content", new JsonObject().put("namespace", "kafka-a"))))
            .withAssert(response -> {
                assertFalse(response.isError());
                String json = response.content().getFirst().asText().text();
                assertTrue(json.contains("my-mm2"), "report must contain the MM2 name, got: " + json);
                assertTrue(json.contains("kafka-a"),
                    "report must reflect the resolved namespace, got: " + json);
            })
            .send()
            .thenAssertResults();
    }
}
