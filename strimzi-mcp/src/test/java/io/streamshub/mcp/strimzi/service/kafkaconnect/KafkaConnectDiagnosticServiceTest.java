/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.service.kafkaconnect;

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
import io.streamshub.mcp.strimzi.dto.kafkaconnect.KafkaConnectDiagnosticReport;
import io.streamshub.mcp.strimzi.service.KubernetesMockHelper;
import io.strimzi.api.kafka.model.connect.KafkaConnect;
import io.strimzi.api.kafka.model.connect.KafkaConnectBuilder;
import io.strimzi.api.kafka.model.connector.KafkaConnector;
import io.strimzi.api.kafka.model.kafka.Kafka;
import io.strimzi.api.kafka.model.nodepool.KafkaNodePool;
import io.strimzi.api.kafka.model.topic.KafkaTopic;
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
 * Service-level tests for {@link KafkaConnectDiagnosticService} with mocked Kubernetes.
 */
@QuarkusTest
class KafkaConnectDiagnosticServiceTest {

    @InjectMock
    KubernetesClient kubernetesClient;

    @Inject
    KafkaConnectDiagnosticService diagnosticService;

    KafkaConnectDiagnosticServiceTest() {
    }

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        MixedOperation<Pod, PodList, PodResource> podOp = Mockito.mock(MixedOperation.class);
        Mockito.lenient().when(kubernetesClient.pods()).thenReturn(podOp);

        MixedOperation<Deployment, DeploymentList, RollableScalableResource<Deployment>> deploymentOp =
            Mockito.mock(MixedOperation.class);
        AppsAPIGroupDSL appsApi = Mockito.mock(AppsAPIGroupDSL.class);
        Mockito.lenient().when(kubernetesClient.apps()).thenReturn(appsApi);
        Mockito.lenient().when(appsApi.deployments()).thenReturn(deploymentOp);

        KubernetesMockHelper.setupEmptyResourceQuery(kubernetesClient, KafkaConnect.class);
        KubernetesMockHelper.setupEmptyResourceQuery(kubernetesClient, KafkaConnector.class);
        KubernetesMockHelper.setupEmptyResourceQuery(kubernetesClient, Kafka.class);
        KubernetesMockHelper.setupEmptyResourceQuery(kubernetesClient, KafkaNodePool.class);
        KubernetesMockHelper.setupEmptyResourceQuery(kubernetesClient, KafkaTopic.class);
        KubernetesMockHelper.setupEmptyResourceQuery(kubernetesClient, Pod.class);
    }

    /**
     * Verify diagnose throws when Connect cluster name is null.
     */
    @Test
    void testThrowsWhenConnectNameNull() {
        assertThrows(ToolCallException.class, () ->
            diagnosticService.diagnose("kafka", null, null, null,
                null, null, null, null, null));
    }

    /**
     * Verify diagnose throws when Connect cluster is not found.
     */
    @Test
    void testThrowsWhenConnectNotFound() {
        assertThrows(ToolCallException.class, () ->
            diagnosticService.diagnose("kafka", "nonexistent", null, null,
                null, null, null, null, null));
    }

    /**
     * Verify full workflow completes with a found Connect cluster (no Sampling).
     */
    @SuppressWarnings("unchecked")
    @Test
    void testFullWorkflowWithConnectCluster() {
        KafkaConnect connect = new KafkaConnectBuilder()
            .withNewMetadata()
                .withName("my-connect")
                .withNamespace("kafka")
            .endMetadata()
            .withNewStatus()
                .addNewCondition()
                    .withType("Ready")
                    .withStatus("True")
                .endCondition()
            .endStatus()
            .build();

        MixedOperation connectOp = Mockito.mock(MixedOperation.class);
        when(kubernetesClient.resources(KafkaConnect.class)).thenReturn(connectOp);
        NonNamespaceOperation nsConnectOp = Mockito.mock(NonNamespaceOperation.class);
        when(connectOp.inNamespace("kafka")).thenReturn(nsConnectOp);
        Resource connectResource = Mockito.mock(Resource.class);
        when(nsConnectOp.withName("my-connect")).thenReturn(connectResource);
        when(connectResource.get()).thenReturn(connect);

        KafkaConnectDiagnosticReport report = diagnosticService.diagnose(
            "kafka", "my-connect", null, null,
            null, null, null, null, null);

        assertNotNull(report);
        assertNotNull(report.connectCluster());
        assertTrue(report.stepsCompleted().contains("connect_status"));
        assertNull(report.analysis());
        assertNotNull(report.timestamp());
    }

    /**
     * Verify step failures do not abort the workflow.
     */
    @SuppressWarnings("unchecked")
    @Test
    void testStepFailureDoesNotAbortWorkflow() {
        KafkaConnect connect = new KafkaConnectBuilder()
            .withNewMetadata()
                .withName("my-connect")
                .withNamespace("kafka")
            .endMetadata()
            .build();

        MixedOperation connectOp = Mockito.mock(MixedOperation.class);
        when(kubernetesClient.resources(KafkaConnect.class)).thenReturn(connectOp);
        NonNamespaceOperation nsConnectOp = Mockito.mock(NonNamespaceOperation.class);
        when(connectOp.inNamespace("kafka")).thenReturn(nsConnectOp);
        Resource connectResource = Mockito.mock(Resource.class);
        when(nsConnectOp.withName("my-connect")).thenReturn(connectResource);
        when(connectResource.get()).thenReturn(connect);

        KafkaConnectDiagnosticReport report = diagnosticService.diagnose(
            "kafka", "my-connect", null, null,
            null, null, null, null, null);

        assertNotNull(report);
        assertNotNull(report.connectCluster());
        assertTrue(report.stepsCompleted().contains("connect_status"));
    }
}
