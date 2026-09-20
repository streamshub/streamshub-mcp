/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkiverse.mcp.server.InputRequiredException;
import io.quarkiverse.mcp.server.InputResponses;
import io.quarkiverse.mcp.server.Sampling;
import io.quarkiverse.mcp.server.SamplingRequest;
import io.quarkiverse.mcp.server.SamplingResponse;
import io.quarkiverse.mcp.server.TextContent;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BaseDiagnosticServiceMrtrTest {

    BaseDiagnosticServiceMrtrTest() {
    }

    private static final class TestService extends BaseDiagnosticService {
        TestService() {
        }

        @Override
        protected Logger getLogger() {
            return Logger.getLogger(TestService.class);
        }
    }

    @Test
    void testPerformSamplingRethrowsInputRequiredException() {
        TestService service = new TestService();
        service.objectMapper = new ObjectMapper();
        Sampling sampling = mock(Sampling.class);
        when(sampling.isSupported()).thenReturn(true);
        when(sampling.requestBuilder()).thenThrow(
            InputRequiredException.builder().setRequestState("x").build());

        assertThrows(InputRequiredException.class,
            () -> service.performSampling(sampling, "sys", Map.of("k", "v"), 100));
    }

    @Test
    void testPerformTriageStatelessReturnsNullWithoutServerInitiatedCall() {
        TestService service = new TestService();
        service.objectMapper = new ObjectMapper();
        service.triageMaxTokens = 200;

        Sampling sampling = mock(Sampling.class);
        when(sampling.isServerInitiatedRequestSupported()).thenReturn(false);

        Map<String, Object> result = service.performTriage(sampling, "sys", Map.of("k", "v"));

        assertNull(result);
        // Triage is stateful-only: a stateless client never attempts a server-initiated call.
        verify(sampling, never()).requestBuilder();
    }

    @Test
    void testPerformAnalysisMrtrStatelessThrowsWhenNoResponse() {
        TestService service = new TestService();
        service.objectMapper = new ObjectMapper();
        service.analysisMaxTokens = 1500;

        Sampling sampling = mock(Sampling.class);
        when(sampling.isServerInitiatedRequestSupported()).thenReturn(false);
        when(sampling.isSupported()).thenReturn(true);

        InputResponses responses = mock(InputResponses.class);
        when(responses.has("analysis")).thenReturn(false);
        when(sampling.inputResponses()).thenReturn(responses);

        SamplingRequest.Builder requestBuilder =
            mock(SamplingRequest.Builder.class, org.mockito.Mockito.RETURNS_SELF);
        SamplingRequest builtRequest = mock(SamplingRequest.class);
        when(requestBuilder.build()).thenReturn(builtRequest);
        when(sampling.requestBuilder()).thenReturn(requestBuilder);

        InputRequiredException.Builder exceptionBuilder =
            mock(InputRequiredException.Builder.class, org.mockito.Mockito.RETURNS_SELF);
        when(exceptionBuilder.build())
            .thenThrow(InputRequiredException.builder().setRequestState("test").build());
        when(sampling.inputRequired()).thenReturn(exceptionBuilder);

        assertThrows(InputRequiredException.class,
            () -> service.performAnalysisMrtr(sampling, "sys", Map.of("k", "v"), "analysis", "ns1", null));

        verify(exceptionBuilder).setRequestState(eq("ns1"));
    }

    @Test
    void testPerformAnalysisMrtrStatelessNullRequestStateThrowsInputRequiredNotNpe() {
        TestService service = new TestService();
        service.objectMapper = new ObjectMapper();
        service.analysisMaxTokens = 1500;

        Sampling sampling = mock(Sampling.class);
        when(sampling.isServerInitiatedRequestSupported()).thenReturn(false);
        when(sampling.isSupported()).thenReturn(true);

        InputResponses responses = mock(InputResponses.class);
        when(responses.has("analysis")).thenReturn(false);
        when(sampling.inputResponses()).thenReturn(responses);

        SamplingRequest.Builder requestBuilder =
            mock(SamplingRequest.Builder.class, org.mockito.Mockito.RETURNS_SELF);
        when(requestBuilder.build()).thenReturn(mock(SamplingRequest.class));
        when(sampling.requestBuilder()).thenReturn(requestBuilder);

        // Real builder so setRequestState(null) runs for real. Objects.requireNonNull would make it
        // NPE without the production null guard; a null request state is legal for the built
        // exception (used by compare_kafka_clusters and auto-detected namespaces).
        when(sampling.inputRequired()).thenReturn(InputRequiredException.builder());

        InputRequiredException thrown = assertThrows(InputRequiredException.class,
            () -> service.performAnalysisMrtr(sampling, "sys", Map.of("k", "v"), "analysis", null, null));
        assertNull(thrown.requestState());
    }

    @Test
    void testPerformAnalysisMrtrStatelessReturnsTextWhenResponsePresent() {
        TestService service = new TestService();
        service.objectMapper = new ObjectMapper();

        Sampling sampling = mock(Sampling.class);
        when(sampling.isServerInitiatedRequestSupported()).thenReturn(false);
        when(sampling.isSupported()).thenReturn(true);

        InputResponses responses = mock(InputResponses.class);
        when(responses.has("analysis")).thenReturn(true);

        TextContent textContent = new TextContent("Analysis result text");
        SamplingResponse samplingResponse = mock(SamplingResponse.class);
        when(samplingResponse.content()).thenReturn(textContent);
        when(responses.getSamplingResponse("analysis")).thenReturn(samplingResponse);
        when(sampling.inputResponses()).thenReturn(responses);

        String result = service.performAnalysisMrtr(sampling, "sys", Map.of("k", "v"), "analysis", "ns1", null);
        assertEquals("Analysis result text", result);
        verify(responses).getSamplingResponse(eq("analysis"));
    }

    @Test
    void testPerformAnalysisMrtrReturnsNullWhenSamplingIsNull() {
        TestService service = new TestService();
        service.objectMapper = new ObjectMapper();

        String result = service.performAnalysisMrtr(null, "sys", Map.of("k", "v"), "analysis", "ns1", null);
        assertNull(result);
    }

    @Test
    void testPerformAnalysisMrtrStatefulDelegatesToPerformAnalysis() {
        TestService service = new TestService();
        service.objectMapper = new ObjectMapper();
        service.analysisMaxTokens = 1500;

        Sampling sampling = mock(Sampling.class);
        when(sampling.isServerInitiatedRequestSupported()).thenReturn(true);
        when(sampling.isSupported()).thenReturn(true);

        SamplingRequest.Builder requestBuilder =
            mock(SamplingRequest.Builder.class, org.mockito.Mockito.RETURNS_SELF);
        SamplingRequest builtRequest = mock(SamplingRequest.class);
        when(requestBuilder.build()).thenReturn(builtRequest);
        when(sampling.requestBuilder()).thenReturn(requestBuilder);

        TextContent textContent = new TextContent("Stateful analysis result");
        SamplingResponse samplingResponse = mock(SamplingResponse.class);
        when(samplingResponse.content()).thenReturn(textContent);
        when(builtRequest.sendAndAwait()).thenReturn(samplingResponse);

        String result = service.performAnalysisMrtr(sampling, "sys", Map.of("k", "v"), "analysis", "ns1", null);
        assertEquals("Stateful analysis result", result);
        verify(sampling).requestBuilder();
    }

    @Test
    void testPerformAnalysisMrtrStatelessWithoutSamplingCapabilityReturnsNull() {
        TestService service = new TestService();
        service.objectMapper = new ObjectMapper();
        service.analysisMaxTokens = 1500;

        Sampling sampling = mock(Sampling.class);
        when(sampling.isServerInitiatedRequestSupported()).thenReturn(false);
        when(sampling.isSupported()).thenReturn(false);

        String result = service.performAnalysisMrtr(sampling, "sys", Map.of("k", "v"), "analysis", "ns1", null);

        assertNull(result);
        // Guard short-circuits before reading responses or building an InputRequiredException.
        verify(sampling, never()).inputResponses();
    }

    @Test
    void testPerformAnalysisMrtrStatelessReturnsNullWhenCancelledWithoutRequestingInput() {
        TestService service = new TestService();
        service.objectMapper = new ObjectMapper();
        service.analysisMaxTokens = 1500;

        Sampling sampling = mock(Sampling.class);
        when(sampling.isServerInitiatedRequestSupported()).thenReturn(false);
        when(sampling.isSupported()).thenReturn(true);

        AtomicBoolean cancelled = new AtomicBoolean(true);

        String result = service.performAnalysisMrtr(sampling, "sys", Map.of("k", "v"), "analysis", "ns1", cancelled);

        assertNull(result);
        // A cancelled diagnostic must not ask a stateless client for more LLM work.
        verify(sampling, never()).inputResponses();
        verify(sampling, never()).inputRequired();
    }

    @Test
    void testPerformAnalysisMrtrStatefulReturnsNullWhenCancelledBeforeCall() {
        TestService service = new TestService();
        service.objectMapper = new ObjectMapper();
        service.analysisMaxTokens = 1500;

        Sampling sampling = mock(Sampling.class);
        when(sampling.isServerInitiatedRequestSupported()).thenReturn(true);
        when(sampling.isSupported()).thenReturn(true);

        AtomicBoolean cancelled = new AtomicBoolean(true);

        String result = service.performAnalysisMrtr(sampling, "sys", Map.of("k", "v"), "analysis", "ns1", cancelled);

        assertNull(result);
        // Cancellation short-circuits before the long server-initiated sampling call.
        verify(sampling, never()).requestBuilder();
    }

    @Test
    void testPerformAnalysisMrtrStatefulReturnsNullWhenCancelledDuringCall() {
        TestService service = new TestService();
        service.objectMapper = new ObjectMapper();
        service.analysisMaxTokens = 1500;

        Sampling sampling = mock(Sampling.class);
        when(sampling.isServerInitiatedRequestSupported()).thenReturn(true);
        when(sampling.isSupported()).thenReturn(true);

        SamplingRequest.Builder requestBuilder =
            mock(SamplingRequest.Builder.class, org.mockito.Mockito.RETURNS_SELF);
        SamplingRequest builtRequest = mock(SamplingRequest.class);
        when(requestBuilder.build()).thenReturn(builtRequest);
        when(sampling.requestBuilder()).thenReturn(requestBuilder);

        AtomicBoolean cancelled = new AtomicBoolean(false);
        SamplingResponse samplingResponse = mock(SamplingResponse.class);
        when(builtRequest.sendAndAwait()).thenAnswer(invocation -> {
            cancelled.set(true);
            return samplingResponse;
        });

        String result = service.performAnalysisMrtr(sampling, "sys", Map.of("k", "v"), "analysis", "ns1", cancelled);

        assertNull(result);
        // Result is discarded when cancellation fires during the call.
        verify(samplingResponse, never()).content();
    }
}
