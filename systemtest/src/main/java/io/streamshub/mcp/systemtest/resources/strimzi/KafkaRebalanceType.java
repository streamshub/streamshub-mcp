/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.systemtest.resources.strimzi;

import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.NonNamespaceOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.skodjob.kubetest4j.interfaces.ResourceType;
import io.skodjob.kubetest4j.resources.KubeResourceManager;
import io.streamshub.mcp.systemtest.Constants;
import io.streamshub.mcp.systemtest.resources.ResourceConditions;
import io.strimzi.api.kafka.Crds;
import io.strimzi.api.kafka.model.rebalance.KafkaRebalance;
import io.strimzi.api.kafka.model.rebalance.KafkaRebalanceList;

import java.util.function.Consumer;

/**
 * kubetest4j resource type for KafkaRebalance custom resources.
 */
public class KafkaRebalanceType implements ResourceType<KafkaRebalance> {

    private final MixedOperation<KafkaRebalance, KafkaRebalanceList, Resource<KafkaRebalance>> client;

    /**
     * Create a new KafkaRebalance resource type.
     */
    public KafkaRebalanceType() {
        client = kafkaRebalanceClient();
    }

    /**
     * Get a Fabric8 client for KafkaRebalance custom resources.
     *
     * @return KafkaRebalance client
     */
    public static MixedOperation<KafkaRebalance, KafkaRebalanceList, Resource<KafkaRebalance>>
        kafkaRebalanceClient() {
        return Crds.kafkaRebalanceOperation(KubeResourceManager.get().kubeClient().getClient());
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
        return KafkaRebalance.RESOURCE_KIND;
    }

    @Override
    public void create(final KafkaRebalance resource) {
        client.inNamespace(resource.getMetadata().getNamespace()).resource(resource).create();
    }

    @Override
    public void update(final KafkaRebalance resource) {
        client.inNamespace(resource.getMetadata().getNamespace()).resource(resource).update();
    }

    @Override
    public void delete(final KafkaRebalance resource) {
        client.inNamespace(resource.getMetadata().getNamespace())
            .withName(resource.getMetadata().getName()).delete();
    }

    @Override
    public void replace(final KafkaRebalance resource, final Consumer<KafkaRebalance> consumer) {
        KafkaRebalance current = client.inNamespace(resource.getMetadata().getNamespace())
            .withName(resource.getMetadata().getName()).get();
        consumer.accept(current);
        update(current);
    }

    @Override
    public boolean isReady(final KafkaRebalance resource) {
        return ResourceConditions.<KafkaRebalance>resourceIsReady().predicate().test(resource);
    }

    @Override
    public boolean isDeleted(final KafkaRebalance resource) {
        return resource == null;
    }
}
