package io.strimzi.mcp.tool;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Generic error result for tool operations.
 * Used when an error occurs that doesn't fit a specific result type.
 */
public record ToolError(
    @JsonProperty("error") String error,
    @JsonProperty("details") String details
) {
    public static ToolError of(String error) {
        return new ToolError(error, null);
    }

    public static ToolError of(String error, Exception e) {
        return new ToolError(error, e.getMessage());
    }

    public static ToolError validation(String message) {
        return new ToolError("Validation error: " + message, null);
    }
}
