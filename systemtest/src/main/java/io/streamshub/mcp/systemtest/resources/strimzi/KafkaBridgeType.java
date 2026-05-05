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
import io.strimzi.api.kafka.model.bridge.KafkaBridge;
import io.strimzi.api.kafka.model.bridge.KafkaBridgeList;

import java.util.function.Consumer;

/**
 * kubetest4j resource type for KafkaBridge custom resources.
 */
public class KafkaBridgeType implements ResourceType<KafkaBridge> {

    private final MixedOperation<KafkaBridge, KafkaBridgeList, Resource<KafkaBridge>> client;

    /**
     * Create a new KafkaBridge resource type.
     */
    public KafkaBridgeType() {
        client = kafkaBridgeClient();
    }

    /**
     * Get a Fabric8 client for KafkaBridge custom resources.
     *
     * @return KafkaBridge client
     */
    public static MixedOperation<KafkaBridge, KafkaBridgeList, Resource<KafkaBridge>> kafkaBridgeClient() {
        return Crds.kafkaBridgeOperation(KubeResourceManager.get().kubeClient().getClient());
    }

    @Override
    public Long getTimeoutForResourceReadiness() {
        return Constants.KAFKA_BRIDGE_READY_TIMEOUT_MS;
    }

    @Override
    public NonNamespaceOperation<?, ?, ?> getClient() {
        return client;
    }

    @Override
    public String getKind() {
        return KafkaBridge.RESOURCE_KIND;
    }

    @Override
    public void create(final KafkaBridge resource) {
        client.inNamespace(resource.getMetadata().getNamespace()).resource(resource).create();
    }

    @Override
    public void update(final KafkaBridge resource) {
        client.inNamespace(resource.getMetadata().getNamespace()).resource(resource).update();
    }

    @Override
    public void delete(final KafkaBridge resource) {
        client.inNamespace(resource.getMetadata().getNamespace())
            .withName(resource.getMetadata().getName()).delete();
    }

    @Override
    public void replace(final KafkaBridge resource, final Consumer<KafkaBridge> consumer) {
        KafkaBridge current = client.inNamespace(resource.getMetadata().getNamespace())
            .withName(resource.getMetadata().getName()).get();
        consumer.accept(current);
        update(current);
    }

    @Override
    public boolean isReady(final KafkaBridge resource) {
        return ResourceConditions.<KafkaBridge>resourceIsReady().predicate().test(resource);
    }

    @Override
    public boolean isDeleted(final KafkaBridge resource) {
        return resource == null;
    }
}
