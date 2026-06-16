/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.config;

/**
 * Constants for MCP tool {@code _meta} fields.
 * Used with {@code @MetaField} annotations on tool methods.
 */
public final class ToolMetaFields {

    /**
     * Metadata key: the tool action type (list, get, diagnose, etc.).
     */
    public static final String TYPE = "type";

    /**
     * Metadata key: the Kubernetes/Strimzi resource the tool targets.
     */
    public static final String RESOURCE = "resource";

    /**
     * Metadata key: whether the tool aggregates multiple internal API calls.
     */
    public static final String COMPOSITE = "composite";

    private ToolMetaFields() {
    }

    /**
     * Tool action type values.
     */
    public static final class Types {

        /**
         * Enumerate resources of a kind.
         */
        public static final String LIST = "list";

        /**
         * Retrieve details of a single resource.
         */
        public static final String GET = "get";

        /**
         * Aggregated cross-resource summaries.
         */
        public static final String OVERVIEW = "overview";

        /**
         * Retrieve pod/container logs.
         */
        public static final String LOGS = "logs";

        /**
         * Retrieve Kubernetes events.
         */
        public static final String EVENTS = "events";

        /**
         * Retrieve Prometheus metrics.
         */
        public static final String METRICS = "metrics";

        /**
         * Multi-step diagnostic workflows.
         */
        public static final String DIAGNOSE = "diagnose";

        /**
         * Compare resources side-by-side.
         */
        public static final String COMPARE = "compare";

        /**
         * Readiness or upgrade assessments.
         */
        public static final String ASSESS = "assess";

        /**
         * Health or readiness checks.
         */
        public static final String CHECK = "check";

        private Types() {
        }
    }

    /**
     * Strimzi/Kubernetes resource values.
     */
    public static final class Resources {

        /**
         * Kafka cluster resource.
         */
        public static final String KAFKA = "kafka";

        /**
         * KafkaTopic resource.
         */
        public static final String KAFKA_TOPIC = "kafkatopic";

        /**
         * KafkaUser resource.
         */
        public static final String KAFKA_USER = "kafkauser";

        /**
         * KafkaNodePool resource.
         */
        public static final String KAFKA_NODE_POOL = "kafkanodepool";

        /**
         * KafkaRebalance resource.
         */
        public static final String KAFKA_REBALANCE = "kafkarebalance";

        /**
         * KafkaConnect resource.
         */
        public static final String KAFKA_CONNECT = "kafkaconnect";

        /**
         * KafkaConnector resource.
         */
        public static final String KAFKA_CONNECTOR = "kafkaconnector";

        /**
         * KafkaBridge resource.
         */
        public static final String KAFKA_BRIDGE = "kafkabridge";

        /**
         * KafkaMirrorMaker2 resource.
         */
        public static final String KAFKA_MIRROR_MAKER_2 = "kafkamirrormaker2";

        /**
         * Strimzi cluster operator.
         */
        public static final String STRIMZI_OPERATOR = "strimzi-operator";

        /**
         * Strimzi Kubernetes events.
         */
        public static final String STRIMZI_EVENT = "strimzi-event";

        /**
         * Strimzi Drain Cleaner.
         */
        public static final String DRAIN_CLEANER = "drain-cleaner";

        private Resources() {
        }
    }
}
