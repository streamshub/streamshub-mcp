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
import io.strimzi.api.kafka.model.connect.KafkaConnect;
import io.strimzi.api.kafka.model.connect.KafkaConnectList;

import java.util.function.Consumer;

/**
 * kubetest4j resource type for KafkaConnect custom resources.
 */
public class KafkaConnectType implements ResourceType<KafkaConnect> {

    private final MixedOperation<KafkaConnect, KafkaConnectList, Resource<KafkaConnect>> client;

    /**
     * Create a new KafkaConnect resource type.
     */
    public KafkaConnectType() {
        client = kafkaConnectClient();
    }

    /**
     * Get a Fabric8 client for KafkaConnect custom resources.
     *
     * @return KafkaConnect client
     */
    public static MixedOperation<KafkaConnect, KafkaConnectList, Resource<KafkaConnect>> kafkaConnectClient() {
        return Crds.kafkaConnectOperation(KubeResourceManager.get().kubeClient().getClient());
    }

    @Override
    public Long getTimeoutForResourceReadiness() {
        return Constants.KAFKA_CONNECT_READY_TIMEOUT_MS;
    }

    @Override
    public NonNamespaceOperation<?, ?, ?> getClient() {
        return client;
    }

    @Override
    public String getKind() {
        return KafkaConnect.RESOURCE_KIND;
    }

    @Override
    public void create(final KafkaConnect resource) {
        client.inNamespace(resource.getMetadata().getNamespace()).resource(resource).create();
    }

    @Override
    public void update(final KafkaConnect resource) {
        client.inNamespace(resource.getMetadata().getNamespace()).resource(resource).update();
    }

    @Override
    public void delete(final KafkaConnect resource) {
        client.inNamespace(resource.getMetadata().getNamespace())
            .withName(resource.getMetadata().getName()).delete();
    }

    @Override
    public void replace(final KafkaConnect resource, final Consumer<KafkaConnect> consumer) {
        KafkaConnect current = client.inNamespace(resource.getMetadata().getNamespace())
            .withName(resource.getMetadata().getName()).get();
        consumer.accept(current);
        update(current);
    }

    @Override
    public boolean isReady(final KafkaConnect resource) {
        return ResourceConditions.<KafkaConnect>resourceIsReady().predicate().test(resource);
    }

    @Override
    public boolean isDeleted(final KafkaConnect resource) {
        return resource == null;
    }
}
