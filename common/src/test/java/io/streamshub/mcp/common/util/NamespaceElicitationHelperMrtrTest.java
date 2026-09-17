/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.util;

import io.quarkiverse.mcp.server.Elicitation;
import io.quarkiverse.mcp.server.ElicitationRequest;
import io.quarkiverse.mcp.server.ElicitationResponse;
import io.quarkiverse.mcp.server.InputRequiredException;
import io.quarkiverse.mcp.server.InputResponses;
import io.quarkiverse.mcp.server.JsonRpcErrorCodes;
import io.quarkiverse.mcp.server.McpException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for MRTR-aware namespace elicitation helpers.
 */
class NamespaceElicitationHelperMrtrTest {

    private static final List<String> CANDIDATE_NAMESPACES = List.of("kafka-a", "kafka-b", "kafka-c");
    private static final String TEST_CONTEXT = "diagnosed";
    private static final String ELICITATION_KEY = "namespace";

    NamespaceElicitationHelperMrtrTest() {
    }

    @Test
    void testElicitNamespaceMrtrStatelessNoResponseThrowsInputRequired() {
        // GIVEN: ambiguous error with candidates
        McpException error = McpErrors.ambiguous("Kafka", "my-cluster", CANDIDATE_NAMESPACES);

        // AND: stateless elicitation (no prior response)
        Elicitation elicitation = mock(Elicitation.class);
        when(elicitation.isServerInitiatedRequestSupported()).thenReturn(false);
        when(elicitation.isFormModeSupported()).thenReturn(true);
        InputResponses responses = mock(InputResponses.class);
        when(elicitation.inputResponses()).thenReturn(responses);
        when(responses.has(ELICITATION_KEY)).thenReturn(false);

        // Mock the request builder chain
        ElicitationRequest.Builder requestBuilder = mock(ElicitationRequest.Builder.class);
        when(elicitation.requestBuilder()).thenReturn(requestBuilder);
        when(requestBuilder.setMessage(anyString())).thenReturn(requestBuilder);
        when(requestBuilder.addSchemaProperty(anyString(), any())).thenReturn(requestBuilder);
        ElicitationRequest request = mock(ElicitationRequest.class);
        when(requestBuilder.build()).thenReturn(request);

        InputRequiredException.Builder builder = mock(InputRequiredException.Builder.class);
        when(elicitation.inputRequired()).thenReturn(builder);
        when(builder.addElicitationRequest(anyString(), any())).thenReturn(builder);
        InputRequiredException exception = mock(InputRequiredException.class);
        when(builder.build()).thenReturn(exception);

        // WHEN/THEN: expect InputRequiredException
        InputRequiredException thrown = assertThrows(InputRequiredException.class,
            () -> NamespaceElicitationHelper.elicitNamespaceMrtr(
                error, elicitation, TEST_CONTEXT, ELICITATION_KEY));

        assertSame(exception, thrown);
        // Verify requestState is NOT set at elicitation time (namespace not yet resolved)
        verify(builder, never()).setRequestState(anyString());
    }

    @Test
    void testElicitNamespaceMrtrStatelessWithAcceptedResponseReturnsSelection() {
        // GIVEN: ambiguous error with candidates
        McpException error = McpErrors.ambiguous("Kafka", "my-cluster", CANDIDATE_NAMESPACES);

        // AND: stateless elicitation with a prior response
        Elicitation elicitation = mock(Elicitation.class);
        when(elicitation.isServerInitiatedRequestSupported()).thenReturn(false);
        when(elicitation.isFormModeSupported()).thenReturn(true);
        InputResponses responses = mock(InputResponses.class);
        when(elicitation.inputResponses()).thenReturn(responses);
        when(responses.has(ELICITATION_KEY)).thenReturn(true);

        ElicitationResponse response = mock(ElicitationResponse.class);
        when(responses.getElicitationResponse(ELICITATION_KEY)).thenReturn(response);
        when(response.actionAccepted()).thenReturn(true);

        ElicitationResponse.Content content = mock(ElicitationResponse.Content.class);
        when(response.content()).thenReturn(content);
        when(content.getString("namespace")).thenReturn("kafka-b");

        // WHEN: elicit namespace
        String result = NamespaceElicitationHelper.elicitNamespaceMrtr(
            error, elicitation, TEST_CONTEXT, ELICITATION_KEY);

        // THEN: returns the selected namespace
        assertEquals("kafka-b", result);
    }

