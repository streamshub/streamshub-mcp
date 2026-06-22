/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.config;

/**
 * Strimzi-specific resource name constants for MCP tool {@code _meta.resource} field.
 * Used with {@code @MetaField} annotations on tool methods.
 */
public final class StrimziToolResources {

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

    private StrimziToolResources() {
    }
}
