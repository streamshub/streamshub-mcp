/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.systemtest.templates.strimzi;

import io.strimzi.api.kafka.model.mirrormaker2.KafkaMirrorMaker2Builder;

/**
 * Template builders for KafkaMirrorMaker2 custom resources with sensible defaults.
 */
public final class KafkaMirrorMaker2Templates {

    private KafkaMirrorMaker2Templates() {
    }

    /**
     * Create a KafkaMirrorMaker2 builder with plain (no auth) source and target.
     *
     * @param namespace           the namespace for the MM2 resource
     * @param name                the MM2 name
     * @param sourceBootstrap     the source cluster bootstrap servers
     * @param sourceAlias         the source cluster alias
     * @param targetBootstrap     the target cluster bootstrap servers
     * @param targetAlias         the target cluster alias
     * @param replicas            the number of replicas
     * @return a pre-configured KafkaMirrorMaker2Builder
     */
    public static KafkaMirrorMaker2Builder mirrorMaker2(final String namespace,
                                                         final String name,
                                                         final String sourceBootstrap,
                                                         final String sourceAlias,
                                                         final String targetBootstrap,
                                                         final String targetAlias,
                                                         final int replicas) {
        return new KafkaMirrorMaker2Builder()
            .withNewMetadata()
                .withName(name)
                .withNamespace(namespace)
            .endMetadata()
            .withNewSpec()
                .withVersion(KafkaTemplates.DEFAULT_KAFKA_VERSION)
                .withReplicas(replicas)
                .withNewTarget()
                    .withAlias(targetAlias)
                    .withBootstrapServers(targetBootstrap)
                    .addToConfig("config.storage.replication.factor", 1)
                    .addToConfig("offset.storage.replication.factor", 1)
                    .addToConfig("status.storage.replication.factor", 1)
                .endTarget()
                .addNewMirror()
                    .withNewSource()
                        .withAlias(sourceAlias)
                        .withBootstrapServers(sourceBootstrap)
                    .endSource()
                    .withNewSourceConnector()
                        .withTasksMax(1)
                        .addToConfig("replication.factor", 1)
                        .addToConfig("offset-syncs.topic.replication.factor", 1)
                    .endSourceConnector()
                    .withTopicsPattern(".*")
                    .withTopicsExcludePattern("__.*")
                .endMirror()
            .endSpec();
    }
}
