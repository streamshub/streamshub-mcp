/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.dto.error;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Structured, machine-readable detail attached to an MCP error response as the JSON-RPC
 * {@code error.data} payload. All fields except {@code category} are optional.
 *
 * @param category     the machine-readable error category
 * @param resourceKind the Kubernetes resource kind involved, if any
 * @param resourceName the resource name involved, if any
 * @param namespace    the namespace involved, if any
 * @param candidates   candidate values for disambiguation (e.g. namespaces), if any
 * @param remediation  a short hint on how to resolve the error, if any
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record McpErrorData(
    @JsonProperty("category") McpErrorCategory category,
    @JsonProperty("resource_kind") String resourceKind,
    @JsonProperty("resource_name") String resourceName,
    @JsonProperty("namespace") String namespace,
    @JsonProperty("candidates") List<String> candidates,
    @JsonProperty("remediation") String remediation
) {
}
