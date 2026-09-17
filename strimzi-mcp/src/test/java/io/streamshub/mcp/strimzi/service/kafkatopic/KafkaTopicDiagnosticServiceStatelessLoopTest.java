/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.service.kafkatopic;

import io.quarkiverse.mcp.server.Elicitation;
import io.quarkiverse.mcp.server.ElicitationResponse;
import io.quarkiverse.mcp.server.InputRequiredException;
import io.quarkiverse.mcp.server.InputResponses;
import io.quarkiverse.mcp.server.McpException;
import io.quarkiverse.mcp.server.Sampling;
import io.quarkiverse.mcp.server.SamplingRequest;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.streamshub.mcp.common.util.McpErrors;
import io.streamshub.mcp.strimzi.dto.kafkatopic.KafkaTopicResponse;
import io.streamshub.mcp.strimzi.service.kafka.KafkaService;
import io.streamshub.mcp.strimzi.service.metrics.KafkaExporterMetricsService;
import io.streamshub.mcp.strimzi.service.operator.StrimziEventsService;
import io.streamshub.mcp.strimzi.service.operator.StrimziOperatorService;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression test for the stateless MRTR re-elicitation loop (issue #251 final review, FIX 2).
 *
 * <p>Reproduces the compound case: a stateless client, an ambiguous topic namespace, and a
 * missing/null parent cluster. Because {@code KafkaTopicResponse} carries no namespace accessor,
 * the resolved topic namespace must be threaded out of {@code gatherTopicStatus} and used as the
 * MRTR {@code requestState}; otherwise the analysis-response round re-hits the ambiguity and
 * re-elicits the namespace forever.</p>
 */
@QuarkusTest
class KafkaTopicDiagnosticServiceStatelessLoopTest {

    @InjectMock
    KafkaTopicService topicService;

    @InjectMock
    KafkaService kafkaService;

    @InjectMock
    StrimziEventsService eventsService;

    @InjectMock
    StrimziOperatorService operatorService;

    @InjectMock
    KafkaExporterMetricsService exporterMetricsService;

    @Inject
    KafkaTopicDiagnosticService topicDiagnosticService;

    KafkaTopicDiagnosticServiceStatelessLoopTest() {
    }

    @Test
    void testStatelessAmbiguousTopicWithNullClusterResolvesViaRequestStateWithoutLooping() {
        // GIVEN: the topic is ambiguous when namespace is unknown, but resolvable in "kafka".
        McpException ambiguous = McpErrors.ambiguous(
            "KafkaTopic", "my-topic", List.of("kafka", "kafka-prod"));
        when(topicService.getTopic(isNull(), isNull(), eq("my-topic"))).thenThrow(ambiguous);

        KafkaTopicResponse topic = KafkaTopicResponse.of(
            "my-topic", "my-cluster", 3, 3, "Ready", null);
        when(topicService.getTopic(eq("kafka"), isNull(), eq("my-topic"))).thenReturn(topic);

        // AND: a stateless client that has already supplied the elicited namespace ("kafka").
        Elicitation elicitation = mockStatelessElicitationWithNamespace("kafka");

        // AND: a stateless, sampling-capable client with no analysis response yet.
        Sampling sampling = Mockito.mock(Sampling.class);
        when(sampling.isServerInitiatedRequestSupported()).thenReturn(false);
        when(sampling.isSupported()).thenReturn(true);

        InputResponses samplingResponses = Mockito.mock(InputResponses.class);
        when(samplingResponses.has("analysis")).thenReturn(false);
        when(sampling.inputResponses()).thenReturn(samplingResponses);

        SamplingRequest.Builder requestBuilder =
            Mockito.mock(SamplingRequest.Builder.class, Mockito.RETURNS_SELF);
        SamplingRequest builtRequest = Mockito.mock(SamplingRequest.class);
        when(requestBuilder.build()).thenReturn(builtRequest);
        when(sampling.requestBuilder()).thenReturn(requestBuilder);

        InputRequiredException.Builder exceptionBuilder =
            Mockito.mock(InputRequiredException.Builder.class, Mockito.RETURNS_SELF);
        when(exceptionBuilder.build())
            .thenThrow(InputRequiredException.builder().setRequestState("kafka").build());
        when(sampling.inputRequired()).thenReturn(exceptionBuilder);

        // WHEN: diagnosing with no explicit namespace and a missing cluster.
        assertThrows(InputRequiredException.class,
            () -> topicDiagnosticService.diagnose(null, "my-topic", null, "topic not ready",
                sampling, elicitation, null, null));

        // THEN: the analysis request carries the RESOLVED topic namespace, not the (null) cluster
        // namespace — so the next round resolves the topic directly via requestState.
        verify(exceptionBuilder).setRequestState(eq("kafka"));

        // AND: the namespace was resolved from the prior response, never re-elicited.
        verify(elicitation, never()).inputRequired();
    }

    private Elicitation mockStatelessElicitationWithNamespace(final String namespace) {
        Elicitation elicitation = Mockito.mock(Elicitation.class);
        when(elicitation.isServerInitiatedRequestSupported()).thenReturn(false);
        when(elicitation.isFormModeSupported()).thenReturn(true);

        InputResponses responses = Mockito.mock(InputResponses.class);
        when(responses.has("namespace")).thenReturn(true);
        when(elicitation.inputResponses()).thenReturn(responses);

        ElicitationResponse response = Mockito.mock(ElicitationResponse.class);
        when(responses.getElicitationResponse("namespace")).thenReturn(response);
        when(response.actionAccepted()).thenReturn(true);

        ElicitationResponse.Content content = Mockito.mock(ElicitationResponse.Content.class);
        when(response.content()).thenReturn(content);
        when(content.getString("namespace")).thenReturn(namespace);

        return elicitation;
    }
}
