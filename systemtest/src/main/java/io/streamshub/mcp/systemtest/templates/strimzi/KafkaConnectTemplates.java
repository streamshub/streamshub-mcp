/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.systemtest.templates.strimzi;

import io.strimzi.api.kafka.model.connect.KafkaConnectBuilder;

/**
 * Template builders for KafkaConnect custom resources with sensible defaults.
 */
public final class KafkaConnectTemplates {

    /** Default Kafka Connect version. */
    private static final String DEFAULT_VERSION = "4.2.0";

    /** Default number of Connect replicas. */
    private static final int DEFAULT_REPLICAS = 1;

    private KafkaConnectTemplates() {
    }

    /**
     * Create a KafkaConnect builder with standard defaults.
     *
     * @param namespace        the namespace
     * @param name             the KafkaConnect cluster name
     * @param kafkaClusterName the Kafka cluster name for bootstrap servers
     * @param replicas         the number of Connect replicas
     * @return a pre-configured KafkaConnectBuilder
     */
    public static KafkaConnectBuilder kafkaConnect(final String namespace, final String name,
                                                    final String kafkaClusterName, final int replicas) {
        String bootstrapServers = kafkaClusterName + "-kafka-bootstrap." + namespace + ".svc:9092";
        return new KafkaConnectBuilder()
            .withNewMetadata()
                .withName(name)
                .withNamespace(namespace)
                .addToAnnotations("strimzi.io/use-connector-resources", "true")
            .endMetadata()
            .withNewSpec()
                .withVersion(DEFAULT_VERSION)
                .withReplicas(replicas)
                .withBootstrapServers(bootstrapServers)
                .withNewBuild()
                    .withNewDockerOutput()
                        .withImage("localhost:5000/" + name + ":latest")
                    .endDockerOutput()
                .endBuild()
                .addToConfig("group.id", name)
                .addToConfig("offset.storage.topic", name + "-offsets")
                .addToConfig("config.storage.topic", name + "-configs")
                .addToConfig("status.storage.topic", name + "-status")
                .addToConfig("offset.storage.replication.factor", 1)
                .addToConfig("config.storage.replication.factor", 1)
                .addToConfig("status.storage.replication.factor", 1)
            .endSpec();
    }

    /**
     * Create a minimal KafkaConnect builder with default replicas.
     *
     * @param namespace        the namespace
     * @param name             the KafkaConnect cluster name
     * @param kafkaClusterName the Kafka cluster name for bootstrap servers
     * @return a pre-configured KafkaConnectBuilder
     */
    public static KafkaConnectBuilder kafkaConnect(final String namespace, final String name,
                                                    final String kafkaClusterName) {
        return kafkaConnect(namespace, name, kafkaClusterName, DEFAULT_REPLICAS);
    }
}
