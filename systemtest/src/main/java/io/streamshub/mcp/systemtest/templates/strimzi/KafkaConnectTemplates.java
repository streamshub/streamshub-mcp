/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.systemtest.templates.strimzi;

import io.streamshub.mcp.systemtest.setup.mcp.ConnectivitySetup;
import io.strimzi.api.kafka.model.common.CertSecretSourceBuilder;
import io.strimzi.api.kafka.model.connect.KafkaConnectBuilder;
import io.strimzi.api.kafka.model.connect.KafkaConnectResources;
import io.strimzi.api.kafka.model.connect.build.DockerOutput;
import io.strimzi.api.kafka.model.connect.build.DockerOutputBuilder;
import io.strimzi.api.kafka.model.connect.build.Plugin;
import io.strimzi.api.kafka.model.connect.build.PluginBuilder;
import io.strimzi.api.kafka.model.connect.build.TgzArtifactBuilder;
import io.strimzi.api.kafka.model.kafka.KafkaResources;

import static io.streamshub.mcp.systemtest.setup.mcp.ConnectivitySetup.isOpenShift;
import static io.streamshub.mcp.systemtest.templates.strimzi.KafkaTemplates.DEFAULT_KAFKA_VERSION;

/**
 * Template builders for KafkaConnect custom resources with sensible defaults.
 */
public final class KafkaConnectTemplates {

    /** Default number of Connect replicas. */
    private static final int DEFAULT_REPLICAS = 1;

    /** Fully qualified class name for the Camel Timer Source connector. */
    public static final String CAMEL_TIMER_SOURCE_CLASS_NAME =
        "org.apache.camel.kafkaconnector.timersource.CamelTimersourceSourceConnector";

    private static final String CAMEL_TIMER_SOURCE_PLUGIN_NAME = "camel-timer-source";
    private static final String CAMEL_TIMER_SOURCE_TGZ_URL =
        "https://repo.maven.apache.org/maven2/org/apache/camel/kafkaconnector/"
            + "camel-timer-source-kafka-connector/4.18.0/"
            + "camel-timer-source-kafka-connector-4.18.0-package.tar.gz";
    private static final String CAMEL_TIMER_SOURCE_TGZ_SHA512 =
        "79fd69db4a99911d952630cd14cee4e2447c2dad1c47d59910c71218701535c09be4171ebd76f689695892b711a71553"
            + "bb91ef6b33c52ee795a7c173a0f3a69e";

    private KafkaConnectTemplates() {
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

        Plugin plugin = new PluginBuilder()
            .withName(CAMEL_TIMER_SOURCE_PLUGIN_NAME)
            .withArtifacts(
                new TgzArtifactBuilder()
                    .withUrl(CAMEL_TIMER_SOURCE_TGZ_URL)
                    .withSha512sum(CAMEL_TIMER_SOURCE_TGZ_SHA512)
                    .build())
            .build();

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
                .withNewBuild()
                    .withPlugins(plugin)
                    .withOutput(KafkaConnectTemplates.dockerOutput(ConnectivitySetup.getImageOutputRegistry() + "/" + namespace + "/strimzi-sts-connect-build:dev"))
                .endBuild()

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
                // Maybe not needed?
                .addToConfig("key.converter", "org.apache.kafka.connect.storage.StringConverter")
                .addToConfig("value.converter", "org.apache.kafka.connect.storage.StringConverter")
                .addToConfig("config.storage.replication.factor", "-1")
                .addToConfig("offset.storage.replication.factor", "-1")
                .addToConfig("status.storage.replication.factor", "-1")
                .withNewInlineLogging()
                    .addToLoggers("rootLogger.level", "INFO")
                .endInlineLogging()
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
     * Create a DockerOutput configuration, with TLS verification disabled on non-OpenShift clusters.
     *
     * @param imageName the target image name
     * @return a configured DockerOutput
     */
    public static DockerOutput dockerOutput(final String imageName) {
        DockerOutputBuilder dockerOutputBuilder = new DockerOutputBuilder().withImage(imageName);

        if (!isOpenShift()) {
            // for Buildah on minikube or Kind, we need to add `--tls-verify=false` in order to push via HTTP
            dockerOutputBuilder.withAdditionalBuildOptions("--tls-verify=false");
            dockerOutputBuilder.withAdditionalPushOptions("--tls-verify=false");
            dockerOutputBuilder.withAdditionalBuildOptions(
                // --insecure for PUSH via HTTP instead of HTTPS
                "--insecure");
        }

        return dockerOutputBuilder.build();
    }
}
