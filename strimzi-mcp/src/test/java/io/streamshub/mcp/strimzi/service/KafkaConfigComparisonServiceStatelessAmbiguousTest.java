/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.service;

import io.quarkiverse.mcp.server.Elicitation;
import io.quarkiverse.mcp.server.McpException;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.streamshub.mcp.common.util.McpErrors;
import io.streamshub.mcp.strimzi.service.kafka.KafkaConfigComparisonService;
import io.streamshub.mcp.strimzi.service.kafka.KafkaConfigService;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies that {@code compare_kafka_clusters} falls back to the structured AMBIGUOUS error for
 * stateless clients instead of attempting a server-initiated elicitation.
 *
 * <p>The two-namespace comparison tool intentionally does not use MRTR elicitation (unlike the
 * single-namespace diagnostics); stateless clients re-call with explicit {@code namespace1}/
 * {@code namespace2}. A stateless client must therefore skip the stateful elicitation entirely —
 * calling {@code sendAndAwait()} would throw {@code IllegalStateException} and log a misleading
 * "Elicitation failed" warning before the fallback. This test asserts the client never reaches the
 * request builder, which is the observable guarantee of gating on
 * {@code isServerInitiatedRequestSupported()}.</p>
 */
@QuarkusTest
class KafkaConfigComparisonServiceStatelessAmbiguousTest {

    @InjectMock
    KafkaConfigService kafkaConfigService;

    @Inject
    KafkaConfigComparisonService comparisonService;

    KafkaConfigComparisonServiceStatelessAmbiguousTest() {
    }

    @Test
    void testStatelessAmbiguousNamespaceFallsBackToStructuredErrorWithoutElicitation() {
        // GIVEN: a stateless client that declares form-mode elicitation.
        Elicitation elicitation = Mockito.mock(Elicitation.class);
        when(elicitation.isServerInitiatedRequestSupported()).thenReturn(false);
        when(elicitation.isFormModeSupported()).thenReturn(true);

        // AND: the first cluster's namespace is ambiguous when auto-detected.
        McpException ambiguous = McpErrors.ambiguous("Kafka", "cluster-a", List.of("kafka-a", "kafka-b"));
        when(kafkaConfigService.getEffectiveConfig(isNull(), eq("cluster-a"))).thenThrow(ambiguous);

        // WHEN/THEN: the original structured error propagates unchanged.
        McpException thrown = assertThrows(McpException.class,
            () -> comparisonService.compare(null, "cluster-a", null, "cluster-b",
                null, elicitation, null, null));

        assertSame(ambiguous, thrown);
        // AND: the stateless client never attempts the doomed server-initiated elicitation.
        verify(elicitation, never()).requestBuilder();
    }
}
