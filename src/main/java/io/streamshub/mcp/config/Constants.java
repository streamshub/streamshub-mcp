/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.config;

/**
 * Application constants organized by domain.
 * Uses Strimzi API constants where available to avoid magic values.
 */
public final class Constants {

    /**
     * Generic lowercase "unknown" fallback value used across the application.
     */
    public static final String UNKNOWN = "unknown";

    private Constants() {
    }

    /**
     * General Kubernetes constants.
     */
    public static final class Kubernetes {

        private Kubernetes() {
        }

        /**
         * Standard Kubernetes labels.
         */
        public static final class Labels {
            /**
             * Kubernetes recommended app name label key.
             */
            public static final String APP_NAME = "app.kubernetes.io/name";
            /**
             * Kubernetes recommended managed-by label key.
             */
            public static final String MANAGED_BY = "app.kubernetes.io/managed-by";
            /**
             * Generic app label key.
             */
            public static final String APP = "app";

            private Labels() {
            }
        }

        /**
         * Kubernetes condition types and statuses used together in condition checks.
         */
        public static final class Conditions {
            /**
             * Condition type indicating readiness.
             */
            public static final String TYPE_READY = "Ready";
            /**
             * Condition status value representing true.
             */
            public static final String STATUS_TRUE = "True";
            /**
             * Condition status value representing false.
             */
            public static final String STATUS_FALSE = "False";

            private Conditions() {
            }
        }

        /**
         * Kubernetes pod phase values.
         */
        public static final class PodPhases {
            /**
             * Pod phase indicating the pod is running.
             */
            public static final String RUNNING = "Running";
            /**
             * Pod phase indicating the pod has failed.
             */
            public static final String FAILED = "Failed";
            /**
             * Pod phase indicating the pod status is unknown.
             */
            public static final String UNKNOWN = "Unknown";

            private PodPhases() {
            }
        }

        /**
         * Kubernetes container state values.
         */
        public static final class ContainerStates {
            /**
             * Container state indicating the container is running.
             */
            public static final String RUNNING = "running";
            /**
             * Container state indicating the container is waiting to start.
             */
            public static final String WAITING = "waiting";
            /**
             * Container state indicating the container has terminated.
             */
            public static final String TERMINATED = "terminated";
            /**
             * Container state indicating the container status is unknown.
             */
            public static final String UNKNOWN = "unknown";

            private ContainerStates() {
            }
        }

        /**
         * Resource status values derived from Kubernetes resource conditions.
         */
        public static final class ResourceStatus {
            /**
             * Status indicating the resource is ready.
             */
            public static final String READY = "Ready";
            /**
             * Status indicating the resource is not ready.
             */
            public static final String NOT_READY = "NotReady";
            /**
             * Status indicating the resource is in an error state.
             */
            public static final String ERROR = "Error";
            /**
             * Status indicating the resource status is unknown.
             */
            public static final String UNKNOWN = "Unknown";

            private ResourceStatus() {
            }
        }

        /**
         * Health status values for deployment and pod health assessment.
         */
        public static final class HealthStatus {
            /**
             * Health status indicating the component is fully healthy.
             */
            public static final String HEALTHY = "HEALTHY";
            /**
             * Health status indicating the component is degraded.
             */
            public static final String DEGRADED = "DEGRADED";
            /**
             * Health status indicating partial availability.
             */
            public static final String PARTIAL = "PARTIAL";
            /**
             * Health status indicating the component was not found.
             */
            public static final String NOT_FOUND = "NOT_FOUND";
            /**
             * Health status indicating the component health is unknown.
             */
            public static final String UNKNOWN = "UNKNOWN";

            private HealthStatus() {
            }
        }
    }

    /**
     * Strimzi-specific constants.
     */
    public static final class Strimzi {

        private Strimzi() {
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

    }
}
