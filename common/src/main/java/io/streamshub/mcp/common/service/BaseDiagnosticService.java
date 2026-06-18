/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkiverse.mcp.server.Sampling;
import io.quarkiverse.mcp.server.SamplingMessage;
import io.quarkiverse.mcp.server.SamplingResponse;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.Map;

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
        if (sampling == null || !sampling.isSupported()) {
            return null;
        }
        try {
            String dataJson = objectMapper.writeValueAsString(data);
            SamplingResponse response = sampling.requestBuilder()
                .setSystemPrompt(systemPrompt)
                .addMessage(SamplingMessage.withUserRole(dataJson))
                .setMaxTokens(maxTokens)
                .build()
                .sendAndAwait();
            return DiagnosticHelper.extractSamplingText(response);
        } catch (Exception e) {
            getLogger().warnf("Sampling failed: %s: %s",
                e.getClass().getSimpleName(), e.getMessage());
            return null;
        }
    }

    /**
     * Perform analysis Sampling and return the LLM response text.
     *
     * <p>Convenience wrapper that uses {@link #analysisMaxTokens}.</p>
     *
     * @param sampling     MCP Sampling interface
     * @param systemPrompt the analysis system prompt
     * @param fullData     the full gathered data map
     * @return the analysis text, or null on failure
     */
    protected String performAnalysis(Sampling sampling, String systemPrompt,
                                      Map<String, Object> fullData) {
        return performSampling(sampling, systemPrompt, fullData, analysisMaxTokens);
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
        String text = performSampling(sampling, systemPrompt, phase1Summary, triageMaxTokens);
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
}
