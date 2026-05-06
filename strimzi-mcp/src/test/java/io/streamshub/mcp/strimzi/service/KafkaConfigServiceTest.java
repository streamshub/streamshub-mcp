/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.service;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.ResourceRequirementsBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.NonNamespaceOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.quarkiverse.mcp.server.ToolCallException;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.streamshub.mcp.strimzi.dto.KafkaEffectiveConfigResponse;
import io.strimzi.api.kafka.model.common.ExternalConfigurationReferenceBuilder;
import io.strimzi.api.kafka.model.common.InlineLoggingBuilder;
import io.strimzi.api.kafka.model.common.JvmOptionsBuilder;
import io.strimzi.api.kafka.model.common.TopologyLabelRackBuilder;
import io.strimzi.api.kafka.model.common.metrics.JmxPrometheusExporterMetricsBuilder;
import io.strimzi.api.kafka.model.kafka.Kafka;
import io.strimzi.api.kafka.model.kafka.KafkaAuthorizationSimple;
import io.strimzi.api.kafka.model.kafka.KafkaBuilder;
import io.strimzi.api.kafka.model.kafka.cruisecontrol.CruiseControlSpecBuilder;
import io.strimzi.api.kafka.model.kafka.entityoperator.EntityOperatorSpecBuilder;
import io.strimzi.api.kafka.model.kafka.entityoperator.EntityTopicOperatorSpecBuilder;
import io.strimzi.api.kafka.model.kafka.entityoperator.EntityUserOperatorSpecBuilder;
import io.strimzi.api.kafka.model.kafka.exporter.KafkaExporterSpecBuilder;
import io.strimzi.api.kafka.model.kafka.listener.GenericKafkaListenerBuilder;
import io.strimzi.api.kafka.model.kafka.listener.KafkaListenerAuthenticationScramSha512;
import io.strimzi.api.kafka.model.kafka.listener.KafkaListenerType;
import io.strimzi.api.kafka.model.nodepool.KafkaNodePool;
import io.strimzi.api.kafka.model.nodepool.KafkaNodePoolList;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link KafkaConfigService}.
 */
@QuarkusTest
class KafkaConfigServiceTest {

    @InjectMock
    KubernetesClient kubernetesClient;

    @Inject
    KafkaConfigService kafkaConfigService;

    KafkaConfigServiceTest() {
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        KubernetesMockHelper.setupEmptyResourceQuery(kubernetesClient, Kafka.class);
        setupEmptyNodePools("kafka");
    }

    @Test
    void testThrowsWhenClusterNameMissing() {
        assertThrows(ToolCallException.class, () ->
            kafkaConfigService.getEffectiveConfig("kafka", null));
    }

    @Test
    void testThrowsWhenClusterNotFound() {
        assertThrows(ToolCallException.class, () ->
            kafkaConfigService.getEffectiveConfig("kafka", "missing-cluster"));
    }

    @Test
    void testBasicConfigExtraction() {
        Kafka kafka = new KafkaBuilder()
            .withMetadata(new ObjectMetaBuilder().withName("my-cluster").withNamespace("kafka").build())
            .withNewSpec()
                .withNewKafka()
                    .withVersion("3.9.0")
                    .withMetadataVersion("3.9-IV0")
                    .withConfig(Map.of("log.retention.hours", 168, "num.partitions", 3))
                .endKafka()
            .endSpec()
            .build();
        setupKafkaResource(kafka, "kafka", "my-cluster");

        KafkaEffectiveConfigResponse response = kafkaConfigService.getEffectiveConfig("kafka", "my-cluster");

        assertNotNull(response);
        assertEquals("my-cluster", response.name());
        assertEquals("kafka", response.namespace());
        assertEquals("3.9.0", response.kafkaVersion());
        assertEquals("3.9-IV0", response.metadataVersion());
        assertNotNull(response.brokerConfig());
        assertEquals(168, response.brokerConfig().get("log.retention.hours"));
        assertEquals(3, response.brokerConfig().get("num.partitions"));
    }

    @Test
    void testKafkaLevelResourcesOmitted() {
        Kafka kafka = new KafkaBuilder()
            .withMetadata(new ObjectMetaBuilder().withName("my-cluster").withNamespace("kafka").build())
            .withNewSpec()
                .withNewKafka()
                    .withResources(new ResourceRequirementsBuilder()
                        .addToRequests("cpu", new Quantity("500m"))
                        .addToRequests("memory", new Quantity("2Gi"))
                        .build())
                .endKafka()
            .endSpec()
            .build();
        setupKafkaResource(kafka, "kafka", "my-cluster");

        KafkaEffectiveConfigResponse response = kafkaConfigService.getEffectiveConfig("kafka", "my-cluster");

        assertNotNull(response);
    }

