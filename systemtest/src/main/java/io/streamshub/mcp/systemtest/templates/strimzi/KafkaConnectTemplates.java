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
import io.strimzi.api.kafka.model.connect.build.JarArtifactBuilder;
import io.strimzi.api.kafka.model.connect.build.Plugin;
import io.strimzi.api.kafka.model.connect.build.PluginBuilder;
import io.strimzi.api.kafka.model.kafka.KafkaResources;

import static io.streamshub.mcp.systemtest.setup.mcp.ConnectivitySetup.isOpenShift;
import static io.streamshub.mcp.systemtest.templates.strimzi.KafkaConnectorTemplates.CONNECTOR_NAME;
import static io.streamshub.mcp.systemtest.templates.strimzi.KafkaTemplates.DEFAULT_KAFKA_VERSION;

/**
 * Template builders for KafkaConnect custom resources with sensible defaults.
 */
public final class KafkaConnectTemplates {

    /** Default number of Connect replicas. */
    private static final int DEFAULT_REPLICAS = 1;

    /**
     * Constants for KafkaConnect EchoSink plugin
     */
    private static final String ECHO_SINK_CONNECTOR_NAME = "echo-sink-connector";
    /** Fully qualified class name for the EchoSink connector. */
    public static final String ECHO_SINK_CLASS_NAME = "cz.scholz.kafka.connect.echosink.EchoSinkConnector";
    private static final String ECHO_SINK_TGZ_URL = "https://github.com/scholzj/echo-sink/archive/1.6.0.tar.gz";
    private static final String ECHO_SINK_TGZ_CHECKSUM = "19b8d501ce0627cff2770ee489e59c205ac81263e771aa11b5848c2c289d917cda22f1fc7fc693a91bad63181787d7c48791796f1a33f8f75d594aefebf1e684";
    private static final String ECHO_SINK_JAR_URL = "https://github.com/scholzj/echo-sink/releases/download/1.6.0/echo-sink-1.6.0.jar";
    private static final String ECHO_SINK_JAR_CHECKSUM = "3f30d48079578f9f2d0a097ed9a7088773b135dff3dc8e70d87f8422c073adc1181cb41d823c1d1472b0447a337e4877e535daa34ca8ef21d608f8ee6f5e4a9c";
    private static final String ECHO_SINK_FILE_NAME = "echo-sink-test.jar";
    private static final String ECHO_SINK_JAR_WRONG_CHECKSUM = "f1f167902325062efc8c755647bc1b782b2b067a87a6e507ff7a3f6205803220";

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
            .withName(CONNECTOR_NAME)
            .withArtifacts(
                new JarArtifactBuilder()
                    .withUrl(ECHO_SINK_JAR_URL)
                    .withSha512sum(ECHO_SINK_JAR_CHECKSUM)
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
                .addToConfig("config.storage.replication.factor", "-1")
                .addToConfig("offset.storage.replication.factor", "-1")
                .addToConfig("status.storage.replication.factor", "-1")
                .withNewInlineLogging()
                    .addToLoggers("rootLogger.level", "DEBUG")
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
