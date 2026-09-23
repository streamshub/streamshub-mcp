/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.observability;

/**
 * Classification of a tool-call outcome for metrics, modelled on the two MCP
 * error channels (JSON-RPC protocol errors vs tool-execution errors) plus the
 * OpenTelemetry {@code error.type} convention.
 *
 * <p>Each outcome maps to a {@code status} tag (kept for backward compatibility
 * with existing dashboards) and an {@code error_type} tag (the finer-grained
 * classification).</p>
 */
public enum ToolCallOutcome {

    /**
     * The tool completed successfully and returned a result.
     */
    SUCCESS("success", "none"),

    /**
     * The tool failed with a JSON-RPC protocol error ({@code McpException}):
     * not-found, invalid-params, or ambiguous. These are request-level (client)
     * errors carried in the JSON-RPC {@code error} channel.
     */
    PROTOCOL_ERROR("error", "protocol_error"),

    /**
     * The tool failed with a tool-execution error ({@code ToolCallException} or a
     * wrapped infrastructure/RBAC failure) surfaced to the LLM as an
     * {@code isError} result.
     */
    TOOL_ERROR("error", "tool_error"),

    /**
     * The call was rejected by a rate-limit guardrail before the tool executed,
     * so no duration is recorded (analogous to an HTTP 429).
     */
    RATE_LIMITED("error", "rate_limited");

    private final String status;
    private final String errorType;

    ToolCallOutcome(final String status, final String errorType) {
        this.status = status;
        this.errorType = errorType;
    }

    /**
     * Returns the value for the {@code status} metric tag.
     *
     * @return {@code "success"} or {@code "error"}
     */
    String status() {
        return status;
    }

    /**
     * Returns the value for the {@code error_type} metric tag.
     *
     * @return the error-type classification (or {@code "none"} on success)
     */
    String errorType() {
        return errorType;
    }
}
