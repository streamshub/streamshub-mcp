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
import io.strimzi.api.kafka.model.bridge.KafkaBridgeBuilder;
import io.strimzi.api.kafka.model.common.CertSecretSourceBuilder;
import io.strimzi.api.kafka.model.common.ExternalConfigurationReferenceBuilder;
import io.strimzi.api.kafka.model.common.metrics.JmxPrometheusExporterMetricsBuilder;
import io.strimzi.api.kafka.model.kafka.KafkaResources;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Template builders for KafkaBridge custom resources with sensible defaults.
 */
public final class KafkaBridgeTemplates {

    private static final Logger LOGGER = LoggerFactory.getLogger(KafkaBridgeTemplates.class);

    /** Default number of bridge replicas. */
    private static final int DEFAULT_REPLICAS = 1;

    /** Default HTTP port for the bridge. */
    private static final int DEFAULT_HTTP_PORT = 8080;

    private static final String METRICS_CONFIG_MAP_NAME = "bridge-metrics";
    private static final Path BRIDGE_FILE =
        Path.of(Constants.STRIMZI_MANIFESTS_DIR, "kafka-bridge", "020-KafkaBridge.yaml");
    private static final Path POD_MONITOR_FILE =
        Path.of(Constants.STRIMZI_MANIFESTS_DIR, "kafka-bridge", "040-PodMonitor.yaml");

    private KafkaBridgeTemplates() {
    }

    /**
     * Deploy the bridge metrics ConfigMap from the dev manifests.
     *
     * @param namespace the target namespace
     */
    public static void deployMetricsConfigMap(final String namespace) {
        try {
            List<HasMetadata> resources = KubeResourceManager.get().readResourcesFromFile(BRIDGE_FILE);
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
            LOGGER.warn("Could not load bridge metrics ConfigMap from {}, skipping", BRIDGE_FILE, e);
        }
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
                .withMetricsConfig(new JmxPrometheusExporterMetricsBuilder()
                    .withValueFrom(new ExternalConfigurationReferenceBuilder()
                        .withNewConfigMapKeyRef("metrics-config.yml", METRICS_CONFIG_MAP_NAME, false)
                        .build())
                    .build())
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

    /**
     * Deploy PodMonitors for KafkaBridge from the dev manifests.
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
            LOGGER.info("Deployed {} bridge PodMonitor(s) into namespace {}", resources.size(), namespace);
        } catch (IOException e) {
            LOGGER.warn("Could not load bridge PodMonitors from {}, skipping", POD_MONITOR_FILE, e);
        }
    }
}
