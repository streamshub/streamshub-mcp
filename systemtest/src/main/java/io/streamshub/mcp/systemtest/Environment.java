/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.systemtest;

/**
 * Reads environment variables and system properties for test configuration.
 */
public final class Environment {

    /** MCP server container image. */
    public static final String MCP_IMAGE = getOrDefault("MCP_IMAGE", "quay.io/streamshub/strimzi-mcp:latest");

    /** Direct MCP URL override. When set, skips all connectivity setup (e.g. for manual port-forward). */
    public static final String MCP_URL = getOrDefault("MCP_URL", null);

    /** Connectivity strategy: {@code NODEPORT}, {@code INGRESS}, or {@code ROUTE}. Auto-detected if not set. */
    public static final String MCP_CONNECTIVITY = getOrDefault("MCP_CONNECTIVITY", null);

    /** Localhost port for Ingress access (default: 9090 for Podman Desktop kind with Contour). */
    public static final int MCP_INGRESS_PORT = Integer.parseInt(getOrDefault("MCP_INGRESS_PORT", "9090"));

    /** Hostname for Ingress rule. Empty string means no host constraint (matches all). */
    public static final String MCP_INGRESS_HOST = getOrDefault("MCP_INGRESS_HOST", "");

    /** Skip Strimzi operator and Kafka deployment (use existing infra). */
    public static final boolean SKIP_STRIMZI_INSTALL =
        Boolean.parseBoolean(getOrDefault("SKIP_STRIMZI_INSTALL", "false"));

    /** Kafka cluster name to use in tests. */
    public static final String KAFKA_CLUSTER_NAME = getOrDefault("KAFKA_CLUSTER_NAME", Constants.KAFKA_CLUSTER_NAME);

    /** Kafka namespace. */
    public static final String KAFKA_NAMESPACE = getOrDefault("KAFKA_NAMESPACE", Constants.KAFKA_NAMESPACE);

    private Environment() {
    }

    /**
     * Get value from environment variable, system property, or use default.
     *
     * @param name         the environment variable / system property name
     * @param defaultValue the default value if not set
     * @return the resolved value
     */
    private static String getOrDefault(final String name, final String defaultValue) {
        String envValue = System.getenv(name);
        if (envValue != null && !envValue.isBlank()) {
            return envValue;
        }
        String propValue = System.getProperty(name);
        if (propValue != null && !propValue.isBlank()) {
            return propValue;
        }
        return defaultValue;
    }
}
