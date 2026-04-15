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
import io.strimzi.api.kafka.Crds;
import io.strimzi.api.kafka.model.nodepool.KafkaNodePool;
import io.strimzi.api.kafka.model.nodepool.KafkaNodePoolList;

import java.util.function.Consumer;

/**
 * kubetest4j resource type for KafkaNodePool custom resources.
 */
public class KafkaNodePoolType implements ResourceType<KafkaNodePool> {

    private final MixedOperation<KafkaNodePool, KafkaNodePoolList, Resource<KafkaNodePool>> client;

    /**
     * Create a new KafkaNodePool resource type.
     */
    public KafkaNodePoolType() {
        client = kafkaNodePoolClient();
    }

    /**
     * Get a Fabric8 client for KafkaNodePool custom resources.
     *
     * @return KafkaNodePool client
     */
    public static MixedOperation<KafkaNodePool, KafkaNodePoolList, Resource<KafkaNodePool>> kafkaNodePoolClient() {
        return Crds.kafkaNodePoolOperation(KubeResourceManager.get().kubeClient().getClient());
    }

    @Override
    public Long getTimeoutForResourceReadiness() {
        return Constants.KAFKA_NODE_POOL_READY_TIMEOUT_MS;
    }

    @Override
    public NonNamespaceOperation<?, ?, ?> getClient() {
        return client;
    }

    @Override
    public String getKind() {
        return KafkaNodePool.RESOURCE_KIND;
    }

    @Override
    public void create(final KafkaNodePool resource) {
        client.inNamespace(resource.getMetadata().getNamespace()).resource(resource).create();
    }

    @Override
    public void update(final KafkaNodePool resource) {
        client.inNamespace(resource.getMetadata().getNamespace()).resource(resource).update();
    }

    @Override
    public void delete(final KafkaNodePool resource) {
        client.inNamespace(resource.getMetadata().getNamespace())
            .withName(resource.getMetadata().getName()).delete();
    }

    @Override
    public void replace(final KafkaNodePool resource, final Consumer<KafkaNodePool> consumer) {
        KafkaNodePool current = client.inNamespace(resource.getMetadata().getNamespace())
            .withName(resource.getMetadata().getName()).get();
        consumer.accept(current);
        update(current);
    }

    @Override
    public boolean isReady(final KafkaNodePool resource) {
        return resource != null;
    }

    @Override
    public boolean isDeleted(final KafkaNodePool resource) {
        return resource == null;
    }
}