    @Test
    void testListenerConfigWithTlsAndAuth() {
        Kafka kafka = new KafkaBuilder()
            .withMetadata(new ObjectMetaBuilder().withName("my-cluster").withNamespace("kafka").build())
            .withNewSpec()
                .withNewKafka()
                    .withListeners(List.of(
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
                            .withAuth(new KafkaListenerAuthenticationScramSha512())
                            .build()
                    ))
                .endKafka()
            .endSpec()
            .build();
        setupKafkaResource(kafka, "kafka", "my-cluster");

        KafkaEffectiveConfigResponse response = kafkaConfigService.getEffectiveConfig("kafka", "my-cluster");

        assertNotNull(response.listeners());
        assertEquals(2, response.listeners().size());

        assertEquals("plain", response.listeners().get(0).name());
        assertEquals(9092, response.listeners().get(0).port());
        assertEquals("internal", response.listeners().get(0).type());
        assertEquals(false, response.listeners().get(0).tls());
        assertNull(response.listeners().get(0).authType());

        assertEquals("tls", response.listeners().get(1).name());
        assertEquals(true, response.listeners().get(1).tls());
        assertEquals("scram-sha-512", response.listeners().get(1).authType());
    }

    @Test
    void testAuthorizationExtraction() {
        Kafka kafka = new KafkaBuilder()
            .withMetadata(new ObjectMetaBuilder().withName("my-cluster").withNamespace("kafka").build())
            .withNewSpec()
                .withNewKafka()
                    .withAuthorization(new KafkaAuthorizationSimple())
                .endKafka()
            .endSpec()
            .build();
        setupKafkaResource(kafka, "kafka", "my-cluster");

        KafkaEffectiveConfigResponse response = kafkaConfigService.getEffectiveConfig("kafka", "my-cluster");

        assertNotNull(response.authorization());
        assertEquals("simple", response.authorization().type());
    }

    @Test
    void testJvmOptionsExtraction() {
        Kafka kafka = new KafkaBuilder()
            .withMetadata(new ObjectMetaBuilder().withName("my-cluster").withNamespace("kafka").build())
            .withNewSpec()
                .withNewKafka()
                    .withJvmOptions(new JvmOptionsBuilder()
                        .withXms("1g")
                        .withXmx("2g")
                        .addToXx("UseG1GC", "true")
                        .build())
                .endKafka()
            .endSpec()
            .build();
        setupKafkaResource(kafka, "kafka", "my-cluster");

        KafkaEffectiveConfigResponse response = kafkaConfigService.getEffectiveConfig("kafka", "my-cluster");

        assertNotNull(response.jvmOptions());
        assertEquals("1g", response.jvmOptions().xms());
        assertEquals("2g", response.jvmOptions().xmx());
        assertEquals("true", response.jvmOptions().xxOptions().get("UseG1GC"));
    }

    @Test
    void testRackAwarenessExtraction() {
        Kafka kafka = new KafkaBuilder()
            .withMetadata(new ObjectMetaBuilder().withName("my-cluster").withNamespace("kafka").build())
            .withNewSpec()
                .withNewKafka()
                    .withRack(new TopologyLabelRackBuilder().withTopologyKey("topology.kubernetes.io/zone").build())
                .endKafka()
            .endSpec()
            .build();
        setupKafkaResource(kafka, "kafka", "my-cluster");

        KafkaEffectiveConfigResponse response = kafkaConfigService.getEffectiveConfig("kafka", "my-cluster");

        assertNotNull(response.rackAwareness());
        assertEquals("topology.kubernetes.io/zone", response.rackAwareness().topologyKey());
    }

