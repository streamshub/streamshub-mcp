/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.mcp.dto;

import java.time.Instant;

/**
 * Simple chat response model.
 *
 * @param response the chat response text
 * @param provider the LLM provider used
 * @param timestamp the time this response was generated
 */
public record ChatResponse(
    String response,
    String provider,
    Instant timestamp
) {

    /**
     * Create a ChatResponse with the current timestamp.
     *
     * @param response the chat response text
     * @param provider the LLM provider used
     * @return a new ChatResponse instance
     */
    public static ChatResponse of(String response, String provider) {
        return new ChatResponse(response, provider, Instant.now());
    }
}
