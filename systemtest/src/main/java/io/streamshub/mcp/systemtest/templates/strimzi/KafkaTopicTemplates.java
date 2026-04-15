/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.systemtest.templates.strimzi;

import io.strimzi.api.ResourceLabels;
import io.strimzi.api.kafka.model.topic.KafkaTopicBuilder;

import java.util.Map;

/**
 * Template builders for KafkaTopic custom resources.
 */
public final class KafkaTopicTemplates {

    private KafkaTopicTemplates() {
    }

    /**
     * Create a KafkaTopic builder with specified configuration.
     *
     * @param namespace   the namespace
     * @param topicName   the topic name
     * @param clusterName the Kafka cluster name
     * @param partitions  number of partitions
     * @param replicas    number of replicas
     * @return a pre-configured KafkaTopicBuilder
     */
    public static KafkaTopicBuilder topic(final String namespace, final String topicName,
                                           final String clusterName, final int partitions,
                                           final int replicas) {
        return new KafkaTopicBuilder()
            .withNewMetadata()
                .withName(topicName)
                .withNamespace(namespace)
                .withLabels(Map.of(ResourceLabels.STRIMZI_CLUSTER_LABEL, clusterName))
            .endMetadata()
            .withNewSpec()
                .withPartitions(partitions)
                .withReplicas(replicas)
            .endSpec();
    }
}