    @Test
    @SuppressWarnings("unchecked")
    void testMetricsConfigWithConfigMapResolution() {
        Kafka kafka = new KafkaBuilder()
            .withMetadata(new ObjectMetaBuilder().withName("my-cluster").withNamespace("kafka").build())
            .withNewSpec()
                .withNewKafka()
                    .withMetricsConfig(new JmxPrometheusExporterMetricsBuilder()
                        .withValueFrom(new ExternalConfigurationReferenceBuilder()
                            .withNewConfigMapKeyRef("kafka-metrics.yml", "metrics-config", false)
                            .build())
                        .build())
                .endKafka()
            .endSpec()
            .build();
        setupKafkaResource(kafka, "kafka", "my-cluster");

        ConfigMap metricsConfigMap = new ConfigMapBuilder()
            .withNewMetadata().withName("metrics-config").withNamespace("kafka").endMetadata()
            .addToData("kafka-metrics.yml", "lowercaseOutputName: true")
            .build();
        setupConfigMap(metricsConfigMap, "kafka", "metrics-config");

        KafkaEffectiveConfigResponse response = kafkaConfigService.getEffectiveConfig("kafka", "my-cluster");

        assertNotNull(response.metricsConfig());
        assertEquals("jmxPrometheusExporter", response.metricsConfig().type());
        assertEquals("metrics-config", response.metricsConfig().configMapName());
        assertEquals("kafka-metrics.yml", response.metricsConfig().configMapKey());
        assertEquals("lowercaseOutputName: true", response.metricsConfig().content());
        assertNull(response.metricsConfig().resolutionNote());
    }

    @Test
    void testMetricsConfigWithMissingConfigMap() {
        Kafka kafka = new KafkaBuilder()
            .withMetadata(new ObjectMetaBuilder().withName("my-cluster").withNamespace("kafka").build())
            .withNewSpec()
                .withNewKafka()
                    .withMetricsConfig(new JmxPrometheusExporterMetricsBuilder()
                        .withValueFrom(new ExternalConfigurationReferenceBuilder()
                            .withNewConfigMapKeyRef("metrics.yml", "missing-cm", false)
                            .build())
                        .build())
                .endKafka()
            .endSpec()
            .build();
        setupKafkaResource(kafka, "kafka", "my-cluster");

        KafkaEffectiveConfigResponse response = kafkaConfigService.getEffectiveConfig("kafka", "my-cluster");

        assertNotNull(response.metricsConfig());
        assertNull(response.metricsConfig().content());
        assertNotNull(response.metricsConfig().resolutionNote());
        assertTrue(response.metricsConfig().resolutionNote().contains("missing-cm"));
    }

    @Test
    void testInlineLoggingExtraction() {
        Kafka kafka = new KafkaBuilder()
            .withMetadata(new ObjectMetaBuilder().withName("my-cluster").withNamespace("kafka").build())
            .withNewSpec()
                .withNewKafka()
                    .withLogging(new InlineLoggingBuilder()
                        .addToLoggers("kafka.root.logger.level", "WARN")
                        .build())
                .endKafka()
            .endSpec()
            .build();
        setupKafkaResource(kafka, "kafka", "my-cluster");

        KafkaEffectiveConfigResponse response = kafkaConfigService.getEffectiveConfig("kafka", "my-cluster");

        assertNotNull(response.logging());
        assertEquals("inline", response.logging().type());
        assertNotNull(response.logging().loggers());
        assertEquals("WARN", response.logging().loggers().get("kafka.root.logger.level"));
    }

    @Test
    void testEntityOperatorExtraction() {
        Kafka kafka = new KafkaBuilder()
            .withMetadata(new ObjectMetaBuilder().withName("my-cluster").withNamespace("kafka").build())
            .withNewSpec()
                .withNewKafka().endKafka()
                .withEntityOperator(new EntityOperatorSpecBuilder()
                    .withTopicOperator(new EntityTopicOperatorSpecBuilder()
                        .withWatchedNamespace("kafka")
                        .withReconciliationIntervalMs(90_000L)
                        .build())
                    .withUserOperator(new EntityUserOperatorSpecBuilder()
                        .withWatchedNamespace("kafka")
                        .build())
                    .build())
            .endSpec()
            .build();
        setupKafkaResource(kafka, "kafka", "my-cluster");

        KafkaEffectiveConfigResponse response = kafkaConfigService.getEffectiveConfig("kafka", "my-cluster");

        assertNotNull(response.entityOperator());
        assertNotNull(response.entityOperator().topicOperator());
        assertEquals("kafka", response.entityOperator().topicOperator().watchedNamespace());
        assertEquals(90, response.entityOperator().topicOperator().reconciliationIntervalSeconds());
        assertNotNull(response.entityOperator().userOperator());
        assertEquals("kafka", response.entityOperator().userOperator().watchedNamespace());
    }

