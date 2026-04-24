/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.systemtest.templates.strimzi;

import io.strimzi.api.kafka.model.connector.KafkaConnectorBuilder;

import static io.streamshub.mcp.systemtest.templates.strimzi.KafkaConnectTemplates.CAMEL_TIMER_SOURCE_CLASS_NAME;

/**
 * Template builders for KafkaConnector custom resources with sensible defaults.
 */
public final class KafkaConnectorTemplates {

    /** Default connector name used in system tests. */
    public static final String CONNECTOR_NAME = "mcp-timer-source";

    private KafkaConnectorTemplates() {
    }

    /**
     * Create a KafkaConnector builder for a Camel Timer Source connector.
     *
     * @param namespace          the namespace
     * @param name               the connector name
     * @param connectClusterName the parent KafkaConnect cluster name
     * @param topicName          the topic to produce to
     * @return a pre-configured KafkaConnectorBuilder
     */
    public static KafkaConnectorBuilder camelTimerSource(final String namespace, final String name,
                                                          final String connectClusterName,
                                                          final String topicName) {
        return new KafkaConnectorBuilder()
            .withNewMetadata()
                .withName(name)
                .withNamespace(namespace)
                .addToLabels("strimzi.io/cluster", connectClusterName)
            .endMetadata()
            .withNewSpec()
                .withClassName(CAMEL_TIMER_SOURCE_CLASS_NAME)
                .withTasksMax(1)
                .addToConfig("topics", topicName)
                .addToConfig("camel.kamelet.timer-source.message", "mcp-test")
                .addToConfig("camel.kamelet.timer-source.period", 60000)
            .endSpec();
    }

    /**
     * Create a generic KafkaConnector builder.
     *
     * @param namespace          the namespace
     * @param name               the connector name
     * @param connectClusterName the parent KafkaConnect cluster name
     * @param className          the connector class name
     * @param tasksMax           the maximum number of tasks
     * @return a pre-configured KafkaConnectorBuilder
     */
    public static KafkaConnectorBuilder connector(final String namespace, final String name,
                                                    final String connectClusterName,
                                                    final String className, final int tasksMax) {
        return new KafkaConnectorBuilder()
            .withNewMetadata()
                .withName(name)
                .withNamespace(namespace)
                .addToLabels("strimzi.io/cluster", connectClusterName)
            .endMetadata()
            .withNewSpec()
                .withClassName(className)
                .withTasksMax(tasksMax)
            .endSpec();
    }
}
