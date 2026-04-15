/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.systemtest.templates.strimzi;

import io.strimzi.api.ResourceLabels;
import io.strimzi.api.kafka.model.nodepool.KafkaNodePoolBuilder;
import io.strimzi.api.kafka.model.nodepool.ProcessRoles;

import java.util.List;
import java.util.Map;

/**
 * Template builders for KafkaNodePool custom resources.
 */
public final class KafkaNodePoolTemplates {

    private KafkaNodePoolTemplates() {
    }

    /**
     * Create a broker node pool builder.
     *
     * @param namespace   the namespace
     * @param poolName    the node pool name
     * @param clusterName the Kafka cluster name
     * @param replicas    the number of replicas
     * @return a pre-configured KafkaNodePoolBuilder for broker role
     */
    public static KafkaNodePoolBuilder brokerPool(final String namespace, final String poolName,
                                                   final String clusterName, final int replicas) {
        return defaultPool(namespace, poolName, clusterName, replicas, List.of(ProcessRoles.BROKER));
    }

    /**
     * Create a controller node pool builder.
     *
     * @param namespace   the namespace
     * @param poolName    the node pool name
     * @param clusterName the Kafka cluster name
     * @param replicas    the number of replicas
     * @return a pre-configured KafkaNodePoolBuilder for controller role
     */
    public static KafkaNodePoolBuilder controllerPool(final String namespace, final String poolName,
                                                       final String clusterName, final int replicas) {
        return defaultPool(namespace, poolName, clusterName, replicas, List.of(ProcessRoles.CONTROLLER));
    }

    /**
     * Create a mixed (broker + controller) node pool builder.
     *
     * @param namespace   the namespace
     * @param poolName    the node pool name
     * @param clusterName the Kafka cluster name
     * @param replicas    the number of replicas
     * @return a pre-configured KafkaNodePoolBuilder for mixed roles
     */
    public static KafkaNodePoolBuilder mixedPool(final String namespace, final String poolName,
                                                  final String clusterName, final int replicas) {
        return defaultPool(namespace, poolName, clusterName, replicas,
            List.of(ProcessRoles.BROKER, ProcessRoles.CONTROLLER));
    }

    private static KafkaNodePoolBuilder defaultPool(final String namespace, final String poolName,
                                                     final String clusterName, final int replicas,
                                                     final List<ProcessRoles> roles) {
        return new KafkaNodePoolBuilder()
            .withNewMetadata()
                .withName(poolName)
                .withNamespace(namespace)
                .withLabels(Map.of(ResourceLabels.STRIMZI_CLUSTER_LABEL, clusterName))
            .endMetadata()
            .withNewSpec()
                .withReplicas(replicas)
                .withRoles(roles)
                .withNewEphemeralStorage()
                .endEphemeralStorage()
            .endSpec();
    }
}
