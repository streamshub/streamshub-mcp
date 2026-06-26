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
import io.strimzi.api.kafka.model.mirrormaker2.KafkaMirrorMaker2;
import io.strimzi.api.kafka.model.mirrormaker2.KafkaMirrorMaker2List;

import java.util.function.Consumer;

/**
 * kubetest4j resource type for KafkaMirrorMaker2 custom resources.
 */
public class KafkaMirrorMaker2Type implements ResourceType<KafkaMirrorMaker2> {

    private final MixedOperation<KafkaMirrorMaker2, KafkaMirrorMaker2List,
        Resource<KafkaMirrorMaker2>> client;

    /**
     * Create a new KafkaMirrorMaker2 resource type.
     */
    public KafkaMirrorMaker2Type() {
        client = kafkaMirrorMaker2Client();
    }

    /**
     * Get a Fabric8 client for KafkaMirrorMaker2 custom resources.
     *
     * @return KafkaMirrorMaker2 client
     */
    public static MixedOperation<KafkaMirrorMaker2, KafkaMirrorMaker2List,
            Resource<KafkaMirrorMaker2>> kafkaMirrorMaker2Client() {
        return Crds.kafkaMirrorMaker2Operation(KubeResourceManager.get().kubeClient().getClient());
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
        return KafkaMirrorMaker2.RESOURCE_KIND;
    }

    @Override
    public void create(final KafkaMirrorMaker2 resource) {
        client.inNamespace(resource.getMetadata().getNamespace()).resource(resource).create();
    }

    @Override
    public void update(final KafkaMirrorMaker2 resource) {
        client.inNamespace(resource.getMetadata().getNamespace()).resource(resource).update();
    }

    @Override
    public void delete(final KafkaMirrorMaker2 resource) {
        client.inNamespace(resource.getMetadata().getNamespace())
            .withName(resource.getMetadata().getName()).delete();
    }

    @Override
    public void replace(final KafkaMirrorMaker2 resource,
                         final Consumer<KafkaMirrorMaker2> consumer) {
        KafkaMirrorMaker2 current = client.inNamespace(resource.getMetadata().getNamespace())
            .withName(resource.getMetadata().getName()).get();
        consumer.accept(current);
        update(current);
    }

    @Override
    public boolean isReady(final KafkaMirrorMaker2 resource) {
        return ResourceConditions.<KafkaMirrorMaker2>resourceIsReady().predicate().test(resource);
    }

    @Override
    public boolean isDeleted(final KafkaMirrorMaker2 resource) {
        return resource == null;
    }
}
