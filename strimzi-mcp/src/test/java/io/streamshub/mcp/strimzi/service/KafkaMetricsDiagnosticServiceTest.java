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
import io.streamshub.mcp.common.util.NamespaceElicitationHelper;
import io.streamshub.mcp.strimzi.dto.KafkaMetricsDiagnosticReport;
import io.strimzi.api.kafka.model.kafka.Kafka;
import io.strimzi.api.kafka.model.kafka.KafkaBuilder;
import io.strimzi.api.kafka.model.kafka.KafkaStatusBuilder;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link KafkaMetricsDiagnosticService}.
 */
@QuarkusTest
class KafkaMetricsDiagnosticServiceTest {

    @InjectMock
    KubernetesClient kubernetesClient;

    @Inject
    KafkaMetricsDiagnosticService diagnosticService;

    KafkaMetricsDiagnosticServiceTest() {
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
    }

    /**
     * Verify that a null cluster name throws a ToolCallException.
     */
    @Test
    void testThrowsWhenClusterNameMissing() {
        assertThrows(ToolCallException.class, () ->
            diagnosticService.diagnose("kafka", null, null,
                null, null, null, null,
                null, null, null, null, null));
    }

    /**
     * Verify that a non-existent cluster throws a ToolCallException.
     */
    @Test
    void testThrowsWhenClusterNotFound() {
        assertThrows(ToolCallException.class, () ->
            diagnosticService.diagnose("kafka", "missing-cluster", null,
                null, null, null, null,
                null, null, null, null, null));
    }

    /**
     * Verify a full diagnostic workflow with a healthy cluster completes successfully.
     */
    @Test
    void testFullWorkflowWithHealthyCluster() {
        setupKafkaCluster("my-cluster", "kafka", true);

        KafkaMetricsDiagnosticReport report = diagnosticService.diagnose(
            "kafka", "my-cluster", null,
            null, null, null, null,
            null, null, null, null, null);

        assertNotNull(report);
        assertEquals("my-cluster", report.cluster().name());
        assertEquals("kafka", report.cluster().namespace());
        assertNotNull(report.stepsCompleted());
        assertTrue(report.stepsCompleted().contains("cluster_status"));
        assertTrue(report.stepsCompleted().contains("pod_health"));
        assertNotNull(report.timestamp());
        assertNotNull(report.message());
        assertNull(report.analysis());
    }

    /**
     * Verify that a failed step does not abort the entire workflow.
     */
    @Test
    void testStepFailureDoesNotAbortWorkflow() {
        setupKafkaCluster("my-cluster", "kafka", false);

        KafkaMetricsDiagnosticReport report = diagnosticService.diagnose(
            "kafka", "my-cluster", "high latency",
            null, null, null, null,
            null, null, null, null, null);

        assertNotNull(report);
        assertNotNull(report.cluster());
        assertEquals("Error", report.cluster().readiness());
        assertTrue(report.stepsCompleted().contains("cluster_status"));
    }

    /**
     * Verify the report message includes the cluster name.
     */
    @Test
    void testReportIncludesConcernContext() {
        setupKafkaCluster("my-cluster", "kafka", true);

        KafkaMetricsDiagnosticReport report = diagnosticService.diagnose(
            "kafka", "my-cluster", "replication lag",
            null, null, null, null,
            null, null, null, null, null);

        assertNotNull(report);
        assertNotNull(report.message());
        assertTrue(report.message().contains("my-cluster"));
    }

    /**
     * Verify namespace parsing from multi-namespace error messages.
     */
    @Test
    void testParseNamespacesFromError() {
        List<String> namespaces = NamespaceElicitationHelper.parseNamespacesFromError(
            "Multiple clusters named 'my-cluster' found in namespaces: kafka-prod, kafka-dev. "
                + "Please specify namespace.");

        assertEquals(2, namespaces.size());
        assertEquals("kafka-prod", namespaces.get(0));
        assertEquals("kafka-dev", namespaces.get(1));
    }

    /**
     * Verify namespace parsing returns empty list for null input.
     */
    @Test
    void testParseNamespacesFromErrorReturnsEmptyForNull() {
        assertTrue(NamespaceElicitationHelper.parseNamespacesFromError(null).isEmpty());
    }

    /**
     * Verify namespace parsing returns empty list for unrelated messages.
     */
    @Test
    void testParseNamespacesFromErrorReturnsEmptyForUnrelatedMessage() {
        assertTrue(NamespaceElicitationHelper.parseNamespacesFromError("Some other error").isEmpty());
    }

    // ---- Test helpers ----

    @SuppressWarnings("unchecked")
    private void setupKafkaCluster(final String name, final String namespace, final boolean ready) {
        KafkaStatusBuilder statusBuilder = new KafkaStatusBuilder();
        if (ready) {
            statusBuilder.addNewCondition()
                .withType("Ready")
                .withStatus("True")
                .endCondition();
        } else {
            statusBuilder.addNewCondition()
                .withType("Ready")
                .withStatus("False")
                .withReason("PodNotReady")
                .withMessage("1 out of 3 brokers not ready")
                .endCondition();
        }

        Kafka kafka = new KafkaBuilder()
            .withNewMetadata().withName(name).withNamespace(namespace).endMetadata()
            .withStatus(statusBuilder.build())
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
