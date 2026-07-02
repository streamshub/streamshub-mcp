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
    public static final String MCP_IMAGE = ENV_VARIABLES.getOrDefault("MCP_IMAGE", "quay.io/streamshub/strimzi-mcp:latest");

    /** MCP server image pull policy */
    public static final String MCP_IMAGE_PULL_POLICY = ENV_VARIABLES.getOrDefault("MCP_IMAGE_PULL_POLICY", "IfNotPresent");

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

    /** Skip Strimzi Drain Cleaner deployment (use existing infra). */
    public static final boolean SKIP_DRAIN_CLEANER_INSTALL =
        Boolean.parseBoolean(ENV_VARIABLES.getOrDefault("SKIP_DRAIN_CLEANER_INSTALL", "false"));

    /** Kafka cluster name to use in tests. */
    public static final String KAFKA_CLUSTER_NAME = ENV_VARIABLES.getOrDefault("KAFKA_CLUSTER_NAME", Constants.KAFKA_CLUSTER_NAME);

    /** Kafka namespace. */
    public static final String KAFKA_NAMESPACE = ENV_VARIABLES.getOrDefault("KAFKA_NAMESPACE", Constants.KAFKA_NAMESPACE);

    /** KafkaConnect container image (pre-built with connector plugins). */
    public static final String CONNECT_IMAGE = ENV_VARIABLES.getOrDefault("CONNECT_IMAGE", Constants.CONNECT_TEST_IMAGE);

    /** Prometheus URL override. When set, skips auto-discovery of Prometheus/Thanos services. */
    public static final String PROMETHEUS_URL = ENV_VARIABLES.getOrDefault("PROMETHEUS_URL", null);

    /** Prometheus auth mode override ({@code none}, {@code sa-token}, {@code basic}). Auto-detected if not set. */
    public static final String PROMETHEUS_AUTH_MODE = ENV_VARIABLES.getOrDefault("PROMETHEUS_AUTH_MODE", null);

    private Environment() {
    }
}
