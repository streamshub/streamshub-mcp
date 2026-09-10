/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.util;

import io.quarkiverse.mcp.server.JsonRpcErrorCodes;
import io.quarkiverse.mcp.server.McpException;
import io.streamshub.mcp.common.dto.error.McpErrorCategory;
import io.streamshub.mcp.common.dto.error.McpErrorData;

import java.util.List;

/**
 * Factories for {@link McpException}s carrying structured {@code error.data}
 * (an {@link io.streamshub.mcp.common.dto.error.McpErrorData}) so LLM clients receive
 * machine-readable error context in addition to the human-readable message.
 */
public final class McpErrors {

    private McpErrors() {
    }

    /**
     * Build a resource-not-found error.
     *
     * @param resourceKind the resource kind (e.g. {@code Kafka})
     * @param resourceName the resource name
     * @param namespace    the namespace searched, or {@code null} if all namespaces were searched
     * @return an McpException with the RESOURCE_NOT_FOUND code and structured data
     */
    public static McpException notFound(final String resourceKind, final String resourceName,
                                        final String namespace) {
        String location = namespace != null ? "namespace " + namespace : "any namespace";
        String named = resourceName != null ? " '" + resourceName + "'" : "";
        String message = resourceKind + named + " not found in " + location;
        McpErrorData data = new McpErrorData(
            McpErrorCategory.RESOURCE_NOT_FOUND, resourceKind, resourceName, namespace, null, null);
        return new McpException(message, JsonRpcErrorCodes.RESOURCE_NOT_FOUND, data);
    }

    /**
     * Build an ambiguity error for a resource found in multiple namespaces.
     *
     * @param resourceKind the resource kind
     * @param resourceName the resource name
     * @param candidates   the candidate namespaces
     * @return an McpException with the INVALID_PARAMS code and structured data
     */
    public static McpException ambiguous(final String resourceKind, final String resourceName,
                                         final List<String> candidates) {
        String named = resourceName != null ? " resources named '" + resourceName + "'" : " resources";
        String message = "Multiple " + resourceKind + named
            + " found in namespaces: " + String.join(", ", candidates) + ". Please specify namespace.";
        McpErrorData data = new McpErrorData(
            McpErrorCategory.AMBIGUOUS, resourceKind, resourceName, null, List.copyOf(candidates), null);
        return new McpException(message, JsonRpcErrorCodes.INVALID_PARAMS, data);
    }

    /**
     * Build an invalid-parameters error.
     *
     * @param message the human-readable message
     * @return an McpException with the INVALID_PARAMS code and structured data
     */
    public static McpException invalidParams(final String message) {
        McpErrorData data = new McpErrorData(
            McpErrorCategory.INVALID_PARAMS, null, null, null, null, null);
        return new McpException(message, JsonRpcErrorCodes.INVALID_PARAMS, data);
    }

    /**
     * Build a forbidden/security error.
     *
     * @param message the human-readable message
     * @return an McpException with the SECURITY_ERROR code and structured data
     */
    public static McpException forbidden(final String message) {
        McpErrorData data = new McpErrorData(
            McpErrorCategory.SECURITY, null, null, null, null, null);
        return new McpException(message, JsonRpcErrorCodes.SECURITY_ERROR, data);
    }
}
