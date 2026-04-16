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
import io.streamshub.mcp.strimzi.dto.KafkaConnectivityDiagnosticReport;
import io.strimzi.api.kafka.model.kafka.Kafka;
import io.strimzi.api.kafka.model.kafka.KafkaBuilder;
import io.strimzi.api.kafka.model.kafka.listener.ListenerStatusBuilder;
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
 * Tests for {@link KafkaConnectivityDiagnosticService}.
 */
@QuarkusTest
class KafkaConnectivityDiagnosticServiceTest {

    @InjectMock
    KubernetesClient kubernetesClient;

    @Inject
    KafkaConnectivityDiagnosticService connectivityDiagnosticService;

    KafkaConnectivityDiagnosticServiceTest() {
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

    @Test
    void testThrowsWhenClusterNameMissing() {
        assertThrows(ToolCallException.class, () ->
            connectivityDiagnosticService.diagnose("kafka", null, null,
                null, null, null, null, null));
    }

    @Test
    void testThrowsWhenClusterNotFound() {
        assertThrows(ToolCallException.class, () ->
            connectivityDiagnosticService.diagnose("kafka", "missing-cluster", null,
                null, null, null, null, null));
    }

    @Test
    void testFullWorkflowWithListeners() {
        setupKafkaClusterWithListeners("my-cluster", "kafka");

        KafkaConnectivityDiagnosticReport report = connectivityDiagnosticService.diagnose(
            "kafka", "my-cluster", null,
            null, null, null, null, null);

        assertNotNull(report);
        assertEquals("my-cluster", report.cluster().name());
        assertEquals("kafka", report.cluster().namespace());
        assertTrue(report.stepsCompleted().contains("cluster_status"));
        assertTrue(report.stepsCompleted().contains("bootstrap_servers"));
        assertNotNull(report.bootstrapServers());
        assertNull(report.analysis());
        assertNotNull(report.timestamp());
    }

    @Test
    void testStepFailureDoesNotAbortWorkflow() {
        setupKafkaCluster("my-cluster", "kafka");
        // Certificates will fail (no secret mock), pods empty, logs empty
        // But workflow should complete

        KafkaConnectivityDiagnosticReport report = connectivityDiagnosticService.diagnose(
            "kafka", "my-cluster", null,
            null, null, null, null, null);

        assertNotNull(report);
        assertTrue(report.stepsCompleted().contains("cluster_status"));
    }

    @Test
    void testWithListenerNameFilter() {
        setupKafkaClusterWithListeners("my-cluster", "kafka");

        KafkaConnectivityDiagnosticReport report = connectivityDiagnosticService.diagnose(
            "kafka", "my-cluster", "tls",
            null, null, null, null, null);

        assertNotNull(report);
        assertNotNull(report.cluster());
        assertTrue(report.message().contains("my-cluster"));
    }

    @Test
    void testParseNamespacesFromError() {
        List<String> namespaces = NamespaceElicitationHelper.parseNamespacesFromError(
            "Multiple clusters named 'my-cluster' found in namespaces: ns-a, ns-b. Please specify namespace.");

        assertEquals(2, namespaces.size());
        assertEquals("ns-a", namespaces.get(0));
        assertEquals("ns-b", namespaces.get(1));
    }

    @Test
    void testParseNamespacesFromErrorReturnsEmptyForNull() {
        assertTrue(NamespaceElicitationHelper.parseNamespacesFromError(null).isEmpty());
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

    @SuppressWarnings("unchecked")
    private void setupKafkaClusterWithListeners(final String name, final String namespace) {
        Kafka kafka = new KafkaBuilder()
            .withNewMetadata().withName(name).withNamespace(namespace).endMetadata()
            .withNewSpec()
                .withNewKafka()
                    .addNewListener()
                        .withName("plain")
                        .withPort(9092)
                        .withType(io.strimzi.api.kafka.model.kafka.listener.KafkaListenerType.INTERNAL)
                        .withTls(false)
                    .endListener()
                    .addNewListener()
                        .withName("tls")
                        .withPort(9093)
                        .withType(io.strimzi.api.kafka.model.kafka.listener.KafkaListenerType.INTERNAL)
                        .withTls(true)
                    .endListener()
                .endKafka()
            .endSpec()
            .withNewStatus()
                .addNewCondition()
                    .withType("Ready")
                    .withStatus("True")
                .endCondition()
                .withListeners(List.of(
                    new ListenerStatusBuilder()
                        .withName("plain")
                        .addNewAddress()
                            .withHost(name + "-kafka-bootstrap." + namespace + ".svc")
                            .withPort(9092)
                        .endAddress()
                        .build(),
                    new ListenerStatusBuilder()
                        .withName("tls")
                        .addNewAddress()
                            .withHost(name + "-kafka-bootstrap." + namespace + ".svc")
                            .withPort(9093)
                        .endAddress()
                        .build()
                ))
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
