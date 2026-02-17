/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.mcp.exception;

/**
 * Exception thrown when LLM functionality is requested but not available.
 * This can happen when:
 * - ENABLE_LLM is set to false (default)
 * - LLM provider is not properly configured
 * - LLM provider is unreachable
 */
public class LlmNotAvailableException extends RuntimeException {

    /**
     * Create a new LlmNotAvailableException with a message.
     *
     * @param message the detail message
     */
    public LlmNotAvailableException(String message) {
        super(message);
    }

    /**
     * Create a new LlmNotAvailableException with a message and cause.
     *
     * @param message the detail message
     * @param cause   the underlying cause
     */
    public LlmNotAvailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
