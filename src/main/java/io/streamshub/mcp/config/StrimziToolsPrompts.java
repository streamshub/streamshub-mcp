/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.config;

/**
 * Shared parameter descriptions for MCP tools.
 */
public final class StrimziToolsPrompts {

    /**
     * Namespace parameter description.
     */
    public static final String NS_DESC =
        "Kubernetes namespace."
            + " Omit to search all namespaces.";

    /**
     * Cluster name parameter description.
     */
    public static final String CLUSTER_DESC =
        "Kafka cluster name"
            + " (e.g., 'my-cluster').";

    /**
     * Sections parameter description.
     */
    public static final String SECTIONS_DESC =
        "Comma-separated detail sections:"
            + " node, labels, env, resources,"
            + " volumes, conditions, full."
            + " Omit for summary only.";

    private StrimziToolsPrompts() {
    }
}
