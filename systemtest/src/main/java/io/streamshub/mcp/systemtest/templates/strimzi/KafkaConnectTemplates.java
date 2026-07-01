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
import io.streamshub.mcp.systemtest.Environment;
import io.strimzi.api.kafka.model.common.CertSecretSourceBuilder;
import io.strimzi.api.kafka.model.common.ExternalConfigurationReferenceBuilder;
import io.strimzi.api.kafka.model.common.metrics.JmxPrometheusExporterMetricsBuilder;
import io.strimzi.api.kafka.model.connect.KafkaConnectBuilder;
import io.strimzi.api.kafka.model.connect.KafkaConnectResources;
import io.strimzi.api.kafka.model.kafka.KafkaResources;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static io.streamshub.mcp.systemtest.templates.strimzi.KafkaTemplates.DEFAULT_KAFKA_VERSION;

/**
 * Template builders for KafkaConnect custom resources with sensible defaults.
 */
public final class KafkaConnectTemplates {

    private static final Logger LOGGER = LoggerFactory.getLogger(KafkaConnectTemplates.class);

    /** Default number of Connect replicas. */
    private static final int DEFAULT_REPLICAS = 1;

    /** Fully qualified class name for the Camel Timer Source connector. */
    public static final String CAMEL_TIMER_SOURCE_CLASS_NAME =
        "org.apache.camel.kafkaconnector.timersource.CamelTimersourceSourceConnector";

    private static final String METRICS_CONFIG_MAP_NAME = "connect-metrics";
    private static final Path CONNECT_FILE =
        Path.of(Constants.STRIMZI_MANIFESTS_DIR, "kafka-connect", "020-KafkaConnect.yaml");
    private static final Path POD_MONITOR_FILE =
        Path.of(Constants.STRIMZI_MANIFESTS_DIR, "kafka-connect", "040-PodMonitor.yaml");

    private KafkaConnectTemplates() {
    }

    /**
     * Deploy the connect metrics ConfigMap from the dev manifests.
     *
     * @param namespace the target namespace
     */
    public static void deployMetricsConfigMap(final String namespace) {
        try {
            List<HasMetadata> resources = KubeResourceManager.get().readResourcesFromFile(CONNECT_FILE);
            for (HasMetadata resource : resources) {
                if (resource instanceof ConfigMap cm) {
                    KubeResourceManager.get().createOrUpdateResourceWithoutWait(
                        new ConfigMapBuilder(cm)
                            .editMetadata()
                                .withNamespace(namespace)
                            .endMetadata()
                            .build());
                }
            }
        } catch (IOException e) {
            LOGGER.warn("Could not load connect metrics ConfigMap from {}, skipping", CONNECT_FILE, e);
        }
    }

    /**
     * Create a KafkaConnect builder with standard defaults.
     *
     * @param namespace        the namespace
     * @param name             the KafkaConnect cluster name
     * @param kafkaClusterName the Kafka cluster name for bootstrap servers
     * @param replicas         the number of Connect replicas
     * @return a pre-configured KafkaConnectBuilder
     */
    public static KafkaConnectBuilder kafkaConnect(final String namespace, final String name,
                                                    final String kafkaClusterName, final int replicas) {

        return new KafkaConnectBuilder()
            .withNewMetadata()
                .withName(name)
                .withNamespace(namespace)
                .addToAnnotations("strimzi.io/use-connector-resources", "true")
            .endMetadata()
            .withNewSpec()
                .withVersion(DEFAULT_KAFKA_VERSION)
                .withReplicas(replicas)
                .withBootstrapServers(KafkaResources.tlsBootstrapAddress(kafkaClusterName))
                .withImage(Environment.CONNECT_IMAGE)
                .withNewTls()
                    .withTrustedCertificates(
                        new CertSecretSourceBuilder()
                            .withSecretName(KafkaResources.clusterCaCertificateSecretName(kafkaClusterName))
                            .withCertificate("ca.crt")
                            .build()
                        )
                .endTls()
                .withGroupId(KafkaConnectResources.componentName(name))
                .withConfigStorageTopic(KafkaConnectResources.configMapName(name))
                .withOffsetStorageTopic(KafkaConnectResources.configStorageTopicOffsets(name))
                .withStatusStorageTopic(KafkaConnectResources.configStorageTopicStatus(name))
                .addToConfig("key.converter", "org.apache.kafka.connect.storage.StringConverter")
                .addToConfig("value.converter", "org.apache.kafka.connect.storage.StringConverter")
                .addToConfig("config.storage.replication.factor", "-1")
                .addToConfig("offset.storage.replication.factor", "-1")
                .addToConfig("status.storage.replication.factor", "-1")
                .withNewInlineLogging()
                    .addToLoggers("rootLogger.level", "INFO")
                .endInlineLogging()
                .withMetricsConfig(new JmxPrometheusExporterMetricsBuilder()
                    .withValueFrom(new ExternalConfigurationReferenceBuilder()
                        .withNewConfigMapKeyRef("metrics-config.yml", METRICS_CONFIG_MAP_NAME, false)
                        .build())
                    .build())
            .endSpec();
    }

    /**
     * Create a minimal KafkaConnect builder with default replicas.
     *
     * @param namespace        the namespace
     * @param name             the KafkaConnect cluster name
     * @param kafkaClusterName the Kafka cluster name for bootstrap servers
     * @return a pre-configured KafkaConnectBuilder
     */
    public static KafkaConnectBuilder kafkaConnect(final String namespace, final String name,
                                                    final String kafkaClusterName) {
        return kafkaConnect(namespace, name, kafkaClusterName, DEFAULT_REPLICAS);
    }

    /**
     * Deploy PodMonitors for KafkaConnect from the dev manifests.
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
            LOGGER.info("Deployed {} connect PodMonitor(s) into namespace {}", resources.size(), namespace);
        } catch (IOException e) {
            LOGGER.warn("Could not load connect PodMonitors from {}, skipping", POD_MONITOR_FILE, e);
        }
    }
}
