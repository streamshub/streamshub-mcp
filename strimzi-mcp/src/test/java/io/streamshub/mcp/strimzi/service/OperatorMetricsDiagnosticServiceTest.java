/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.service;

import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ContainerBuilder;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.api.model.apps.DeploymentList;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.NonNamespaceOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.quarkiverse.mcp.server.ToolCallException;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.streamshub.mcp.strimzi.dto.OperatorMetricsDiagnosticReport;
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
 * Tests for {@link OperatorMetricsDiagnosticService}.
 */
@QuarkusTest
class OperatorMetricsDiagnosticServiceTest {

    @InjectMock
    KubernetesClient kubernetesClient;

    @Inject
    OperatorMetricsDiagnosticService diagnosticService;

    OperatorMetricsDiagnosticServiceTest() {
    }

    @BeforeEach
    void setUp() {
        // No default mocks needed - individual tests set up their own
    }

    /**
     * Verify that a missing operator throws a ToolCallException.
     */
    @Test
    void testThrowsWhenOperatorNotFound() {
        setupEmptyDeployments("kafka");

        assertThrows(ToolCallException.class, () ->
            diagnosticService.diagnose("kafka", null, null, null,
                null, null, null, null,
                null, null, null, null));
    }

    /**
     * Verify a full diagnostic workflow with a healthy operator completes successfully.
     */
    @Test
    void testFullWorkflowWithHealthyOperator() {
        setupOperatorDeployment("strimzi-cluster-operator", "kafka");

        OperatorMetricsDiagnosticReport report = diagnosticService.diagnose(
            "kafka", "strimzi-cluster-operator", null, null,
            null, null, null, null,
            null, null, null, null);

        assertNotNull(report);
        assertEquals("strimzi-cluster-operator", report.operator().name());
        assertEquals("kafka", report.operator().namespace());
        assertNotNull(report.stepsCompleted());
        assertTrue(report.stepsCompleted().contains("operator_status"));
        assertNotNull(report.timestamp());
        assertNotNull(report.message());
        assertNull(report.analysis());
    }

    /**
     * Verify that a failed step does not abort the entire workflow.
     */
    @Test
    void testStepFailureDoesNotAbortWorkflow() {
        setupOperatorDeployment("strimzi-cluster-operator", "kafka");

        OperatorMetricsDiagnosticReport report = diagnosticService.diagnose(
            "kafka", "strimzi-cluster-operator", null, "high memory",
            null, null, null, null,
            null, null, null, null);

        assertNotNull(report);
        assertTrue(report.stepsCompleted().contains("operator_status"));
    }

    /**
     * Verify the report message includes the operator name.
     */
    @Test
    void testReportIncludesConcernContext() {
        setupOperatorDeployment("strimzi-cluster-operator", "kafka");

        OperatorMetricsDiagnosticReport report = diagnosticService.diagnose(
            "kafka", "strimzi-cluster-operator", null, "reconciliation failures",
            null, null, null, null,
            null, null, null, null);

        assertNotNull(report);
        assertNotNull(report.message());
        assertTrue(report.message().contains("strimzi-cluster-operator"));
    }

    // ---- Test helpers ----

    @SuppressWarnings("unchecked")
    private void setupEmptyDeployments(final String namespace) {
        // Mock resources(Deployment.class) chain used by KubernetesResourceService
        MixedOperation deploymentOp = Mockito.mock(MixedOperation.class);
        when(kubernetesClient.resources(Deployment.class)).thenReturn(deploymentOp);

        NonNamespaceOperation nsOp = Mockito.mock(NonNamespaceOperation.class);
        when(deploymentOp.inNamespace(namespace)).thenReturn(nsOp);
        when(nsOp.withLabel(anyString(), anyString())).thenReturn(nsOp);

        DeploymentList emptyList = new DeploymentList();
        emptyList.setItems(List.of());
        when(nsOp.list()).thenReturn(emptyList);
    }

    @SuppressWarnings("unchecked")
    private void setupOperatorDeployment(final String name, final String namespace) {
        Container container = new ContainerBuilder()
            .withName("strimzi-cluster-operator")
            .withImage("quay.io/strimzi/operator:0.51.0")
            .build();

        Deployment deployment = new DeploymentBuilder()
            .withNewMetadata()
                .withName(name)
                .withNamespace(namespace)
                .withLabels(Map.of("app", "strimzi"))
            .endMetadata()
            .withNewSpec()
                .withReplicas(1)
                .withNewTemplate()
                    .withNewSpec()
                        .withContainers(container)
                    .endSpec()
                .endTemplate()
            .endSpec()
            .withNewStatus()
                .withReplicas(1)
                .withReadyReplicas(1)
                .withAvailableReplicas(1)
            .endStatus()
            .build();

        // Mock resources(Deployment.class) chain used by KubernetesResourceService.getResource
        MixedOperation deploymentOp = Mockito.mock(MixedOperation.class);
        when(kubernetesClient.resources(Deployment.class)).thenReturn(deploymentOp);

        NonNamespaceOperation nsOp = Mockito.mock(NonNamespaceOperation.class);
        when(deploymentOp.inNamespace(namespace)).thenReturn(nsOp);

        Resource deploymentResource = Mockito.mock(Resource.class);
        when(nsOp.withName(name)).thenReturn(deploymentResource);
        when(deploymentResource.get()).thenReturn(deployment);
    }
}
