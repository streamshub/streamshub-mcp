/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.systemtest.resources.strimzi;

import io.fabric8.kubernetes.api.model.DeletionPropagation;
import io.fabric8.kubernetes.api.model.LabelSelectorBuilder;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.NonNamespaceOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.skodjob.kubetest4j.interfaces.ResourceType;
import io.skodjob.kubetest4j.resources.KubeResourceManager;
import io.streamshub.mcp.systemtest.Constants;
import io.streamshub.mcp.systemtest.resources.ResourceConditions;
import io.strimzi.api.ResourceLabels;
import io.strimzi.api.kafka.Crds;
import io.strimzi.api.kafka.model.kafka.Kafka;
import io.strimzi.api.kafka.model.kafka.KafkaList;
import io.strimzi.api.kafka.model.nodepool.KafkaNodePool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * kubetest4j resource type for Kafka custom resources.
 */
public class KafkaType implements ResourceType<Kafka> {

    private static final Logger LOGGER = LoggerFactory.getLogger(KafkaType.class);

    private final MixedOperation<Kafka, KafkaList, Resource<Kafka>> client;

    /**
     * Create a new Kafka resource type.
     */
    public KafkaType() {
        client = kafkaClient();
    }

    /**
     * Get a Fabric8 client for Kafka custom resources.
     *
     * @return Kafka client
     */
    public static MixedOperation<Kafka, KafkaList, Resource<Kafka>> kafkaClient() {
        return Crds.kafkaOperation(KubeResourceManager.get().kubeClient().getClient());
    }

    @Override
    public Long getTimeoutForResourceReadiness() {
        return Constants.KAFKA_READY_TIMEOUT_MS;
    }

    @Override
    public NonNamespaceOperation<?, ?, ?> getClient() {
        return client;
    }

    @Override
    public String getKind() {
        return Kafka.RESOURCE_KIND;
    }

    @Override
    public void create(final Kafka kafka) {
        client.inNamespace(kafka.getMetadata().getNamespace()).resource(kafka).create();
    }

    @Override
    public void update(final Kafka kafka) {
        client.inNamespace(kafka.getMetadata().getNamespace()).resource(kafka).update();
    }

    @Override
    public void delete(final Kafka kafka) {
        String namespace = kafka.getMetadata().getNamespace();
        String name = kafka.getMetadata().getName();

        // Delete KafkaNodePools attached to this cluster first to prevent hanging PVCs
        List<KafkaNodePool> nodePools = KafkaNodePoolType.kafkaNodePoolClient()
            .inNamespace(namespace)
            .withLabelSelector(new LabelSelectorBuilder()
                .withMatchLabels(Map.of(ResourceLabels.STRIMZI_CLUSTER_LABEL, name))
                .build())
            .list()
            .getItems();

        if (!nodePools.isEmpty()) {
            LOGGER.info("Deleting {} KafkaNodePools for Kafka {}/{}", nodePools.size(), namespace, name);
            KubeResourceManager.get().deleteResourceWithWait(nodePools.toArray(new KafkaNodePool[0]));
        }

        Kafka current = client.inNamespace(namespace).withName(name).get();
        if (current != null) {
            client.inNamespace(namespace).withName(name)
                .withPropagationPolicy(DeletionPropagation.BACKGROUND).delete();
        }
    }

    @Override
    public void replace(final Kafka kafka, final Consumer<Kafka> consumer) {
        Kafka toBeReplaced = client.inNamespace(kafka.getMetadata().getNamespace())
            .withName(kafka.getMetadata().getName()).get();
        consumer.accept(toBeReplaced);
        update(toBeReplaced);
    }

    @Override
    public boolean isReady(final Kafka resource) {
        return ResourceConditions.<Kafka>resourceIsReady().predicate().test(resource);
    }

    @Override
    public boolean isDeleted(final Kafka kafka) {
        return client.inNamespace(kafka.getMetadata().getNamespace()).withName(kafka.getMetadata().getName()).get() == null;
    }
}
