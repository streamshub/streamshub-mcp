/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkiverse.mcp.server.InputRequiredException;
import io.quarkiverse.mcp.server.Sampling;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Base class for diagnostic service implementations.
 *
 * <p>Provides shared configuration fields and utility methods for MCP Sampling
 * (triage and analysis). Concrete subclasses keep their domain-specific
 * {@code diagnose()} workflow and gather methods.</p>
 */
public abstract class BaseDiagnosticService {

    @Inject
    protected ObjectMapper objectMapper;

    @ConfigProperty(name = "mcp.sampling.triage-max-tokens", defaultValue = "200")
    protected int triageMaxTokens;

    @ConfigProperty(name = "mcp.sampling.analysis-max-tokens", defaultValue = "1500")
    protected int analysisMaxTokens;

    @ConfigProperty(name = "mcp.log.tail-lines", defaultValue = "200")
    protected int defaultTailLines;

    protected BaseDiagnosticService() {
    }

    protected abstract Logger getLogger();

    /**
     * Send a Sampling request and return the extracted text response.
     *
     * <p>Returns {@code null} if Sampling is unavailable, unsupported, or fails.</p>
     *
     * @param sampling     MCP Sampling interface (may be null or unsupported)
     * @param systemPrompt the system prompt for the LLM
     * @param data         the data map to serialize as JSON input
     * @param maxTokens    maximum tokens for the response
     * @return the extracted text, or null on failure
     */
    protected String performSampling(Sampling sampling, String systemPrompt,
                                      Map<String, Object> data, int maxTokens) {
        return performSampling(sampling, systemPrompt, data, maxTokens, null);
    }

    /**
     * Send a Sampling request with cancellation support.
     *
     * <p>Checks the cancellation flag before and after the async sampling call.
     * Returns {@code null} if Sampling is unavailable, unsupported, or fails,
     * or if cancelled before the call completes.</p>
     *
     * @param sampling     MCP Sampling interface (may be null or unsupported)
     * @param systemPrompt the system prompt for the LLM
     * @param data         the data map to serialize as JSON input
     * @param maxTokens    maximum tokens for the response
     * @param cancelled    optional flag set by push-based cancellation callback (may be null)
     * @return the extracted text, or null on failure or cancellation
     */
    protected String performSampling(Sampling sampling, String systemPrompt,
                                      Map<String, Object> data, int maxTokens,
                                      AtomicBoolean cancelled) {
        return DiagnosticHelper.sendSampling(sampling, objectMapper, systemPrompt, data,
            maxTokens, cancelled, getLogger());
    }

    /**
     * Perform triage Sampling and return the parsed JSON response as a map.
     *
     * <p>Returns {@code null} if Sampling is unavailable or the response cannot
     * be parsed as JSON. Callers should fall back to investigating all areas
     * when null is returned.</p>
     *
     * @param sampling      MCP Sampling interface
     * @param systemPrompt  the triage system prompt
     * @param phase1Summary the Phase 1 summary data map
     * @return the parsed triage response, or null on failure
     */
    protected Map<String, Object> performTriage(Sampling sampling, String systemPrompt,
                                                 Map<String, Object> phase1Summary) {
        return performTriage(sampling, systemPrompt, phase1Summary, null);
    }

    /**
     * Perform triage Sampling with cancellation support.
     *
     * <p>Checks the cancellation flag before and after the sampling call.
     * Returns {@code null} if Sampling is unavailable, the response cannot
     * be parsed as JSON, or the operation was cancelled. Callers should fall
     * back to investigating all areas when null is returned.</p>
     *
     * @param sampling      MCP Sampling interface
     * @param systemPrompt  the triage system prompt
     * @param phase1Summary the Phase 1 summary data map
     * @param cancelled     optional flag set by push-based cancellation callback (may be null)
     * @return the parsed triage response, or null on failure or cancellation
     */
    protected Map<String, Object> performTriage(Sampling sampling, String systemPrompt,
                                                 Map<String, Object> phase1Summary,
                                                 AtomicBoolean cancelled) {
        if (sampling != null && !sampling.isServerInitiatedRequestSupported()) {
            // Triage is a stateful-only (SSE) optimization. Stateless clients cannot service a
            // server-initiated sampling call, so skip it here and let analysis run over all
            // gathered data via the MRTR path. Note: isSupported() is capability-based and is
            // true even for stateless clients, so the skip must key off the transport check.
            return null;
        }
        String text = performSampling(sampling, systemPrompt, phase1Summary, triageMaxTokens, cancelled);
        if (text == null) {
            return null;
        }
        try {
            return objectMapper.readValue(text, DiagnosticHelper.MAP_TYPE_REF);
        } catch (Exception e) {
            getLogger().debugf("Could not parse triage response: %s", e.getMessage());
            return null;
        }
    }

    /**
     * Perform analysis Sampling, adapting to the client's transport (MRTR-aware).
     *
     * <p>Delegates to {@link DiagnosticHelper#analysisSamplingMrtr}. For stateful clients it sends
     * a server-initiated request and awaits the response, short-circuiting (returning {@code null})
     * when {@code cancelled} is set before or after the call. For stateless clients (MRTR) it reads
     * a prior sampling response keyed by {@code key} if present, otherwise throws
     * {@link InputRequiredException} requesting it — that exception must propagate to the MCP
     * framework and must not be caught. Returns {@code null} when sampling is null/unsupported,
     * serialization fails, or the operation was cancelled.</p>
     *
     * @param sampling     MCP Sampling interface (may be null)
     * @param systemPrompt the analysis system prompt
     * @param fullData     the full gathered data map
     * @param key          the MRTR request key (e.g. "analysis")
     * @param requestState the request state to preserve across round-trips (e.g. namespace)
     * @param cancelled    optional flag set by push-based cancellation callback (may be null)
     * @return the analysis text, or null when sampling is null/unsupported or cancelled
     */
    protected String performAnalysisMrtr(final Sampling sampling, final String systemPrompt,
                                         final Map<String, Object> fullData, final String key,
                                         final String requestState, final AtomicBoolean cancelled) {
        return DiagnosticHelper.analysisSamplingMrtr(sampling, objectMapper, systemPrompt,
            fullData, analysisMaxTokens, key, requestState, cancelled, getLogger());
    }
}
