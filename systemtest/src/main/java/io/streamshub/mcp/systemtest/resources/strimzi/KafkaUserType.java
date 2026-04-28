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
import io.strimzi.api.kafka.model.user.KafkaUser;
import io.strimzi.api.kafka.model.user.KafkaUserList;

import java.util.function.Consumer;

/**
 * kubetest4j resource type for KafkaUser custom resources.
 */
public class KafkaUserType implements ResourceType<KafkaUser> {

    private final MixedOperation<KafkaUser, KafkaUserList, Resource<KafkaUser>> client;

    /**
     * Create a new KafkaUser resource type.
     */
    public KafkaUserType() {
        client = kafkaUserClient();
    }

    /**
     * Get a Fabric8 client for KafkaUser custom resources.
     *
     * @return KafkaUser client
     */
    public static MixedOperation<KafkaUser, KafkaUserList, Resource<KafkaUser>> kafkaUserClient() {
        return Crds.kafkaUserOperation(KubeResourceManager.get().kubeClient().getClient());
    }

    @Override
    public Long getTimeoutForResourceReadiness() {
        return Constants.KAFKA_USER_READY_TIMEOUT_MS;
    }

    @Override
    public NonNamespaceOperation<?, ?, ?> getClient() {
        return client;
    }

    @Override
    public String getKind() {
        return KafkaUser.RESOURCE_KIND;
    }

    @Override
    public void create(final KafkaUser resource) {
        client.inNamespace(resource.getMetadata().getNamespace()).resource(resource).create();
    }

    @Override
    public void update(final KafkaUser resource) {
        client.inNamespace(resource.getMetadata().getNamespace()).resource(resource).update();
    }

    @Override
    public void delete(final KafkaUser resource) {
        client.inNamespace(resource.getMetadata().getNamespace())
            .withName(resource.getMetadata().getName()).delete();
    }

    @Override
    public void replace(final KafkaUser resource, final Consumer<KafkaUser> consumer) {
        KafkaUser current = client.inNamespace(resource.getMetadata().getNamespace())
            .withName(resource.getMetadata().getName()).get();
        consumer.accept(current);
        update(current);
    }

    @Override
    public boolean isReady(final KafkaUser resource) {
        return ResourceConditions.<KafkaUser>resourceIsReady().predicate().test(resource);
    }

    @Override
    public boolean isDeleted(final KafkaUser resource) {
        return resource == null;
    }
}
