/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.systemtest;

import io.skodjob.kubetest4j.environment.TestEnvironmentVariables;

/**
 * Reads environment variables and system properties for test configuration.
 */
public final class Environment {

    private static final TestEnvironmentVariables ENV_VARIABLES = new TestEnvironmentVariables();

    /** MCP server container image. */
    // TODO - revert!
    public static final String MCP_IMAGE = ENV_VARIABLES.getOrDefault("MCP_IMAGE", "quay.io/jstejska/strimzi-mcp:dev");

    /** Direct MCP URL override. When set, skips all connectivity setup (e.g. for manual port-forward). */
    public static final String MCP_URL = ENV_VARIABLES.getOrDefault("MCP_URL", null);

    /** Connectivity strategy: {@code NODEPORT}, {@code INGRESS}, or {@code ROUTE}. Auto-detected if not set. */
    public static final String MCP_CONNECTIVITY = ENV_VARIABLES.getOrDefault("MCP_CONNECTIVITY", null);

    /** Localhost port for Ingress access (default: 9090 for Podman Desktop kind with Contour). */
    public static final int MCP_INGRESS_PORT = ENV_VARIABLES.getOrDefault("MCP_INGRESS_PORT", Integer::valueOf, 9090);

    /** Hostname for Ingress rule. Empty string means no host constraint (matches all). */
    public static final String MCP_INGRESS_HOST = ENV_VARIABLES.getOrDefault("MCP_INGRESS_HOST", "");

    /** Skip Strimzi operator and Kafka deployment (use existing infra). */
    public static final boolean SKIP_STRIMZI_INSTALL =
        Boolean.parseBoolean(ENV_VARIABLES.getOrDefault("SKIP_STRIMZI_INSTALL", "false"));

    /** Kafka cluster name to use in tests. */
    public static final String KAFKA_CLUSTER_NAME = ENV_VARIABLES.getOrDefault("KAFKA_CLUSTER_NAME", Constants.KAFKA_CLUSTER_NAME);

    /** Kafka namespace. */
    public static final String KAFKA_NAMESPACE = ENV_VARIABLES.getOrDefault("KAFKA_NAMESPACE", Constants.KAFKA_NAMESPACE);

    private Environment() {
    }
}
