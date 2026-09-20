/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.testutil;

import io.quarkiverse.mcp.server.InputRequiredException;
import io.quarkiverse.mcp.server.InputResponses;
import io.quarkiverse.mcp.server.Sampling;
import io.quarkiverse.mcp.server.SamplingRequest;
import io.quarkiverse.mcp.server.SamplingResponse;
import io.quarkiverse.mcp.server.TextContent;
import org.mockito.Mockito;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Shared test utility for creating mock MRTR (stateless) Sampling instances.
 *
 * <p>This helper builds a mocked Sampling object configured for stateless clients
 * (isServerInitiatedRequestSupported=false) with optional analysis response data.</p>
 */
public final class MrtrTestHelper {

    private MrtrTestHelper() {
    }

    /**
     * Create a mock stateless Sampling instance for testing MRTR flows.
     *
     * <p>When {@code hasResponse} is true, the mock returns the provided
     * {@code analysisText} as a SamplingResponse for the "analysis" key.
     * When false, it builds the InputRequiredException flow with request builder
     * and inputRequired mocks.</p>
     *
     * @param hasResponse  whether the "analysis" response is present
     * @param analysisText the text content to return when response is present (may be null if hasResponse is false)
     * @return a mocked Sampling instance configured for stateless MRTR flow
     */
    public static Sampling mockStatelessSampling(final boolean hasResponse, final String analysisText) {
        Sampling sampling = Mockito.mock(Sampling.class);
        when(sampling.isServerInitiatedRequestSupported()).thenReturn(false);
        when(sampling.isSupported()).thenReturn(true);

        InputResponses responses = Mockito.mock(InputResponses.class);
        when(responses.has("analysis")).thenReturn(hasResponse);
        when(sampling.inputResponses()).thenReturn(responses);

        if (hasResponse) {
            TextContent textContent = new TextContent(analysisText);
            SamplingResponse samplingResponse = Mockito.mock(SamplingResponse.class);
            when(samplingResponse.content()).thenReturn(textContent);
            when(responses.getSamplingResponse(eq("analysis"))).thenReturn(samplingResponse);
        } else {
            SamplingRequest.Builder requestBuilder =
                Mockito.mock(SamplingRequest.Builder.class, Mockito.RETURNS_SELF);
            SamplingRequest builtRequest = Mockito.mock(SamplingRequest.class);
            when(requestBuilder.build()).thenReturn(builtRequest);
            when(sampling.requestBuilder()).thenReturn(requestBuilder);

            // Return a REAL InputRequiredException builder so the production setRequestState(...) and
            // build() calls execute for real. A mocked builder silently swallows a null requestState,
            // masking the NPE that setRequestState(null) actually throws for tools that carry no
            // namespace to preserve (e.g. compare_kafka_clusters).
            when(sampling.inputRequired()).thenReturn(InputRequiredException.builder());
        }

        return sampling;
    }
}
