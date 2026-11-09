/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.service;

import io.fabric8.kubernetes.api.model.EnvVarBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodList;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.api.model.apps.DeploymentList;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.AppsAPIGroupDSL;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.NonNamespaceOperation;
import io.fabric8.kubernetes.client.dsl.PodResource;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.fabric8.kubernetes.client.dsl.RollableScalableResource;
import io.quarkiverse.mcp.server.McpException;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.streamshub.mcp.strimzi.dto.kafka.UpgradePreflightFinding;
import io.streamshub.mcp.strimzi.dto.kafka.UpgradeReadinessReport;
import io.streamshub.mcp.strimzi.service.kafka.UpgradeReadinessDiagnosticService;
import io.strimzi.api.kafka.model.kafka.Kafka;
import io.strimzi.api.kafka.model.kafka.KafkaBuilder;
import io.strimzi.api.kafka.model.nodepool.KafkaNodePool;
import io.strimzi.api.kafka.model.rebalance.KafkaRebalance;
import io.strimzi.api.kafka.model.topic.KafkaTopic;
import io.strimzi.api.kafka.model.topic.KafkaTopicBuilder;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;
/**
 * Tests for {@link UpgradeReadinessDiagnosticService}.
 */
@QuarkusTest
class UpgradeReadinessDiagnosticServiceTest {

    @InjectMock
    KubernetesClient kubernetesClient;

    @Inject
    UpgradeReadinessDiagnosticService upgradeReadinessDiagnosticService;

    UpgradeReadinessDiagnosticServiceTest() {
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        MixedOperation<Pod, PodList, PodResource> podOp = Mockito.mock(MixedOperation.class);
        Mockito.lenient().when(kubernetesClient.pods()).thenReturn(podOp);

        MixedOperation<Deployment, DeploymentList, RollableScalableResource<Deployment>> deploymentOp =
            Mockito.mock(MixedOperation.class);
        AppsAPIGroupDSL appsApi = Mockito.mock(AppsAPIGroupDSL.class);
        Mockito.lenient().when(kubernetesClient.apps()).thenReturn(appsApi);
        Mockito.lenient().when(appsApi.deployments()).thenReturn(deploymentOp);

        KubernetesMockHelper.setupEmptyResourceQuery(kubernetesClient, Kafka.class);
        KubernetesMockHelper.setupEmptyResourceQuery(kubernetesClient, KafkaNodePool.class);
        KubernetesMockHelper.setupEmptyResourceQuery(kubernetesClient, Pod.class);
        KubernetesMockHelper.setupEmptyResourceQuery(kubernetesClient, KafkaRebalance.class);
        KubernetesMockHelper.setupEmptyResourceQuery(kubernetesClient, KafkaTopic.class);
    }

    @Test
    void testThrowsWhenClusterNameMissing() {
        assertThrows(McpException.class, () ->
            upgradeReadinessDiagnosticService.diagnose("kafka", null, null,
                null, null, null, null));
    }

    @Test
    void testThrowsWhenClusterNotFound() {
        assertThrows(McpException.class, () ->
            upgradeReadinessDiagnosticService.diagnose("kafka", "missing-cluster", null,
                null, null, null, null));
    }

    @Test
    void testFullWorkflowWithCluster() {
        setupKafkaCluster("my-cluster", "kafka");

        UpgradeReadinessReport report = upgradeReadinessDiagnosticService.diagnose(
            "kafka", "my-cluster", null,
            null, null, null, null);

        assertNotNull(report);
        assertNotNull(report.cluster());
        assertTrue(report.stepsCompleted().contains("cluster_status"));
        assertNull(report.analysis());
        assertNotNull(report.timestamp());
    }

    @Test
    void testStepFailureDoesNotAbortWorkflow() {
        setupKafkaCluster("my-cluster", "kafka");
        // Node pools, metrics, drain cleaner, certificates, events will fail (no mocks)
        // But the workflow should still complete

        UpgradeReadinessReport report = upgradeReadinessDiagnosticService.diagnose(
            "kafka", "my-cluster", null,
            null, null, null, null);

        assertNotNull(report);
        assertTrue(report.stepsCompleted().contains("cluster_status"));
    }

    @Test
    void testPreflightFindingsReportBlockingTopic() {
        setupKafkaCluster("my-cluster", "kafka");
        KafkaTopic topic = new KafkaTopicBuilder()
            .withNewMetadata().withName("orders").withNamespace("kafka")
                .withLabels(Map.of("strimzi.io/cluster", "my-cluster"))
            .endMetadata()
            .withNewSpec().withPartitions(3).withReplicas(3)
                .withConfig(Map.of("min.insync.replicas", 3))
            .endSpec()
            .build();
        KubernetesMockHelper.setupResourceQuery(kubernetesClient, KafkaTopic.class, List.of(topic));

        UpgradeReadinessReport report = upgradeReadinessDiagnosticService.diagnose(
            "kafka", "my-cluster", "Kafka 4.2.0",
            null, null, null, null);

        assertTrue(report.stepsCompleted().contains("preflight_checks"));
        assertNotNull(report.preflightFindings());
        assertTrue(report.preflightFindings().stream().anyMatch(f ->
            f.severity() == UpgradePreflightFinding.Severity.BLOCKER && "topic_durability".equals(f.check())
                && f.message().contains("orders")));
    }