    @Test
    void testElicitNamespaceMrtrStatelessWithDeclinedResponseThrowsOriginalError() {
        // GIVEN: ambiguous error with candidates
        McpException error = McpErrors.ambiguous("Kafka", "my-cluster", CANDIDATE_NAMESPACES);

        // AND: stateless elicitation with a declined response
        Elicitation elicitation = mock(Elicitation.class);
        when(elicitation.isServerInitiatedRequestSupported()).thenReturn(false);
        when(elicitation.isFormModeSupported()).thenReturn(true);
        InputResponses responses = mock(InputResponses.class);
        when(elicitation.inputResponses()).thenReturn(responses);
        when(responses.has(ELICITATION_KEY)).thenReturn(true);

        ElicitationResponse response = mock(ElicitationResponse.class);
        when(responses.getElicitationResponse(ELICITATION_KEY)).thenReturn(response);
        when(response.actionAccepted()).thenReturn(false);

        // WHEN/THEN: throws original error
        McpException thrown = assertThrows(McpException.class,
            () -> NamespaceElicitationHelper.elicitNamespaceMrtr(
                error, elicitation, TEST_CONTEXT, ELICITATION_KEY));

        assertSame(error, thrown);
    }

    @Test
    void testElicitNamespaceMrtrNoCandidatesThrowsOriginalError() {
        // GIVEN: error with no candidates
        McpException error = new McpException("Some error", JsonRpcErrorCodes.INTERNAL_ERROR);
        Elicitation elicitation = mock(Elicitation.class);

        // WHEN/THEN: throws original error without attempting elicitation
        McpException thrown = assertThrows(McpException.class,
            () -> NamespaceElicitationHelper.elicitNamespaceMrtr(
                error, elicitation, TEST_CONTEXT, ELICITATION_KEY));

        assertSame(error, thrown);
    }

    @Test
    void testElicitNamespaceMrtrEmptyCandidatesThrowsOriginalError() {
        // GIVEN: error with empty candidate list
        McpException error = McpErrors.ambiguous("Kafka", "my-cluster", List.of());
        Elicitation elicitation = mock(Elicitation.class);

        // WHEN/THEN: throws original error without attempting elicitation
        McpException thrown = assertThrows(McpException.class,
            () -> NamespaceElicitationHelper.elicitNamespaceMrtr(
                error, elicitation, TEST_CONTEXT, ELICITATION_KEY));

        assertSame(error, thrown);
    }

    @Test
    void testElicitNamespaceMrtrStatelessFormModeUnsupportedThrowsOriginalError() {
        // GIVEN: ambiguous error, stateless client that did NOT declare form-mode elicitation
        McpException error = McpErrors.ambiguous("Kafka", "my-cluster", CANDIDATE_NAMESPACES);
        Elicitation elicitation = mock(Elicitation.class);
        when(elicitation.isServerInitiatedRequestSupported()).thenReturn(false);
        when(elicitation.isFormModeSupported()).thenReturn(false);

        // WHEN/THEN: falls back to the structured error, never requests input
        McpException thrown = assertThrows(McpException.class,
            () -> NamespaceElicitationHelper.elicitNamespaceMrtr(
                error, elicitation, TEST_CONTEXT, ELICITATION_KEY));

        assertSame(error, thrown);
        verify(elicitation, never()).inputRequired();
    }

    @Test
    void testElicitNamespaceMrtrPromptNamesResourceKind() {
        // GIVEN: ambiguous KafkaTopic in a stateless client with form mode, no prior response
        McpException error = McpErrors.ambiguous("KafkaTopic", "my-topic", CANDIDATE_NAMESPACES);
        Elicitation elicitation = mock(Elicitation.class);
        when(elicitation.isServerInitiatedRequestSupported()).thenReturn(false);
        when(elicitation.isFormModeSupported()).thenReturn(true);
        InputResponses responses = mock(InputResponses.class);
        when(elicitation.inputResponses()).thenReturn(responses);
        when(responses.has(ELICITATION_KEY)).thenReturn(false);

        ElicitationRequest.Builder requestBuilder = mock(ElicitationRequest.Builder.class);
        when(elicitation.requestBuilder()).thenReturn(requestBuilder);
        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        when(requestBuilder.setMessage(message.capture())).thenReturn(requestBuilder);
        when(requestBuilder.addSchemaProperty(anyString(), any())).thenReturn(requestBuilder);
        when(requestBuilder.build()).thenReturn(mock(ElicitationRequest.class));

        InputRequiredException.Builder builder = mock(InputRequiredException.Builder.class);
        when(elicitation.inputRequired()).thenReturn(builder);
        when(builder.addElicitationRequest(anyString(), any())).thenReturn(builder);
        when(builder.build()).thenReturn(mock(InputRequiredException.class));

        assertThrows(InputRequiredException.class,
            () -> NamespaceElicitationHelper.elicitNamespaceMrtr(
                error, elicitation, TEST_CONTEXT, ELICITATION_KEY));

        assertTrue(message.getValue().contains("KafkaTopic"),
            "prompt should name the resource kind, got: " + message.getValue());
    }
}
