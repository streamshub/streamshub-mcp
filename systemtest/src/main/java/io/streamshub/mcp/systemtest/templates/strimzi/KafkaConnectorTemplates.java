/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.systemtest.templates.strimzi;

import io.strimzi.api.kafka.model.connector.KafkaConnectorBuilder;

/**
 * Template builders for KafkaConnector custom resources with sensible defaults.
 */
public final class KafkaConnectorTemplates {

    private KafkaConnectorTemplates() {
    }

    /**
     * Create a KafkaConnector builder for a FileStreamSink connector.
     *
     * @param namespace          the namespace
     * @param name               the connector name
     * @param connectClusterName the parent KafkaConnect cluster name
     * @param topicName          the topic to consume from
     * @return a pre-configured KafkaConnectorBuilder
     */
    public static KafkaConnectorBuilder fileStreamSink(final String namespace, final String name,
                                                        final String connectClusterName,
                                                        final String topicName) {
        return new KafkaConnectorBuilder()
            .withNewMetadata()
                .withName(name)
                .withNamespace(namespace)
                .addToLabels("strimzi.io/cluster", connectClusterName)
            .endMetadata()
            .withNewSpec()
                .withClassName("org.apache.kafka.connect.file.FileStreamSinkConnector")
                .withTasksMax(1)
                .addToConfig("file", "/tmp/" + name + "-output.txt")
                .addToConfig("topics", topicName)
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
