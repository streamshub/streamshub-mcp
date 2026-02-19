/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.config;

/**
 * Shared system prompts and messages for Strimzi assistant tools.
 */
public final class StrimziToolsPrompts {
    /**
     * Auto-discovery usage guidance for MCP tool descriptions.
     * Common patterns that can be reused across different tool descriptions.
     */
    public static final String MCP_AUTO_DISCOVERY_GUIDANCE = """
        
        **CRITICAL AUTO-DISCOVERY USAGE:**
        - When user asks without mentioning namespace → use namespace=null for auto-discovery
        - When user specifies namespace explicitly → use that specific namespace
        - NEVER default to 'default', 'kafka', or any namespace unless explicitly mentioned
        - Let tool auto-discover across all namespaces when namespace not specified""";

    /**
     * Standard namespace parameter description for MCP tools.
     */
    public static final String MCP_NAMESPACE_PARAM_DESC =
        "Namespace parameter. Use null for auto-discovery across all namespaces (recommended), " +
            "or specify explicit namespace only if user mentions it";

    /**
     * Standard cluster name parameter description for MCP tools.
     */
    public static final String MCP_CLUSTER_NAME_PARAM_DESC =
        "Kafka cluster name (e.g., 'my-cluster') or null for all clusters. " +
            "Extract cluster name only if user specifically mentions it";

    /**
     * Standard sections parameter description for pod-describe tools.
     */
    public static final String MCP_SECTIONS_PARAM_DESC =
        "Comma-separated list of detail sections to include: " +
            "node, labels, env, resources, volumes, conditions, full. " +
            "Omit or leave empty for summary only (name, phase, ready, component, restarts, age).";

    private StrimziToolsPrompts() {
        // Utility class - no instantiation
    }
}