package io.strimzi.mcp.dto;

import java.time.Instant;

/**
 * Simple chat response model.
 */
public record ChatResponse(
    String response,
    String provider,
    Instant timestamp
) {

    public static ChatResponse of(String response, String provider) {
        return new ChatResponse(response, provider, Instant.now());
    }
}