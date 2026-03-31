/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.dto.metrics;

/**
 * Identifies a pod endpoint to scrape for metrics.
 *
 * @param namespace the Kubernetes namespace of the pod
 * @param podName   the pod name
 * @param port      the metrics port (null for auto-detection)
 * @param path      the metrics endpoint path (default {@code /metrics})
 */
public record PodTarget(
    String namespace,
    String podName,
    Integer port,
    String path
) {

    /** Default metrics endpoint path. */
    public static final String DEFAULT_PATH = "/metrics";

    /**
     * Creates a pod target with auto-detected port and default path.
     *
     * @param namespace the Kubernetes namespace
     * @param podName   the pod name
     * @return a new pod target
     */
    public static PodTarget of(final String namespace, final String podName) {
        return new PodTarget(namespace, podName, null, DEFAULT_PATH);
    }

    /**
     * Creates a pod target with explicit port and path.
     *
     * @param namespace the Kubernetes namespace
     * @param podName   the pod name
     * @param port      the metrics port
     * @param path      the metrics endpoint path
     * @return a new pod target
     */
    public static PodTarget of(final String namespace, final String podName,
                                final Integer port, final String path) {
        return new PodTarget(namespace, podName, port, path);
    }
}
