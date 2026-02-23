/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.config;

/**
 * Application constants for both general Kubernetes resources and Strimzi-specific values.
 * Organized to clearly separate general-purpose constants from technology-specific ones.
 */
public final class Constants {

    private Constants() {
        // Utility class - no instantiation
    }

    /**
     * General Kubernetes constants that any service can use.
     */
    public static final class Kubernetes {

        /**
         * Standard Kubernetes labels.
         */
        public static final class Labels {
            /** Standard Kubernetes application name label. */
            public static final String APP_NAME_LABEL = "app.kubernetes.io/name";
            /** Standard Kubernetes managed-by label. */
            public static final String MANAGED_BY_LABEL = "app.kubernetes.io/managed-by";
            /** Simple app label. */
            public static final String APP_LABEL = "app";
            /** Name label. */
            public static final String NAME_LABEL = "name";

            private Labels() {
            }
        }

        /**
         * Kubernetes condition types.
         */
        public static final class ConditionTypes {
            /** Ready condition type. */
            public static final String READY = "Ready";

            private ConditionTypes() {
            }
        }

        /**
         * Kubernetes condition statuses.
         */
        public static final class ConditionStatuses {
            /** True condition status. */
            public static final String TRUE = "True";
            /** False condition status. */
            public static final String FALSE = "False";

            private ConditionStatuses() {
            }
        }

        /**
         * Kubernetes pod phase constants.
         */
        public static final class PodPhases {
            /** Pod is running and ready. */
            public static final String RUNNING = "Running";
            /** Pod has failed. */
            public static final String FAILED = "Failed";
            /** Pod is pending. */
            public static final String PENDING = "Pending";
            /** Pod has succeeded. */
            public static final String SUCCEEDED = "Succeeded";
            /** Pod phase is unknown. */
            public static final String UNKNOWN = "Unknown";

            private PodPhases() {
            }
        }

        /**
         * Kubernetes container state constants.
         */
        public static final class ContainerStates {
            /** Container is running. */
            public static final String RUNNING = "running";
            /** Container is waiting. */
            public static final String WAITING = "waiting";
            /** Container has terminated. */
            public static final String TERMINATED = "terminated";
            /** Container state is unknown. */
            public static final String UNKNOWN = "unknown";

            private ContainerStates() {
            }
        }

        /**
         * General status values that can be used by any service.
         */
        public static final class StatusValues {
            /** Unknown status. */
            public static final String UNKNOWN = "Unknown";
            /** Unknown cluster name. */
            public static final String UNKNOWN_CLUSTER = "unknown";
            /** Partial status. */
            public static final String PARTIAL = "PARTIAL";
            /** Not found status. */
            public static final String NOT_FOUND = "NOT_FOUND";

            private StatusValues() {
            }
        }

        private Kubernetes() {
        }
    }

    /**
     * Strimzi-specific constants that should only be used by Strimzi services.
     */
    public static final class Strimzi {

        /**
         * Strimzi component names.
         */
        public static final class ComponentNames {
            /** Cluster operator component. */
            public static final String CLUSTER_OPERATOR = "cluster-operator";
            /** Topic operator component. */
            public static final String TOPIC_OPERATOR = "topic-operator";
            /** User operator component. */
            public static final String USER_OPERATOR = "user-operator";
            /** Entity operator component. */
            public static final String ENTITY_OPERATOR = "entity-operator";
            /** Kafka component. */
            public static final String KAFKA = "kafka";
            /** ZooKeeper component. */
            public static final String ZOOKEEPER = "zookeeper";
            /** Connect component. */
            public static final String CONNECT = "connect";
            /** Bridge component. */
            public static final String BRIDGE = "bridge";

            private ComponentNames() {
            }
        }

        /**
         * Common string values for Strimzi resources.
         */
        public static final class CommonValues {
            /** Strimzi application identifier. */
            public static final String STRIMZI = "strimzi";
            /** Strimzi cluster operator name. */
            public static final String STRIMZI_CLUSTER_OPERATOR = "strimzi-cluster-operator";

            private CommonValues() {
            }
        }

        /**
         * Strimzi-specific labels.
         */
        public static final class Labels {
            /** Strimzi operator label. */
            public static final String OPERATOR_LABEL = "strimzi.io/operator";

            private Labels() {
            }
        }

        /**
         * Status values for Strimzi cluster and topic conditions.
         */
        public static final class StatusValues {
            /** Ready status. */
            public static final String READY = "Ready";
            /** NotReady status. */
            public static final String NOT_READY = "NotReady";
            /** Error status. */
            public static final String ERROR = "Error";
            /** Unknown status. */
            public static final String UNKNOWN = "Unknown";
            /** Healthy status. */
            public static final String HEALTHY = "HEALTHY";
            /** Degraded status. */
            public static final String DEGRADED = "DEGRADED";
            /** Down status. */
            public static final String DOWN = "DOWN";
            /** Not deployed status. */
            public static final String NOT_DEPLOYED = "NOT_DEPLOYED";

            private StatusValues() {
            }
        }

        /**
         * Component type strings for filtering and identification.
         */
        public static final class ComponentTypes {
            /** Kafka component type (lowercase). */
            public static final String KAFKA_LOWERCASE = "kafka";
            /** ZooKeeper component type (lowercase). */
            public static final String ZOOKEEPER_LOWERCASE = "zookeeper";
            /** Operator component type (lowercase). */
            public static final String OPERATOR_LOWERCASE = "operator";

            private ComponentTypes() {
            }
        }

        /**
         * Storage type constants for Kafka clusters.
         */
        public static final class StorageTypes {
            /** Ephemeral storage type. */
            public static final String EPHEMERAL = "ephemeral";
            /** Persistent claim storage type. */
            public static final String PERSISTENT_CLAIM = "persistent-claim";
            /** JBOD storage type. */
            public static final String JBOD = "jbod";
            /** Unknown storage type. */
            public static final String UNKNOWN = "unknown";

            private StorageTypes() {
            }
        }

        private Strimzi() {
        }
    }
}