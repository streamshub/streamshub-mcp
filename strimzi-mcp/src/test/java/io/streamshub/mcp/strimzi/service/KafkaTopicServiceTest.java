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
import io.fabric8.kubernetes.client.dsl.PodResource;
import io.fabric8.kubernetes.client.dsl.RollableScalableResource;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.streamshub.mcp.strimzi.dto.KafkaTopicListResponse;
import io.strimzi.api.kafka.model.topic.KafkaTopic;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        KafkaTopicListResponse result = topicService.listTopics("kafka", "my-cluster", null, null);

        assertNotNull(result);
        assertTrue(result.topics().isEmpty());
        assertEquals(0, result.total());
        assertFalse(result.hasMore());
    }

    @Test
    void testListTopicsDefaultPagination() {
        KafkaTopicListResponse result = topicService.listTopics("kafka", "my-cluster", null, null);

        assertEquals(0, result.offset());
        assertEquals(100, result.limit());
    }

    @Test
    void testListTopicsCustomPagination() {
        KafkaTopicListResponse result = topicService.listTopics("kafka", "my-cluster", 5, 10);

        assertEquals(5, result.offset());
        assertEquals(10, result.limit());
    }

    @Test
    void testListTopicsNegativeOffsetNormalized() {
        KafkaTopicListResponse result = topicService.listTopics("kafka", "my-cluster", -1, null);

        assertEquals(0, result.offset());
    }
}
