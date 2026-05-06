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
import io.fabric8.kubernetes.client.dsl.FilterWatchListDeletable;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.NonNamespaceOperation;
import io.fabric8.kubernetes.client.dsl.PodResource;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.fabric8.kubernetes.client.dsl.RollableScalableResource;
import io.quarkiverse.mcp.server.ToolCallException;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.streamshub.mcp.strimzi.dto.KafkaTopicDiagnosticReport;
import io.strimzi.api.kafka.model.kafka.Kafka;
import io.strimzi.api.kafka.model.kafka.KafkaBuilder;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link KafkaTopicDiagnosticService}.
 */
@QuarkusTest
class KafkaTopicDiagnosticServiceTest {

    @InjectMock
    KubernetesClient kubernetesClient;

    @Inject
    KafkaTopicDiagnosticService topicDiagnosticService;

    KafkaTopicDiagnosticServiceTest() {
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
        KubernetesMockHelper.setupEmptyResourceQuery(kubernetesClient, KafkaTopic.class);
        KubernetesMockHelper.setupEmptyResourceQuery(kubernetesClient, Pod.class);
    }

    @Test
    void testThrowsWhenTopicNameMissing() {
        assertThrows(ToolCallException.class, () ->
            topicDiagnosticService.diagnose("kafka", null, null, null,
                null, null, null, null, null));
    }

    @Test
    void testThrowsWhenTopicNotFound() {
        assertThrows(ToolCallException.class, () ->
            topicDiagnosticService.diagnose("kafka", "missing-topic", "my-cluster", null,
                null, null, null, null, null));
    }

    @Test
    void testFullWorkflowWithTopic() {
        setupKafkaTopic("my-topic", "kafka", "my-cluster");
        setupKafkaCluster("my-cluster", "kafka");

        KafkaTopicDiagnosticReport report = topicDiagnosticService.diagnose(
            "kafka", "my-topic", "my-cluster", null,
            null, null, null, null, null);

        assertNotNull(report);
        assertNotNull(report.topic());
        assertTrue(report.stepsCompleted().contains("topic_status"));
        assertNull(report.analysis());
        assertNotNull(report.timestamp());
    }

    @Test
    void testStepFailureDoesNotAbortWorkflow() {
        setupKafkaTopic("my-topic", "kafka", "my-cluster");
        // Cluster, operator logs, events, metrics will fail (no mocks)
        // But the workflow should still complete

        KafkaTopicDiagnosticReport report = topicDiagnosticService.diagnose(
            "kafka", "my-topic", "my-cluster", null,
            null, null, null, null, null);

        assertNotNull(report);
        assertTrue(report.stepsCompleted().contains("topic_status"));
    }

    // ---- Test helpers ----

    @SuppressWarnings("unchecked")
    private void setupKafkaTopic(final String name, final String namespace, final String clusterName) {
        KafkaTopic topic = new KafkaTopicBuilder()
            .withNewMetadata().withName(name).withNamespace(namespace)
                .withLabels(Map.of("strimzi.io/cluster", clusterName))
            .endMetadata()
            .withNewSpec().withPartitions(3).withReplicas(3).endSpec()
            .withNewStatus()
                .addNewCondition().withType("Ready").withStatus("True").endCondition()
            .endStatus()
            .build();

        MixedOperation topicOp = Mockito.mock(MixedOperation.class);
        when(kubernetesClient.resources(KafkaTopic.class)).thenReturn(topicOp);

        NonNamespaceOperation nsTopicOp = Mockito.mock(NonNamespaceOperation.class);
        when(topicOp.inNamespace(namespace)).thenReturn(nsTopicOp);

        Resource topicResource = Mockito.mock(Resource.class);
        when(nsTopicOp.withName(name)).thenReturn(topicResource);
        when(topicResource.get()).thenReturn(topic);

        // Label-based query chain for KubernetesResourceService.queryResourcesByLabel
        when(topicOp.inAnyNamespace()).thenReturn(nsTopicOp);
        FilterWatchListDeletable labeledOp = Mockito.mock(FilterWatchListDeletable.class);
        when(nsTopicOp.withLabel(anyString(), anyString())).thenReturn(labeledOp);

        io.fabric8.kubernetes.api.model.KubernetesResourceList topicList =
            Mockito.mock(io.fabric8.kubernetes.api.model.KubernetesResourceList.class);
        Mockito.lenient().when(topicList.getItems()).thenReturn(List.of(topic));
        Mockito.lenient().when(labeledOp.list()).thenReturn(topicList);
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
