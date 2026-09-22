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
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.streamshub.mcp.common.dto.PaginatedResponse;
import io.streamshub.mcp.strimzi.dto.kafkatopic.KafkaTopicResponse;
import io.streamshub.mcp.strimzi.service.kafkatopic.KafkaTopicService;
import io.strimzi.api.kafka.model.topic.KafkaTopic;
import io.strimzi.api.kafka.model.topic.KafkaTopicBuilder;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;
/**
 * Service-level tests for {@link KafkaTopicService} with mocked Kubernetes.
 */
@QuarkusTest
class KafkaTopicServiceTest {

    @InjectMock
    KubernetesClient kubernetesClient;

    @Inject
    KafkaTopicService topicService;

    KafkaTopicServiceTest() {
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

        KubernetesMockHelper.setupEmptyResourceQuery(kubernetesClient, KafkaTopic.class);
    }

    @Test
    void testListTopicsReturnsEmptyWhenNoneExist() {
        PaginatedResponse<?> result = topicService.listTopics("kafka", "my-cluster", null, null);

        assertNotNull(result);
        assertTrue(result.items().isEmpty());
        assertEquals(0, result.total());
        assertFalse(result.hasMore());
    }

    @Test
    void testListTopicsDefaultPagination() {
        PaginatedResponse<?> result = topicService.listTopics("kafka", "my-cluster", null, null);

        assertEquals(0, result.offset());
        assertEquals(100, result.limit());
    }

    @Test
    void testListTopicsCustomPagination() {
        PaginatedResponse<?> result = topicService.listTopics("kafka", "my-cluster", 5, 10);

        assertEquals(5, result.offset());
        assertEquals(10, result.limit());
    }

    @Test
    void testListTopicsNegativeOffsetNormalized() {
        PaginatedResponse<?> result = topicService.listTopics("kafka", "my-cluster", -1, null);

        assertEquals(0, result.offset());
    }

    @Test
    void testGetTopicIncludesTopicIdWhenPresent() {
        setupKafkaTopic(new KafkaTopicBuilder()
            .withNewMetadata().withName("my-topic").withNamespace("kafka")
                .withLabels(Map.of("strimzi.io/cluster", "my-cluster"))
            .endMetadata()
            .withNewSpec().withPartitions(3).withReplicas(3).endSpec()
            .withNewStatus()
                .addNewCondition().withType("Ready").withStatus("True").endCondition()
                .withTopicId("abc123")
                .withTopicName("my-topic")
            .endStatus()
            .build());

        KafkaTopicResponse response = topicService.getTopic("kafka", "my-cluster", "my-topic");

        assertEquals("abc123", response.topicId());
        assertEquals("my-topic", response.topicName());
    }

    @Test
    void testGetTopicTopicIdAbsentWhenStatusMissing() {
        setupKafkaTopic(new KafkaTopicBuilder()
            .withNewMetadata().withName("my-topic").withNamespace("kafka")
                .withLabels(Map.of("strimzi.io/cluster", "my-cluster"))
            .endMetadata()
            .withNewSpec().withPartitions(3).withReplicas(3).endSpec()
            .build());

        KafkaTopicResponse response = topicService.getTopic("kafka", "my-cluster", "my-topic");

        assertNull(response.topicId());
        assertNull(response.topicName());
        assertNull(response.conditions());
        assertNull(response.replicasChange());
    }

    @Test
    void testGetTopicMapsStatusConditions() {
        setupKafkaTopic(new KafkaTopicBuilder()
            .withNewMetadata().withName("my-topic").withNamespace("kafka")
                .withLabels(Map.of("strimzi.io/cluster", "my-cluster"))
            .endMetadata()
            .withNewSpec().withPartitions(3).withReplicas(3).endSpec()
            .withNewStatus()
                .addNewCondition()
                    .withType("Ready").withStatus("True")
                    .withReason("Reconciled").withMessage("Topic is ready")
                    .withLastTransitionTime("2026-01-01T00:00:00Z")
                .endCondition()
            .endStatus()
            .build());

        KafkaTopicResponse response = topicService.getTopic("kafka", "my-cluster", "my-topic");

        assertEquals(1, response.conditions().size());
        assertEquals("Ready", response.conditions().get(0).type());
        assertEquals("True", response.conditions().get(0).status());
        assertEquals("Reconciled", response.conditions().get(0).reason());
        assertEquals("Topic is ready", response.conditions().get(0).message());
        assertEquals("2026-01-01T00:00:00Z", response.conditions().get(0).lastTransitionTime());
    }

    @Test
    void testGetTopicReplicasChangeNullInSteadyState() {
        setupKafkaTopic(new KafkaTopicBuilder()
            .withNewMetadata().withName("my-topic").withNamespace("kafka")
                .withLabels(Map.of("strimzi.io/cluster", "my-cluster"))
            .endMetadata()
            .withNewSpec().withPartitions(3).withReplicas(3).endSpec()
            .withNewStatus()
                .addNewCondition().withType("Ready").withStatus("True").endCondition()
            .endStatus()
            .build());

        KafkaTopicResponse response = topicService.getTopic("kafka", "my-cluster", "my-topic");

        assertNull(response.replicasChange());
    }

    @Test
    void testGetTopicReconciliationUsesObservedGeneration() {
        setupKafkaTopic(new KafkaTopicBuilder()
            .withNewMetadata().withName("my-topic").withNamespace("kafka")
                .withLabels(Map.of("strimzi.io/cluster", "my-cluster"))
                .withGeneration(3L)
            .endMetadata()
            .withNewSpec().withPartitions(3).withReplicas(3).endSpec()
            .withNewStatus()
                .addNewCondition().withType("Ready").withStatus("True").endCondition()
                .withObservedGeneration(2L)
            .endStatus()
            .build());

        KafkaTopicResponse response = topicService.getTopic("kafka", "my-cluster", "my-topic");

        assertNotNull(response.reconciliation());
        assertEquals(3L, response.reconciliation().generation());
        assertEquals(2L, response.reconciliation().observedGeneration());
        assertFalse(response.reconciliation().upToDate());
    }

    @SuppressWarnings("unchecked")
    private void setupKafkaTopic(final KafkaTopic topic) {
        String namespace = topic.getMetadata().getNamespace();
        String name = topic.getMetadata().getName();

        MixedOperation topicOp = Mockito.mock(MixedOperation.class);
        when(kubernetesClient.resources(KafkaTopic.class)).thenReturn(topicOp);

        NonNamespaceOperation nsTopicOp = Mockito.mock(NonNamespaceOperation.class);
        when(topicOp.inNamespace(namespace)).thenReturn(nsTopicOp);

        Resource topicResource = Mockito.mock(Resource.class);
        when(nsTopicOp.withName(name)).thenReturn(topicResource);
        when(topicResource.get()).thenReturn(topic);
    }
}
