/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.config;

/**
 * Strimzi-specific constants.
 * Uses Strimzi API constants where available to avoid magic values.
 */
public final class StrimziConstants {

    private StrimziConstants() {
    }

    /**
     * Strimzi label keys not available in the Strimzi API ResourceLabels class.
     */
    public static final class Labels {
        /**
         * Strimzi label key identifying the node pool name.
         */
        public static final String POOL_NAME = "strimzi.io/pool-name";

        private Labels() {
        }
    }

    /**
     * Values for the strimzi.io/kind label.
     */
    public static final class KindValues {
        /**
         * Kind label value for the Strimzi cluster operator.
         */
        public static final String CLUSTER_OPERATOR = "cluster-operator";

        /**
         * Kind label value for KafkaConnect pods.
         */
        public static final String KAFKA_CONNECT = "KafkaConnect";

        /**
         * Kind label value for KafkaBridge pods.
         */
        public static final String KAFKA_BRIDGE = "KafkaBridge";

        private KindValues() {
        }
    }

    /**
     * Constants for the Strimzi entity operator deployment.
     * The entity operator runs as a single pod per Kafka cluster with two containers:
     * user-operator and topic-operator, each exposing metrics on a different port.
     */
    public static final class EntityOperator {
        /**
         * Value of the {@code app.kubernetes.io/name} label on entity operator pods.
         */
        public static final String APP_NAME_VALUE = "entity-operator";

        /**
         * Metrics port for the user-operator container.
         */
        public static final int USER_OPERATOR_PORT = 8081;

        /**
         * Metrics port for the topic-operator container.
         */
        public static final int TOPIC_OPERATOR_PORT = 8080;

        private EntityOperator() {
        }
    }

    /**
     * Values for the strimzi.io/component-type label.
     */
    public static final class ComponentTypes {
        /**
         * Component type value for Kafka broker components.
         */
        public static final String KAFKA = "kafka";

        /**
         * Component type value for Kafka Exporter components.
         */
        public static final String KAFKA_EXPORTER = "kafka-exporter";

        /**
         * Component type value for Kafka Bridge components.
         */
        public static final String KAFKA_BRIDGE = "kafka-bridge";

        /**
         * Component types representing Kafka broker pods.
         */
        public static final java.util.Set<String> BROKER_TYPES =
            java.util.Set.of(KAFKA);

        private ComponentTypes() {
        }
    }

    /**
     * Strimzi Drain Cleaner constants for deployment and webhook discovery.
     */
    public static final class DrainCleaner {
        /**
         * Value of the app label on drain cleaner deployments.
         */
        public static final String APP_LABEL_VALUE = "strimzi-drain-cleaner";

        /**
         * Well-known name of the ValidatingWebhookConfiguration.
         */
        public static final String WEBHOOK_CONFIG_NAME = "strimzi-drain-cleaner";

        /**
         * Environment variable controlling eviction denial mode.
         * When true (default), evictions are denied (standard mode).
         * When false, evictions are allowed (legacy mode).
         */
        public static final String ENV_DENY_EVICTION = "STRIMZI_DENY_EVICTION";

        /**
         * Environment variable controlling Kafka pod draining.
         */
        public static final String ENV_DRAIN_KAFKA = "STRIMZI_DRAIN_KAFKA";

        /**
         * Environment variable restricting monitored namespaces.
         */
        public static final String ENV_DRAIN_NAMESPACES = "STRIMZI_DRAIN_NAMESPACES";

        /**
         * Mode value for standard eviction denial mode.
         */
        public static final String MODE_STANDARD = "standard";

        /**
         * Mode value for legacy mode (evictions allowed, relies on PDB).
         */
        public static final String MODE_LEGACY = "legacy";

        private DrainCleaner() {
        }
    }

    /**
     * Strimzi operator label values for discovery.
     */
    public static final class Operator {
        /**
         * Value of the app label on the operator deployment (app=strimzi).
         */
        public static final String APP_LABEL_VALUE = "strimzi";

        private Operator() {
        }
    }

