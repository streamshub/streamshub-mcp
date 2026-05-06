/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.service;

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodList;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentList;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.AppsAPIGroupDSL;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.NonNamespaceOperation;
import io.fabric8.kubernetes.client.dsl.PodResource;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.fabric8.kubernetes.client.dsl.RollableScalableResource;
import io.quarkiverse.mcp.server.ToolCallException;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.streamshub.mcp.strimzi.dto.UpgradeReadinessReport;
import io.strimzi.api.kafka.model.kafka.Kafka;
import io.strimzi.api.kafka.model.kafka.KafkaBuilder;
import io.strimzi.api.kafka.model.nodepool.KafkaNodePool;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

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
    }

    @Test
    void testThrowsWhenClusterNameMissing() {
        assertThrows(ToolCallException.class, () ->
            upgradeReadinessDiagnosticService.diagnose("kafka", null, null,
                null, null, null, null, null));
    }

    @Test
    void testThrowsWhenClusterNotFound() {
        assertThrows(ToolCallException.class, () ->
            upgradeReadinessDiagnosticService.diagnose("kafka", "missing-cluster", null,
                null, null, null, null, null));
    }

    @Test
    void testFullWorkflowWithCluster() {
        setupKafkaCluster("my-cluster", "kafka");

        UpgradeReadinessReport report = upgradeReadinessDiagnosticService.diagnose(
            "kafka", "my-cluster", null,
            null, null, null, null, null);

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
            null, null, null, null, null);

        assertNotNull(report);
        assertTrue(report.stepsCompleted().contains("cluster_status"));
    }

    // ---- Test helpers ----

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
