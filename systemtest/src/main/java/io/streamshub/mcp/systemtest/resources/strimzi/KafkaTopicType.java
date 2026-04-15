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
import io.strimzi.api.kafka.model.topic.KafkaTopic;
import io.strimzi.api.kafka.model.topic.KafkaTopicList;

import java.util.function.Consumer;

/**
 * kubetest4j resource type for KafkaTopic custom resources.
 */
public class KafkaTopicType implements ResourceType<KafkaTopic> {

    private final MixedOperation<KafkaTopic, KafkaTopicList, Resource<KafkaTopic>> client;

    /**
     * Create a new KafkaTopic resource type.
     */
    public KafkaTopicType() {
        client = kafkaTopicClient();
    }

    /**
     * Get a Fabric8 client for KafkaTopic custom resources.
     *
     * @return KafkaTopic client
     */
    public static MixedOperation<KafkaTopic, KafkaTopicList, Resource<KafkaTopic>> kafkaTopicClient() {
        return Crds.topicOperation(KubeResourceManager.get().kubeClient().getClient());
    }

    @Override
    public Long getTimeoutForResourceReadiness() {
        return Constants.KAFKA_TOPIC_READY_TIMEOUT_MS;
    }

    @Override
    public NonNamespaceOperation<?, ?, ?> getClient() {
        return client;
    }

    @Override
    public String getKind() {
        return KafkaTopic.RESOURCE_KIND;
    }

    @Override
    public void create(final KafkaTopic resource) {
        client.inNamespace(resource.getMetadata().getNamespace()).resource(resource).create();
    }

    @Override
    public void update(final KafkaTopic resource) {
        client.inNamespace(resource.getMetadata().getNamespace()).resource(resource).update();
    }

    @Override
    public void delete(final KafkaTopic resource) {
        client.inNamespace(resource.getMetadata().getNamespace())
            .withName(resource.getMetadata().getName()).delete();
    }

    @Override
    public void replace(final KafkaTopic resource, final Consumer<KafkaTopic> consumer) {
        KafkaTopic current = client.inNamespace(resource.getMetadata().getNamespace())
            .withName(resource.getMetadata().getName()).get();
        consumer.accept(current);
        update(current);
    }

    @Override
    public boolean isReady(final KafkaTopic resource) {
        return ResourceConditions.<KafkaTopic>resourceIsReady().predicate().test(resource);
    }

    @Override
    public boolean isDeleted(final KafkaTopic resource) {
        return resource == null;
    }
}
