/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.systemtest.templates.strimzi;

import io.strimzi.api.kafka.model.bridge.KafkaBridgeBuilder;
import io.strimzi.api.kafka.model.common.CertSecretSourceBuilder;
import io.strimzi.api.kafka.model.kafka.KafkaResources;

/**
 * Template builders for KafkaBridge custom resources with sensible defaults.
 */
public final class KafkaBridgeTemplates {

    /** Default number of bridge replicas. */
    private static final int DEFAULT_REPLICAS = 1;

    /** Default HTTP port for the bridge. */
    private static final int DEFAULT_HTTP_PORT = 8080;

    private KafkaBridgeTemplates() {
    }

    /**
     * Create a KafkaBridge builder with standard defaults.
     *
     * @param namespace        the namespace
     * @param name             the KafkaBridge name
     * @param kafkaClusterName the Kafka cluster name for bootstrap servers
     * @param replicas         the number of bridge replicas
     * @return a pre-configured KafkaBridgeBuilder
     */
    public static KafkaBridgeBuilder kafkaBridge(final String namespace, final String name,
                                                  final String kafkaClusterName, final int replicas) {
        return new KafkaBridgeBuilder()
            .withNewMetadata()
                .withName(name)
                .withNamespace(namespace)
            .endMetadata()
            .withNewSpec()
                .withReplicas(replicas)
                .withBootstrapServers(KafkaResources.tlsBootstrapAddress(kafkaClusterName))
                .withNewHttp()
                    .withPort(DEFAULT_HTTP_PORT)
                .endHttp()
                .withNewTls()
                    .withTrustedCertificates(
                        new CertSecretSourceBuilder()
                            .withSecretName(KafkaResources.clusterCaCertificateSecretName(kafkaClusterName))
                            .withCertificate("ca.crt")
                            .build())
                .endTls()
                .withNewInlineLogging()
                    .addToLoggers("rootLogger.level", "INFO")
                .endInlineLogging()
            .endSpec();
    }

    /**
     * Create a minimal KafkaBridge builder with default replicas.
     *
     * @param namespace        the namespace
     * @param name             the KafkaBridge name
     * @param kafkaClusterName the Kafka cluster name for bootstrap servers
     * @return a pre-configured KafkaBridgeBuilder
     */
    public static KafkaBridgeBuilder kafkaBridge(final String namespace, final String name,
                                                  final String kafkaClusterName) {
        return kafkaBridge(namespace, name, kafkaClusterName, DEFAULT_REPLICAS);
    }
}
