package io.strimzi.mcp.exception;

/**
 * Exception thrown when LLM functionality is requested but not available.
 * This can happen when:
 * - ENABLE_LLM is set to false (default)
 * - LLM provider is not properly configured
 * - LLM provider is unreachable
 */
public class LlmNotAvailableException extends RuntimeException {

    public LlmNotAvailableException(String message) {
        super(message);
    }

    public LlmNotAvailableException(String message, Throwable cause) {
        super(message, cause);
    }
}