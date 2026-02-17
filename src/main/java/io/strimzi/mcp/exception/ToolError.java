/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.mcp.exception;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Generic error result for tool operations.
 * Used when an error occurs that doesn't fit a specific result type.
 *
 * @param error   the error message
 * @param details additional error details
 */
public record ToolError(
    @JsonProperty("error") String error,
    @JsonProperty("details") String details
) {
    /**
     * Create a ToolError with only an error message.
     *
     * @param error the error message
     * @return a new ToolError instance
     */
    public static ToolError of(String error) {
        return new ToolError(error, null);
    }

    /**
     * Create a ToolError from an error message and exception.
     *
     * @param error the error message
     * @param e     the exception that caused the error
     * @return a new ToolError instance
     */
    public static ToolError of(String error, Exception e) {
        return new ToolError(error, e != null ? e.getMessage() : null);
    }

    /**
     * Create a ToolError for a validation failure.
     *
     * @param message the validation error message
     * @return a new ToolError instance
     */
    public static ToolError validation(String message) {
        return new ToolError("Validation error: " + message, null);
    }
}
