/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.systemtest.templates.strimzi;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.skodjob.kubetest4j.resources.KubeResourceManager;
import io.streamshub.mcp.systemtest.Constants;
import io.strimzi.api.kafka.model.kafka.KafkaBuilder;
import io.strimzi.api.kafka.model.kafka.listener.GenericKafkaListenerBuilder;
import io.strimzi.api.kafka.model.kafka.listener.KafkaListenerType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Template builders for Kafka custom resources with sensible defaults.
 */
public final class KafkaTemplates {

    private static final Logger LOGGER = LoggerFactory.getLogger(KafkaTemplates.class);

    private static final Path KAFKA_FILE =
        Path.of(Constants.STRIMZI_MANIFESTS_DIR, "kafka", "010-Kafka.yaml");

    /** Default Kafka version for test clusters. */
    private static final String DEFAULT_KAFKA_VERSION = "4.2.0";

    /** Maximum replication factor for Kafka internal topics. */
    private static final int MAX_REPLICATION_FACTOR = 3;

    /** Maximum min.insync.replicas value. */
    private static final int MAX_MIN_ISR = 2;

    private KafkaTemplates() {
    }

    /**
     * Create a Kafka builder with standard defaults.
     *
     * @param namespace    the namespace
     * @param name         the cluster name
     * @param replicas     the number of broker replicas
     * @return a pre-configured KafkaBuilder
     */
    public static KafkaBuilder kafka(final String namespace, final String name, final int replicas) {
        return new KafkaBuilder()
            .withNewMetadata()
                .withName(name)
                .withNamespace(namespace)
            .endMetadata()
            .editSpec()
                .editKafka()
                    .withVersion(DEFAULT_KAFKA_VERSION)
                    .withListeners(
                        new GenericKafkaListenerBuilder()
                            .withName("plain")
                            .withPort(9092)
                            .withType(KafkaListenerType.INTERNAL)
                            .withTls(false)
                            .build(),
                        new GenericKafkaListenerBuilder()
                            .withName("tls")
                            .withPort(9093)
                            .withType(KafkaListenerType.INTERNAL)
                            .withTls(true)
                            .build())
                    .addToConfig("offsets.topic.replication.factor", Math.min(replicas, MAX_REPLICATION_FACTOR))
                    .addToConfig("transaction.state.log.replication.factor", Math.min(replicas, MAX_REPLICATION_FACTOR))
                    .addToConfig("transaction.state.log.min.isr", Math.min(replicas, MAX_MIN_ISR))
                    .addToConfig("default.replication.factor", Math.min(replicas, MAX_REPLICATION_FACTOR))
                    .addToConfig("min.insync.replicas", Math.min(Math.max(replicas - 1, 1), MAX_MIN_ISR))
                .endKafka()
            .endSpec();
    }

    /**
     * Create a minimal Kafka builder for CI environments (single replica, relaxed replication).
     *
     * @param namespace the namespace
     * @param name      the cluster name
     * @return a pre-configured KafkaBuilder for minimal deployments
     */
    public static KafkaBuilder kafkaMinimal(final String namespace, final String name) {
        return kafka(namespace, name, 1)
            .editSpec()
                .editKafka()
                    .addToConfig("offsets.topic.replication.factor", 1)
                    .addToConfig("transaction.state.log.replication.factor", 1)
                    .addToConfig("transaction.state.log.min.isr", 1)
                    .addToConfig("default.replication.factor", 1)
                    .addToConfig("min.insync.replicas", 1)
                .endKafka()
            .endSpec();
    }

    /**
     * Deploy the Kafka metrics ConfigMap from {@code dev/manifests/strimzi/kafka/010-Kafka.yaml}.
     * This ConfigMap contains JMX Prometheus exporter configuration used by the Kafka CR's
     * {@code metricsConfig} section.
     *
     * @param namespace the target namespace
     */
    public static void deployMetricsConfigMap(final String namespace) {
        try {
            List<HasMetadata> resources = KubeResourceManager.get().readResourcesFromFile(KAFKA_FILE);
            for (HasMetadata resource : resources) {
                if (resource instanceof ConfigMap cm) {
                    KubeResourceManager.get().createOrUpdateResourceWithoutWait(
                        new ConfigMapBuilder(cm)
                            .editMetadata().withNamespace(namespace).endMetadata()
                            .build());
                }
            }
        } catch (IOException e) {
            LOGGER.warn("Could not load metrics ConfigMap from {}, skipping", KAFKA_FILE, e);
        }
    }
}
