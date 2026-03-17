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

        private KindValues() {
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

        private ComponentTypes() {
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
