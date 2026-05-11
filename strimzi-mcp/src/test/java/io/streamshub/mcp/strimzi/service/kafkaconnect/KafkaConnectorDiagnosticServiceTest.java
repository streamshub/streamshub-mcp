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
import io.streamshub.mcp.strimzi.dto.kafkaconnect.KafkaConnectorDiagnosticReport;
import io.streamshub.mcp.strimzi.service.KubernetesMockHelper;
import io.strimzi.api.kafka.model.connect.KafkaConnect;
import io.strimzi.api.kafka.model.connector.KafkaConnector;
import io.strimzi.api.kafka.model.connector.KafkaConnectorBuilder;
import io.strimzi.api.kafka.model.kafka.Kafka;
import io.strimzi.api.kafka.model.nodepool.KafkaNodePool;
import io.strimzi.api.kafka.model.topic.KafkaTopic;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * Service-level tests for {@link KafkaConnectorDiagnosticService} with mocked Kubernetes.
 */
@QuarkusTest
class KafkaConnectorDiagnosticServiceTest {

    @InjectMock
    KubernetesClient kubernetesClient;

    @Inject
    KafkaConnectorDiagnosticService diagnosticService;

    KafkaConnectorDiagnosticServiceTest() {
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

        KubernetesMockHelper.setupEmptyResourceQuery(kubernetesClient, KafkaConnector.class);
        KubernetesMockHelper.setupEmptyResourceQuery(kubernetesClient, KafkaConnect.class);
        KubernetesMockHelper.setupEmptyResourceQuery(kubernetesClient, Kafka.class);
        KubernetesMockHelper.setupEmptyResourceQuery(kubernetesClient, KafkaNodePool.class);
        KubernetesMockHelper.setupEmptyResourceQuery(kubernetesClient, KafkaTopic.class);
        KubernetesMockHelper.setupEmptyResourceQuery(kubernetesClient, Pod.class);
    }

    /**
     * Verify diagnose throws when connector name is null.
     */
    @Test
    void testThrowsWhenConnectorNameNull() {
        assertThrows(ToolCallException.class, () ->
            diagnosticService.diagnose("kafka", null, null, null,
                null, null, null, null, null));
    }

    /**
     * Verify diagnose throws when connector is not found.
     */
    @Test
    void testThrowsWhenConnectorNotFound() {
        assertThrows(ToolCallException.class, () ->
            diagnosticService.diagnose("kafka", "nonexistent", null, null,
                null, null, null, null, null));
    }

    /**
     * Verify full workflow completes with a found connector (no Sampling).
     */
    @SuppressWarnings("unchecked")
    @Test
    void testFullWorkflowWithConnector() {
        KafkaConnector connector = new KafkaConnectorBuilder()
            .withNewMetadata()
                .withName("my-connector")
                .withNamespace("kafka")
                .withLabels(Map.of("strimzi.io/cluster", "my-connect"))
            .endMetadata()
            .withNewSpec()
                .withClassName("org.apache.kafka.connect.file.FileStreamSinkConnector")
            .endSpec()
            .withNewStatus()
                .addNewCondition()
                    .withType("Ready")
                    .withStatus("True")
                .endCondition()
            .endStatus()
            .build();

        MixedOperation connectorOp = Mockito.mock(MixedOperation.class);
        when(kubernetesClient.resources(KafkaConnector.class)).thenReturn(connectorOp);
        NonNamespaceOperation nsConnectorOp = Mockito.mock(NonNamespaceOperation.class);
        when(connectorOp.inNamespace("kafka")).thenReturn(nsConnectorOp);
        Resource connectorResource = Mockito.mock(Resource.class);
        when(nsConnectorOp.withName("my-connector")).thenReturn(connectorResource);
        when(connectorResource.get()).thenReturn(connector);

        KafkaConnectorDiagnosticReport report = diagnosticService.diagnose(
            "kafka", "my-connector", null, null,
            null, null, null, null, null);

        assertNotNull(report);
        assertNotNull(report.connector());
        assertTrue(report.stepsCompleted().contains("connector_status"));
        assertNull(report.analysis());
        assertNotNull(report.timestamp());
    }

    /**
     * Verify step failures do not abort the workflow.
     */
    @SuppressWarnings("unchecked")
    @Test
    void testStepFailureDoesNotAbortWorkflow() {
        KafkaConnector connector = new KafkaConnectorBuilder()
            .withNewMetadata()
                .withName("my-connector")
                .withNamespace("kafka")
                .withLabels(Map.of("strimzi.io/cluster", "my-connect"))
            .endMetadata()
            .withNewSpec()
                .withClassName("org.example.MyConnector")
            .endSpec()
            .build();

        MixedOperation connectorOp = Mockito.mock(MixedOperation.class);
        when(kubernetesClient.resources(KafkaConnector.class)).thenReturn(connectorOp);
        NonNamespaceOperation nsConnectorOp = Mockito.mock(NonNamespaceOperation.class);
        when(connectorOp.inNamespace("kafka")).thenReturn(nsConnectorOp);
        Resource connectorResource = Mockito.mock(Resource.class);
        when(nsConnectorOp.withName("my-connector")).thenReturn(connectorResource);
        when(connectorResource.get()).thenReturn(connector);

        KafkaConnectorDiagnosticReport report = diagnosticService.diagnose(
            "kafka", "my-connector", null, null,
            null, null, null, null, null);

        assertNotNull(report);
        assertNotNull(report.connector());
        assertTrue(report.stepsCompleted().contains("connector_status"));
    }
}
