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

}
