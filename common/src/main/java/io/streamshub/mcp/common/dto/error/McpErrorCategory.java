/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.dto.error;

/**
 * Machine-readable category for a structured MCP error, carried in {@link McpErrorData}.
 */
public enum McpErrorCategory {

    /** The requested resource does not exist. */
    RESOURCE_NOT_FOUND,

    /** A tool argument was missing, malformed, or otherwise invalid. */
    INVALID_PARAMS,

    /** The request matched multiple resources and needs disambiguation. */
    AMBIGUOUS
}
