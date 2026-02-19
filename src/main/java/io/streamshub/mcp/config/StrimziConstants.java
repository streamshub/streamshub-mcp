/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.config;

/**
 * Constants for Strimzi and Kubernetes labels, component names, condition values, and environment variables
 * to eliminate magic strings throughout the codebase.
 */
public final class StrimziConstants {

    private StrimziConstants() {
        // Utility class - no instantiation
    }

    /**
     * Strimzi-specific labels.
     */
    public static final class StrimziLabels {
        /**
         * Label for identifying the Kafka cluster a resource belongs to
         */
        public static final String CLUSTER_LABEL = "strimzi.io/cluster";
        /**
         * Label for identifying the type/kind of Strimzi resource
         */
        public static final String KIND_LABEL = "strimzi.io/kind";
        /**
         * Label for identifying the component name
         */
        public static final String NAME_LABEL = "strimzi.io/name";

        private StrimziLabels() {
        }
    }

    /**
     * Standard Kubernetes labels.
     */
    public static final class KubernetesLabels {
        /**
         * Standard Kubernetes application name label
         */
        public static final String APP_NAME_LABEL = "app.kubernetes.io/name";
        /**
         * Simple app label
         */
        public static final String APP_LABEL = "app";
        /**
         * Name label
         */
        public static final String NAME_LABEL = "name";

        private KubernetesLabels() {
        }
    }

    /**
     * Strimzi component names.
     */
    public static final class ComponentNames {
        /**
         * Cluster operator component
         */
        public static final String CLUSTER_OPERATOR = "cluster-operator";
        /**
         * Topic operator component
         */
        public static final String TOPIC_OPERATOR = "topic-operator";
        /**
         * User operator component
         */
        public static final String USER_OPERATOR = "user-operator";
        /**
         * Entity operator component
         */
        public static final String ENTITY_OPERATOR = "entity-operator";
        /**
         * Kafka component
         */
        public static final String KAFKA = "kafka";
        /**
         * ZooKeeper component
         */
        public static final String ZOOKEEPER = "zookeeper";
        /**
         * Connect component
         */
        public static final String CONNECT = "connect";
        /**
         * Bridge component
         */
        public static final String BRIDGE = "bridge";

        private ComponentNames() {
        }
    }

    /**
     * Common string values for Strimzi resources.
     */
    public static final class CommonValues {
        /**
         * Strimzi application identifier
         */
        public static final String STRIMZI = "strimzi";
        /**
         * Strimzi cluster operator name
         */
        public static final String STRIMZI_CLUSTER_OPERATOR = "strimzi-cluster-operator";

        private CommonValues() {
        }
    }

    /**
     * Kubernetes condition types.
     */
    public static final class ConditionTypes {
        /**
         * Ready condition type
         */
        public static final String READY = "Ready";

        private ConditionTypes() {
        }
    }

    /**
     * Kubernetes condition statuses.
     */
    public static final class ConditionStatuses {
        /**
         * True condition status
         */
        public static final String TRUE = "True";
        /**
         * False condition status
         */
        public static final String FALSE = "False";

        private ConditionStatuses() {
        }
    }
}