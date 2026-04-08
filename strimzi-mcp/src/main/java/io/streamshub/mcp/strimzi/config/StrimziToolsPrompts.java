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

    /**
     * Operator name parameter description for metrics.
     */
    public static final String OPERATOR_NAME_DESC =
        "Strimzi operator deployment name."
            + " Omit to auto-discover.";

    /**
     * Cluster name parameter description for operator metrics.
     */
    public static final String OPERATOR_CLUSTER_DESC =
        "Kafka cluster name to include entity operator"
            + " (user-operator and topic-operator) metrics."
            + " Omit for cluster operator metrics only.";

    /**
     * Listener name parameter description.
     */
    public static final String LISTENER_DESC =
        "Listener name to filter results"
            + " (e.g., 'plain', 'tls', 'external')."
            + " Omit to return all listeners.";

    /**
     * Metrics category parameter description.
     */
    public static final String METRICS_CATEGORY_DESC =
        "Metric category: 'replication', 'performance',"
            + " 'resources', or 'throughput'."
            + " Defaults to 'replication' if omitted.";

    /**
     * Operator metrics category parameter description.
     */
    public static final String OPERATOR_METRICS_CATEGORY_DESC =
        "Metric category: 'reconciliation', 'resources',"
            + " or 'jvm'."
            + " Defaults to 'reconciliation' if no category"
            + " or metric names are provided.";

    /**
     * Metric names parameter description.
     */
    public static final String METRICS_NAMES_DESC =
        "Comma-separated list of explicit Prometheus"
            + " metric names to retrieve."
            + " Can be combined with a category.";

    /**
     * Range minutes parameter description.
     */
    public static final String RANGE_MINUTES_DESC =
        "Relative time range in minutes from now"
            + " (e.g., 15, 60). Omit for instant query."
            + " Mutually exclusive with startTime/endTime.";

    /**
     * Start time parameter description for absolute time ranges.
     */
    public static final String START_TIME_DESC =
        "Absolute start time in ISO 8601 format"
            + " (e.g., '2025-01-15T10:00:00Z')."
            + " Use with endTime; mutually exclusive with rangeMinutes.";

    /**
     * End time parameter description for absolute time ranges.
     */
    public static final String END_TIME_DESC =
        "Absolute end time in ISO 8601 format"
            + " (e.g., '2025-01-15T12:00:00Z')."
            + " Use with startTime; mutually exclusive with rangeMinutes.";

    /**
     * Step seconds parameter description.
     */
    public static final String STEP_SECONDS_DESC =
        "Query resolution step in seconds for range queries."
            + " Defaults to server-configured value (typically 60).";

    private StrimziToolsPrompts() {
    }
}
