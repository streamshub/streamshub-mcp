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
import io.strimzi.api.kafka.model.connector.KafkaConnector;
import io.strimzi.api.kafka.model.connector.KafkaConnectorList;

import java.util.function.Consumer;

/**
 * kubetest4j resource type for KafkaConnector custom resources.
 */
public class KafkaConnectorType implements ResourceType<KafkaConnector> {

    private final MixedOperation<KafkaConnector, KafkaConnectorList, Resource<KafkaConnector>> client;

    /**
     * Create a new KafkaConnector resource type.
     */
    public KafkaConnectorType() {
        client = kafkaConnectorClient();
    }

    /**
     * Get a Fabric8 client for KafkaConnector custom resources.
     *
     * @return KafkaConnector client
     */
    public static MixedOperation<KafkaConnector, KafkaConnectorList, Resource<KafkaConnector>> kafkaConnectorClient() {
        return Crds.kafkaConnectorOperation(KubeResourceManager.get().kubeClient().getClient());
    }

    @Override
    public Long getTimeoutForResourceReadiness() {
        return Constants.KAFKA_CONNECTOR_READY_TIMEOUT_MS;
    }

    @Override
    public NonNamespaceOperation<?, ?, ?> getClient() {
        return client;
    }

    @Override
    public String getKind() {
        return KafkaConnector.RESOURCE_KIND;
    }

    @Override
    public void create(final KafkaConnector resource) {
        client.inNamespace(resource.getMetadata().getNamespace()).resource(resource).create();
    }

    @Override
    public void update(final KafkaConnector resource) {
        client.inNamespace(resource.getMetadata().getNamespace()).resource(resource).update();
    }

    @Override
    public void delete(final KafkaConnector resource) {
        client.inNamespace(resource.getMetadata().getNamespace())
            .withName(resource.getMetadata().getName()).delete();
    }

    @Override
    public void replace(final KafkaConnector resource, final Consumer<KafkaConnector> consumer) {
        KafkaConnector current = client.inNamespace(resource.getMetadata().getNamespace())
            .withName(resource.getMetadata().getName()).get();
        consumer.accept(current);
        update(current);
    }

    @Override
    public boolean isReady(final KafkaConnector resource) {
        return ResourceConditions.<KafkaConnector>resourceIsReady().predicate().test(resource);
    }

    @Override
    public boolean isDeleted(final KafkaConnector resource) {
        return resource == null;
    }
}