    @Test
    void testCruiseControlExtraction() {
        Kafka kafka = new KafkaBuilder()
            .withMetadata(new ObjectMetaBuilder().withName("my-cluster").withNamespace("kafka").build())
            .withNewSpec()
                .withNewKafka().endKafka()
                .withCruiseControl(new CruiseControlSpecBuilder()
                    .withConfig(Map.of("num.concurrent.partition.movements.per.broker", 5))
                    .withResources(new ResourceRequirementsBuilder()
                        .addToRequests("cpu", new Quantity("500m"))
                        .build())
                    .build())
            .endSpec()
            .build();
        setupKafkaResource(kafka, "kafka", "my-cluster");

        KafkaEffectiveConfigResponse response = kafkaConfigService.getEffectiveConfig("kafka", "my-cluster");

        assertNotNull(response.cruiseControl());
        assertNotNull(response.cruiseControl().config());
        assertEquals(5, response.cruiseControl().config().get("num.concurrent.partition.movements.per.broker"));
        assertNotNull(response.cruiseControl().resources());
        assertEquals("500m", response.cruiseControl().resources().cpuRequest());
    }

    @Test
    void testKafkaExporterExtraction() {
        Kafka kafka = new KafkaBuilder()
            .withMetadata(new ObjectMetaBuilder().withName("my-cluster").withNamespace("kafka").build())
            .withNewSpec()
                .withNewKafka().endKafka()
                .withKafkaExporter(new KafkaExporterSpecBuilder()
                    .withTopicRegex(".*")
                    .withGroupRegex("my-group-.*")
                    .build())
            .endSpec()
            .build();
        setupKafkaResource(kafka, "kafka", "my-cluster");

        KafkaEffectiveConfigResponse response = kafkaConfigService.getEffectiveConfig("kafka", "my-cluster");

        assertNotNull(response.kafkaExporter());
        assertEquals(".*", response.kafkaExporter().topicRegex());
        assertEquals("my-group-.*", response.kafkaExporter().groupRegex());
    }

    // ---- Test helpers ----

    @SuppressWarnings("unchecked")
    private void setupKafkaResource(final Kafka kafka, final String namespace, final String name) {
        MixedOperation kafkaOp = Mockito.mock(MixedOperation.class);
        when(kubernetesClient.resources(Kafka.class)).thenReturn(kafkaOp);

        NonNamespaceOperation nsKafkaOp = Mockito.mock(NonNamespaceOperation.class);
        when(kafkaOp.inNamespace(namespace)).thenReturn(nsKafkaOp);

        Resource kafkaResource = Mockito.mock(Resource.class);
        when(nsKafkaOp.withName(name)).thenReturn(kafkaResource);
        when(kafkaResource.get()).thenReturn(kafka);
    }

    @SuppressWarnings("unchecked")
    private void setupEmptyNodePools(final String namespace) {
        MixedOperation nodePoolOp = Mockito.mock(MixedOperation.class);
        Mockito.lenient().when(kubernetesClient.resources(KafkaNodePool.class)).thenReturn(nodePoolOp);

        NonNamespaceOperation nsNodePoolOp = Mockito.mock(NonNamespaceOperation.class);
        Mockito.lenient().when(nodePoolOp.inNamespace(namespace)).thenReturn(nsNodePoolOp);

        io.fabric8.kubernetes.client.dsl.FilterWatchListDeletable filteredNodePools =
            Mockito.mock(io.fabric8.kubernetes.client.dsl.FilterWatchListDeletable.class);
        Mockito.lenient().when(nsNodePoolOp.withLabel(anyString(), anyString())).thenReturn(filteredNodePools);

        KafkaNodePoolList emptyList = new KafkaNodePoolList();
        emptyList.setItems(List.of());
        Mockito.lenient().when(filteredNodePools.list()).thenReturn(emptyList);
    }

    @SuppressWarnings("unchecked")
    private void setupConfigMap(final ConfigMap configMap, final String namespace, final String name) {
        MixedOperation cmOp = Mockito.mock(MixedOperation.class);
        Mockito.lenient().when(kubernetesClient.resources(ConfigMap.class)).thenReturn(cmOp);

        NonNamespaceOperation nsCmOp = Mockito.mock(NonNamespaceOperation.class);
        Mockito.lenient().when(cmOp.inNamespace(namespace)).thenReturn(nsCmOp);

        Resource cmResource = Mockito.mock(Resource.class);
        Mockito.lenient().when(nsCmOp.withName(name)).thenReturn(cmResource);
        Mockito.lenient().when(cmResource.get()).thenReturn(configMap);
    }
}
