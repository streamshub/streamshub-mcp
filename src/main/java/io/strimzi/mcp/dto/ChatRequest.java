/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.mcp.dto;

/**
 * Simple chat request model.
 *
 * @param message the chat message content
 */
public record ChatRequest(String message) {

    /**
     * Validates that the message is not null or empty.
     */
    public ChatRequest {
        if (message == null || message.trim().isEmpty()) {
            throw new IllegalArgumentException("Message cannot be empty");
        }
    }
}
