/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.service.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkiverse.mcp.server.Sampling;
import io.quarkiverse.mcp.server.SamplingMessage;
import io.quarkiverse.mcp.server.SamplingRequest;
import io.quarkiverse.mcp.server.TextContent;
import io.streamshub.mcp.common.guardrail.Redactor;
import io.streamshub.mcp.strimzi.dto.kafka.KafkaEffectiveConfigResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies that {@code compare_kafka_configs} redacts both cluster configs before they are sent
 * to the client's LLM through Sampling.
 */
class KafkaConfigComparisonServiceRedactionTest {

    KafkaConfigComparisonServiceRedactionTest() {
    }

    @Test
    void testSamplingDataIsRedactedBeforeLeavingTheServer() {
        KafkaConfigComparisonService service = new KafkaConfigComparisonService();
        service.objectMapper = new ObjectMapper();
        service.redactor = new Redactor(true, null);
        service.analysisMaxTokens = 1500;

        Sampling sampling = mock(Sampling.class);
        when(sampling.isServerInitiatedRequestSupported()).thenReturn(true);
        when(sampling.isSupported()).thenReturn(true);
        SamplingRequest.Builder requestBuilder = mock(SamplingRequest.Builder.class, Mockito.RETURNS_SELF);
        SamplingRequest builtRequest = mock(SamplingRequest.class);
        when(requestBuilder.build()).thenReturn(builtRequest);
        when(builtRequest.sendAndAwait()).thenReturn(null);
        when(sampling.requestBuilder()).thenReturn(requestBuilder);

        service.produceAnalysis(sampling,
            config("cluster-a", Map.of("ssl.keystore.password", "hunter2", "num.partitions", 3)),
            config("cluster-b", Map.of("sasl.jaas.config", "PlainLoginModule required password=\"s3cr3t\";",
                "num.partitions", 6)));

        ArgumentCaptor<SamplingMessage> captor = ArgumentCaptor.forClass(SamplingMessage.class);
        verify(requestBuilder).addMessage(captor.capture());
        String sent = ((TextContent) captor.getValue().content()).text();
        assertFalse(sent.contains("hunter2"), sent);
        assertFalse(sent.contains("s3cr3t"), sent);
        assertTrue(sent.contains("num.partitions"), sent);
    }

    private static KafkaEffectiveConfigResponse config(final String name, final Map<String, Object> brokerConfig) {
        return new KafkaEffectiveConfigResponse(name, "kafka", "4.2.0", null, brokerConfig,
            null, null, null, null, null, null, null, null, null, null, null, null, null);
    }
}
