/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.systemtest.templates.strimzi;

import io.strimzi.api.ResourceLabels;
import io.strimzi.api.kafka.model.rebalance.KafkaRebalanceBuilder;

import java.util.Map;

/**
 * Template builders for KafkaRebalance custom resources.
 */
public final class KafkaRebalanceTemplates {

    private KafkaRebalanceTemplates() {
    }

    /**
     * Create a KafkaRebalance builder for a full rebalance.
     *
     * @param namespace      the namespace
     * @param rebalanceName  the rebalance resource name
     * @param clusterName    the Kafka cluster name
     * @return a pre-configured KafkaRebalanceBuilder
     */
    public static KafkaRebalanceBuilder rebalance(final String namespace, final String rebalanceName,
                                                   final String clusterName) {
        return new KafkaRebalanceBuilder()
            .withNewMetadata()
                .withName(rebalanceName)
                .withNamespace(namespace)
                .withLabels(Map.of(ResourceLabels.STRIMZI_CLUSTER_LABEL, clusterName))
            .endMetadata()
            .withNewSpec()
            .endSpec();
    }
}
