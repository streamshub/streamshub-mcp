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
import io.strimzi.api.kafka.model.common.ExternalConfigurationReferenceBuilder;
import io.strimzi.api.kafka.model.common.metrics.JmxPrometheusExporterMetricsBuilder;
import io.strimzi.api.kafka.model.kafka.KafkaBuilder;
import io.strimzi.api.kafka.model.kafka.cruisecontrol.KafkaAutoRebalanceConfigurationBuilder;
import io.strimzi.api.kafka.model.kafka.cruisecontrol.KafkaAutoRebalanceMode;
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

    private static final Path POD_MONITOR_FILE =
        Path.of(Constants.STRIMZI_MANIFESTS_DIR, "kafka", "020-PodMonitor.yaml");

    /** Default Kafka version for test clusters. */
    public static final String DEFAULT_KAFKA_VERSION = "4.2.0";

    /** Maximum replication factor for Kafka internal topics. */
    private static final int MAX_REPLICATION_FACTOR = 3;

    /** Maximum min.insync.replicas value. */
    private static final int MAX_MIN_ISR = 2;

    private static final String METRICS_CONFIG_MAP_NAME = "kafka-metrics";

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
                    .addToConfig("min.insync.replicas", Math.clamp(replicas - 1, 1, MAX_MIN_ISR))
                .endKafka()
                .editEntityOperator()
                    .editOrNewUserOperator()
                    .endUserOperator()
                    .editOrNewTopicOperator()
                    .endTopicOperator()
                .endEntityOperator()
            .endSpec();
    }

    /**
     * Create a Kafka builder with CruiseControl and auto-rebalance enabled.
     * Configures auto-rebalance for both add-brokers and remove-brokers modes.
     *
     * @param namespace    the namespace
     * @param name         the cluster name
     * @param replicas     the number of broker replicas
     * @return a pre-configured KafkaBuilder with CruiseControl
     */
    public static KafkaBuilder kafkaWithCruiseControl(final String namespace, final String name,
                                                       final int replicas) {
        return kafka(namespace, name, replicas)
            .editSpec()
                .withNewCruiseControl()
                    .withAutoRebalance(
                        new KafkaAutoRebalanceConfigurationBuilder()
                            .withMode(KafkaAutoRebalanceMode.ADD_BROKERS)
                            .build(),
                        new KafkaAutoRebalanceConfigurationBuilder()
                            .withMode(KafkaAutoRebalanceMode.REMOVE_BROKERS)
                            .build())
                .endCruiseControl()
            .endSpec();
    }

    /**
     * Create a Kafka builder with JMX Prometheus exporter metrics enabled.
     * Requires the {@code kafka-metrics} ConfigMap to be deployed via {@link #deployMetricsConfigMap(String)}.
     *
     * @param namespace    the namespace
     * @param name         the cluster name
     * @param replicas     the number of broker replicas
     * @return a pre-configured KafkaBuilder with metricsConfig
     */
    public static KafkaBuilder kafkaWithMetrics(final String namespace, final String name,
                                                final int replicas) {
        return kafka(namespace, name, replicas)
            .editSpec()
                .editKafka()
                    .withMetricsConfig(new JmxPrometheusExporterMetricsBuilder()
                        .withValueFrom(new ExternalConfigurationReferenceBuilder()
                            .withNewConfigMapKeyRef("kafka-metrics-config.yml", METRICS_CONFIG_MAP_NAME, false)
                            .build())
                        .build())
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

    /**
     * Deploy PodMonitors from {@code dev/manifests/strimzi/kafka/020-PodMonitor.yaml}.
     * These tell Prometheus to scrape Kafka broker and entity-operator pods for metrics.
     *
     * @param namespace the target namespace
     */
    public static void deployPodMonitors(final String namespace) {
        try {
            List<HasMetadata> resources = KubeResourceManager.get().readResourcesFromFile(POD_MONITOR_FILE);
            for (HasMetadata resource : resources) {
                resource.getMetadata().setNamespace(namespace);
                KubeResourceManager.get().createOrUpdateResourceWithoutWait(resource);
            }
            LOGGER.info("Deployed {} PodMonitor(s) into namespace {}", resources.size(), namespace);
        } catch (IOException e) {
            LOGGER.warn("Could not load PodMonitors from {}, skipping", POD_MONITOR_FILE, e);
        }
    }
}