    @Test
    void testPreflightKeepsTopicFindingsWhenTargetVersionIsMalformed() {
        setupKafkaCluster("my-cluster", "kafka");
        KafkaTopic topic = new KafkaTopicBuilder()
            .withNewMetadata().withName("orders").withNamespace("kafka")
                .withLabels(Map.of("strimzi.io/cluster", "my-cluster"))
            .endMetadata()
            .withNewSpec().withPartitions(3).withReplicas(3)
                .withConfig(Map.of("min.insync.replicas", 3))
            .endSpec()
            .build();
        KubernetesMockHelper.setupResourceQuery(kubernetesClient, KafkaTopic.class, List.of(topic));

        UpgradeReadinessReport report = upgradeReadinessDiagnosticService.diagnose(
            "kafka", "my-cluster", "Kafka 40000000000.0",
            null, null, null, null);

        assertTrue(report.stepsCompleted().contains("preflight_checks"));
        assertTrue(report.stepsFailed() == null || report.stepsFailed().stream()
            .noneMatch(f -> f.startsWith("preflight_checks")));
        assertTrue(report.preflightFindings().stream().anyMatch(f ->
            f.severity() == UpgradePreflightFinding.Severity.BLOCKER && "topic_durability".equals(f.check())
                && f.message().contains("orders")));
    }

    @Test
    void testPreflightFindingsReportUnsupportedTargetFromTheDiscoveredOperator() {
        setupKafkaCluster("my-cluster", "kafka");
        // Two operators: the version check must use the one gathered in Phase 1, not re-discover.
        KubernetesMockHelper.setupResourceQuery(kubernetesClient, Deployment.class, List.of(
            operatorDeployment("strimzi-a", "operators-a", "4.2.0=quay.io/strimzi/kafka:1.3.0-kafka-4.2.0\n"
                + "4.3.0=quay.io/strimzi/kafka:1.3.0-kafka-4.3.0"),
            operatorDeployment("strimzi-b", "operators-b", "4.2.0=quay.io/strimzi/kafka:1.3.0-kafka-4.2.0")));

        UpgradeReadinessReport report = upgradeReadinessDiagnosticService.diagnose(
            "kafka", "my-cluster", "Kafka 4.4.0",
            null, null, null, null);

        assertTrue(report.stepsCompleted().contains("preflight_checks"));
        assertTrue(report.stepsFailed() == null || report.stepsFailed().stream()
            .noneMatch(f -> f.startsWith("operator_supported_versions")));
        assertTrue(report.preflightFindings().stream().anyMatch(f ->
            f.severity() == UpgradePreflightFinding.Severity.BLOCKER && "version_path".equals(f.check())
                && f.message().contains("4.4.0") && f.message().contains("4.2.0, 4.3.0")));
    }

    @Test
    void testPreflightFindingsIgnoreStrimziTarget() {
        setupKafkaCluster("my-cluster", "kafka");
        KubernetesMockHelper.setupResourceQuery(kubernetesClient, Deployment.class, List.of(
            operatorDeployment("strimzi-a", "operators-a", "4.2.0=quay.io/strimzi/kafka:1.3.0-kafka-4.2.0")));

        UpgradeReadinessReport report = upgradeReadinessDiagnosticService.diagnose(
            "kafka", "my-cluster", "1.4.0",
            null, null, null, null);

        assertTrue(report.stepsCompleted().contains("preflight_checks"));
        assertTrue(report.preflightFindings() == null || report.preflightFindings().stream()
            .noneMatch(f -> "version_path".equals(f.check())));
    }

    // ---- Test helpers ----

    private static Deployment operatorDeployment(final String name, final String namespace, final String kafkaImages) {
        return new DeploymentBuilder()
            .withNewMetadata().withName(name).withNamespace(namespace)
                .withLabels(Map.of("app", "strimzi"))
            .endMetadata()
            .withNewSpec().withReplicas(1)
                .withNewTemplate().withNewSpec()
                    .addNewContainer()
                        .withName("strimzi-cluster-operator")
                        .withImage("quay.io/strimzi/operator:1.3.0")
                        .withEnv(new EnvVarBuilder().withName("STRIMZI_KAFKA_IMAGES").withValue(kafkaImages).build())
                    .endContainer()
                .endSpec().endTemplate()
            .endSpec()
            .build();
    }

    @SuppressWarnings("unchecked")
    private void setupKafkaCluster(final String name, final String namespace) {
        Kafka kafka = new KafkaBuilder()
            .withNewMetadata().withName(name).withNamespace(namespace).endMetadata()
            .withNewStatus()
                .addNewCondition()
                    .withType("Ready")
                    .withStatus("True")
                .endCondition()
            .endStatus()
            .build();

        MixedOperation kafkaOp = Mockito.mock(MixedOperation.class);
        when(kubernetesClient.resources(Kafka.class)).thenReturn(kafkaOp);

        NonNamespaceOperation nsKafkaOp = Mockito.mock(NonNamespaceOperation.class);
        when(kafkaOp.inNamespace(namespace)).thenReturn(nsKafkaOp);

        Resource kafkaResource = Mockito.mock(Resource.class);
        when(nsKafkaOp.withName(name)).thenReturn(kafkaResource);
        when(kafkaResource.get()).thenReturn(kafka);
    }
}
