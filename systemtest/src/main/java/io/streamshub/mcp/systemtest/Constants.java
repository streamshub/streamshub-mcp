/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.systemtest;

/**
 * Constants used across system tests.
 */
public final class Constants {

    // --- Namespaces ---

    /** Namespace for the MCP server deployment. */
    public static final String MCP_NAMESPACE = "streamshub-mcp";

    /** Namespace where the Kafka cluster is deployed. */
    public static final String KAFKA_NAMESPACE = "strimzi-kafka";

    /** Namespace where the Strimzi operator is deployed. */
    public static final String STRIMZI_NAMESPACE = "strimzi";

    // --- MCP Server ---

    /** Name of the MCP server deployment, service, and related resources. */
    public static final String MCP_NAME = "streamshub-strimzi-mcp";

    /** Label key used to identify MCP server resources. */
    public static final String MCP_APP_LABEL_KEY = "app.kubernetes.io/name";

    /** Label value used to identify MCP server resources. */
    public static final String MCP_APP_LABEL = "strimzi-mcp";

    /** Default container port for the MCP server. */
    public static final int MCP_PORT = 8080;

    /** NodePort used to expose the MCP server on kind clusters. */
    public static final int MCP_NODE_PORT = 30080;

    // --- Kafka Cluster ---

    /** Name of the pre-deployed Kafka cluster. */
    public static final String KAFKA_CLUSTER_NAME = "mcp-cluster";

    // --- Timeouts (milliseconds) ---

    /** Timeout for MCP server deployment readiness. */
    public static final long MCP_READY_TIMEOUT_MS = 120_000L;

    /** Timeout for Kafka CR readiness. */
    public static final long KAFKA_READY_TIMEOUT_MS = 840_000L;

    /** Timeout for KafkaNodePool CR readiness. */
    public static final long KAFKA_NODE_POOL_READY_TIMEOUT_MS = 600_000L;

    /** Timeout for KafkaTopic CR readiness. */
    public static final long KAFKA_TOPIC_READY_TIMEOUT_MS = 180_000L;

    /** Timeout for KafkaConnect CR readiness. */
    public static final long KAFKA_CONNECT_READY_TIMEOUT_MS = 300_000L;

    /** Timeout for KafkaConnector CR readiness. */
    public static final long KAFKA_CONNECTOR_READY_TIMEOUT_MS = 180_000L;

    /** Poll interval for Kafka CR readiness checks. */
    public static final long KAFKA_READY_POLL_MS = 10_000L;

    // --- Paths ---

    /** Project root directory (parent of systemtest module). */
    public static final String PROJECT_ROOT = System.getProperty("user.dir") + "/..";

    /** Path to MCP server install directory. */
    public static final String INSTALL_DIR = PROJECT_ROOT + "/install/strimzi-mcp/base/";

    /** Path to Strimzi manifests directory. */
    public static final String STRIMZI_MANIFESTS_DIR = PROJECT_ROOT + "/dev/manifests/strimzi/";

    private Constants() {
    }
}