    /**
     * Strimzi-managed secret name suffixes and data keys.
     */
    public static final class Secrets {
        /**
         * Suffix for the cluster CA certificate secret ({@code {cluster}-cluster-ca-cert}).
         */
        public static final String CLUSTER_CA_CERT_SUFFIX = "-cluster-ca-cert";

        /**
         * Suffix for the clients CA certificate secret ({@code {cluster}-clients-ca-cert}).
         */
        public static final String CLIENTS_CA_CERT_SUFFIX = "-clients-ca-cert";

        /**
         * Data key for the CA certificate PEM inside a Strimzi CA secret.
         */
        public static final String CA_CRT_KEY = "ca.crt";

        private Secrets() {
        }
    }

    /**
     * MCP resource URI templates and builders for Strimzi resources.
     * Template constants are used in {@code @ResourceTemplate} annotations,
     * and builder methods resolve them at runtime for resource registration
     * and subscription notifications.
     */
    public static final class ResourceUris {

        /** URI template for Kafka cluster status resources. */
        public static final String KAFKA_STATUS =
            "strimzi://kafka.strimzi.io/namespaces/{namespace}/kafkas/{name}/status";

        /** URI template for Kafka cluster topology resources. */
        public static final String KAFKA_TOPOLOGY =
            "strimzi://kafka.strimzi.io/namespaces/{namespace}/kafkas/{name}/topology";

        /** URI template for KafkaNodePool status resources. */
        public static final String NODEPOOL_STATUS =
            "strimzi://kafka.strimzi.io/namespaces/{namespace}/kafkanodepools/{name}/status";

        /** URI template for KafkaTopic status resources. */
        public static final String TOPIC_STATUS =
            "strimzi://kafka.strimzi.io/namespaces/{namespace}/kafkatopics/{name}/status";

        /** URI template for KafkaUser status resources. */
        public static final String USER_STATUS =
            "strimzi://kafka.strimzi.io/namespaces/{namespace}/kafkausers/{name}/status";

        /** URI template for Strimzi operator status resources. */
        public static final String OPERATOR_STATUS =
            "strimzi://operator.strimzi.io/namespaces/{namespace}/clusteroperator/{name}/status";

        private ResourceUris() {
        }

        /**
         * Build a resolved Kafka cluster status URI.
         *
         * @param namespace the Kubernetes namespace
         * @param name      the Kafka cluster name
         * @return the resolved URI string
         */
        public static String kafkaStatus(final String namespace, final String name) {
            return resolve(KAFKA_STATUS, namespace, name);
        }

        /**
         * Build a resolved Kafka cluster topology URI.
         *
         * @param namespace the Kubernetes namespace
         * @param name      the Kafka cluster name
         * @return the resolved URI string
         */
        public static String kafkaTopology(final String namespace, final String name) {
            return resolve(KAFKA_TOPOLOGY, namespace, name);
        }

        /**
         * Build a resolved KafkaNodePool status URI.
         *
         * @param namespace the Kubernetes namespace
         * @param name      the KafkaNodePool name
         * @return the resolved URI string
         */
        public static String nodePoolStatus(final String namespace, final String name) {
            return resolve(NODEPOOL_STATUS, namespace, name);
        }

        /**
         * Build a resolved KafkaTopic status URI.
         *
         * @param namespace the Kubernetes namespace
         * @param name      the KafkaTopic name
         * @return the resolved URI string
         */
        public static String topicStatus(final String namespace, final String name) {
            return resolve(TOPIC_STATUS, namespace, name);
        }

        /**
         * Build a resolved KafkaUser status URI.
         *
         * @param namespace the Kubernetes namespace
         * @param name      the KafkaUser name
         * @return the resolved URI string
         */
        public static String userStatus(final String namespace, final String name) {
            return resolve(USER_STATUS, namespace, name);
        }

        /**
         * Build a resolved Strimzi operator status URI.
         *
         * @param namespace the Kubernetes namespace
         * @param name      the operator deployment name
         * @return the resolved URI string
         */
        public static String operatorStatus(final String namespace, final String name) {
            return resolve(OPERATOR_STATUS, namespace, name);
        }

        private static String resolve(final String template, final String namespace, final String name) {
            return template.replace("{namespace}", namespace).replace("{name}", name);
        }
    }

}
