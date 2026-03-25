/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.config;

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
     * Log filter parameter description.
     */
    public static final String LOG_FILTER_DESC =
        "Filter log lines: 'errors' for ERROR/EXCEPTION only,"
            + " 'warnings' for ERROR/EXCEPTION/WARN,"
            + " or a regex pattern. Omit for all lines.";

    /**
     * Since minutes parameter description.
     */
    public static final String SINCE_MINUTES_DESC =
        "Only return logs newer than this many minutes."
            + " Omit for no time restriction.";

    /**
     * Tail lines parameter description.
     */
    public static final String TAIL_LINES_DESC =
        "Number of log lines to retrieve per pod."
            + " Defaults to server-configured value (typically 200).";

    /**
     * Previous container logs parameter description.
     */
    public static final String PREVIOUS_DESC =
        "If true, retrieve logs from the previous"
            + " container instance (crashed/restarted pods).";

    /**
     * Keywords parameter description.
     */
    public static final String KEYWORDS_DESC =
        "List of keywords to filter log lines (e.g., ['ERROR', 'OOM', 'Exception'])."
            + " Returns only lines containing at least one keyword."
            + " Case-insensitive. Omit for no keyword filtering.";

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
