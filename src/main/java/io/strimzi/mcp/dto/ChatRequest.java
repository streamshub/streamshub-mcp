package io.strimzi.mcp.dto;

/**
 * Simple chat request model.
 */
public record ChatRequest(String message) {

    public ChatRequest {
        if (message == null || message.trim().isEmpty()) {
            throw new IllegalArgumentException("Message cannot be empty");
        }
    }
}