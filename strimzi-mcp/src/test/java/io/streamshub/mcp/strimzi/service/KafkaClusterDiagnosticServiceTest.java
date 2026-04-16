/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.service;

import io.fabric8.kubernetes.api.model.Event;
import io.fabric8.kubernetes.api.model.EventList;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodList;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentList;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.AppsAPIGroupDSL;
import io.fabric8.kubernetes.client.dsl.FilterWatchListDeletable;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.NonNamespaceOperation;
import io.fabric8.kubernetes.client.dsl.PodResource;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.fabric8.kubernetes.client.dsl.RollableScalableResource;
import io.fabric8.kubernetes.client.dsl.V1APIGroupDSL;
import io.quarkiverse.mcp.server.ToolCallException;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.streamshub.mcp.common.util.NamespaceElicitationHelper;
import io.streamshub.mcp.strimzi.dto.KafkaClusterDiagnosticReport;
import io.strimzi.api.kafka.model.kafka.Kafka;
import io.strimzi.api.kafka.model.kafka.KafkaBuilder;
import io.strimzi.api.kafka.model.kafka.KafkaStatusBuilder;
import io.strimzi.api.kafka.model.nodepool.KafkaNodePool;
import io.strimzi.api.kafka.model.nodepool.KafkaNodePoolList;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link KafkaClusterDiagnosticService}.
 */
@QuarkusTest
class KafkaClusterDiagnosticServiceTest {

    @InjectMock
    KubernetesClient kubernetesClient;

    @Inject
    KafkaClusterDiagnosticService diagnosticService;

    KafkaClusterDiagnosticServiceTest() {
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        // Mock pods (empty by default)
        MixedOperation<Pod, PodList, PodResource> podOp = Mockito.mock(MixedOperation.class);
        Mockito.lenient().when(kubernetesClient.pods()).thenReturn(podOp);

        // Mock deployments (for operator lookups)
        MixedOperation<Deployment, DeploymentList, RollableScalableResource<Deployment>> deploymentOp =
            Mockito.mock(MixedOperation.class);
        AppsAPIGroupDSL appsApi = Mockito.mock(AppsAPIGroupDSL.class);
        Mockito.lenient().when(kubernetesClient.apps()).thenReturn(appsApi);
        Mockito.lenient().when(appsApi.deployments()).thenReturn(deploymentOp);

        // Mock events (empty by default)
        setupEmptyEvents();
    }

    @Test
    void testThrowsWhenClusterNameMissing() {
        assertThrows(ToolCallException.class, () ->
            diagnosticService.diagnose("kafka", null, null, null,
                null, null, null, null, null));
    }

    @Test
    void testThrowsWhenClusterNotFound() {
        assertThrows(ToolCallException.class, () ->
            diagnosticService.diagnose("kafka", "missing-cluster", null, null,
                null, null, null, null, null));
    }

    @Test
    void testFullWorkflowWithHealthyCluster() {
        setupKafkaCluster("my-cluster", "kafka", true);
        setupNodePools("kafka");

        KafkaClusterDiagnosticReport report = diagnosticService.diagnose(
            "kafka", "my-cluster", null, null,
            null, null, null, null, null);

        assertNotNull(report);
        assertEquals("my-cluster", report.cluster().name());
        assertEquals("kafka", report.cluster().namespace());
        assertNotNull(report.stepsCompleted());
        assertTrue(report.stepsCompleted().contains("cluster_status"));
        assertTrue(report.stepsCompleted().contains("node_pools"));
        assertTrue(report.stepsCompleted().contains("pod_health"));
        assertNotNull(report.timestamp());
        assertNotNull(report.message());
        // No sampling → analysis should be null
        assertNull(report.analysis());
    }

    @Test
    void testStepFailureDoesNotAbortWorkflow() {
        setupKafkaCluster("my-cluster", "kafka", false);
        // Node pools will return empty (no mock), events/metrics will fail
        // But the workflow should still complete

        KafkaClusterDiagnosticReport report = diagnosticService.diagnose(
            "kafka", "my-cluster", "NotReady", 15,
            null, null, null, null, null);

        assertNotNull(report);
        assertNotNull(report.cluster());
        assertEquals("Error", report.cluster().readiness());
        // Some steps should have failed (metrics at least, since no provider is mocked)
        assertTrue(report.stepsCompleted().contains("cluster_status"));
    }

    @Test
    void testReportIncludesSymptomContext() {
        setupKafkaCluster("my-cluster", "kafka", true);

        KafkaClusterDiagnosticReport report = diagnosticService.diagnose(
            "kafka", "my-cluster", "pods restarting", null,
            null, null, null, null, null);

        assertNotNull(report);
        assertNotNull(report.message());
        assertTrue(report.message().contains("my-cluster"));
    }

    @Test
    void testParseNamespacesFromError() {
        List<String> namespaces = NamespaceElicitationHelper.parseNamespacesFromError(
            "Multiple clusters named 'my-cluster' found in namespaces: kafka-prod, kafka-dev. Please specify namespace.");

        assertEquals(2, namespaces.size());
        assertEquals("kafka-prod", namespaces.get(0));
        assertEquals("kafka-dev", namespaces.get(1));
    }

    @Test
    void testParseNamespacesFromErrorReturnsEmptyForNull() {
        assertTrue(NamespaceElicitationHelper.parseNamespacesFromError(null).isEmpty());
    }

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

    @SuppressWarnings("unchecked")
    private void setupNodePools(final String namespace) {
        MixedOperation nodePoolOp = Mockito.mock(MixedOperation.class);
        Mockito.lenient().when(kubernetesClient.resources(KafkaNodePool.class)).thenReturn(nodePoolOp);

        NonNamespaceOperation nsNodePoolOp = Mockito.mock(NonNamespaceOperation.class);
        Mockito.lenient().when(nodePoolOp.inNamespace(namespace)).thenReturn(nsNodePoolOp);

        FilterWatchListDeletable filteredNodePools = Mockito.mock(FilterWatchListDeletable.class);
        Mockito.lenient().when(nsNodePoolOp.withLabel(anyString(), anyString())).thenReturn(filteredNodePools);

        KafkaNodePoolList emptyList = new KafkaNodePoolList();
        emptyList.setItems(List.of());
        Mockito.lenient().when(filteredNodePools.list()).thenReturn(emptyList);
    }

    @SuppressWarnings("unchecked")
    private void setupEmptyEvents() {
        V1APIGroupDSL v1 = Mockito.mock(V1APIGroupDSL.class);
        Mockito.lenient().when(kubernetesClient.v1()).thenReturn(v1);

        MixedOperation<Event, EventList, Resource<Event>> eventOp = Mockito.mock(MixedOperation.class);
        Mockito.lenient().when(v1.events()).thenReturn(eventOp);

        NonNamespaceOperation<Event, EventList, Resource<Event>> nsEventOp =
            Mockito.mock(NonNamespaceOperation.class);
        Mockito.lenient().when(eventOp.inNamespace(anyString())).thenReturn(nsEventOp);

        FilterWatchListDeletable<Event, EventList, Resource<Event>> fieldEventOp =
            Mockito.mock(FilterWatchListDeletable.class);
        Mockito.lenient().when(nsEventOp.withField(anyString(), anyString())).thenReturn(fieldEventOp);
        Mockito.lenient().when(fieldEventOp.withField(anyString(), anyString())).thenReturn(fieldEventOp);

        EventList emptyEventList = new EventList();
        emptyEventList.setItems(List.of());
        Mockito.lenient().when(fieldEventOp.list()).thenReturn(emptyEventList);
    }
}
