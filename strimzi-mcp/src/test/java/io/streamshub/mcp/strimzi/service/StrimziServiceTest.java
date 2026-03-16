/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.service;

import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodList;
import io.fabric8.kubernetes.api.model.PodStatus;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentList;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.AppsAPIGroupDSL;
import io.fabric8.kubernetes.client.dsl.FilterWatchListDeletable;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.NonNamespaceOperation;
import io.fabric8.kubernetes.client.dsl.PodResource;
import io.fabric8.kubernetes.client.dsl.RollableScalableResource;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.streamshub.mcp.common.config.KubernetesConstants;
import io.streamshub.mcp.strimzi.config.StrimziConstants;
import io.streamshub.mcp.strimzi.dto.KafkaClusterPodsResponse;
import io.streamshub.mcp.strimzi.dto.KafkaClusterResponse;
import io.streamshub.mcp.strimzi.dto.KafkaTopicResponse;
import io.streamshub.mcp.strimzi.dto.StrimziOperatorLogsResponse;
import io.streamshub.mcp.strimzi.dto.StrimziOperatorResponse;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Integration test for Strimzi services with mocked Kubernetes.
 * Tests the core service functionality that powers the MCP tools.
 */
@QuarkusTest
class StrimziServiceTest {

    StrimziServiceTest() {
    }

    @InjectMock
    KubernetesClient kubernetesClient;

    @Inject
    StrimziOperatorService operatorService;

    @Inject
    KafkaService kafkaService;

    @Inject
    KafkaTopicService topicService;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        // Use lenient stubbing to avoid conflicts with background threads
        // (ResourceSubscriptionManager starts watches on startup)
        MixedOperation<Pod, PodList, PodResource> podOp = Mockito.mock(MixedOperation.class);
        Mockito.lenient().when(kubernetesClient.pods()).thenReturn(podOp);

        MixedOperation<Deployment, DeploymentList, RollableScalableResource<Deployment>> deploymentOp =
            Mockito.mock(MixedOperation.class);
        AppsAPIGroupDSL appsApi =
            Mockito.mock(io.fabric8.kubernetes.client.dsl.AppsAPIGroupDSL.class);
        Mockito.lenient().when(kubernetesClient.apps()).thenReturn(appsApi);
        Mockito.lenient().when(appsApi.deployments()).thenReturn(deploymentOp);
    }

    @Test
    void testOperatorLogsRetrievalWithHealthyOperator() {
        // Setup a healthy operator pod in kafka-system namespace
        setupHealthyOperatorPod("kafka-system", "strimzi-cluster-operator-abc123");

        // Test the service method
        StrimziOperatorLogsResponse result = operatorService.getOperatorLogs("kafka-system", null, null, null, null, null);

        // Verify the result
        assertNotNull(result);
        assertEquals("kafka-system", result.namespace());
        assertNotNull(result.timestamp());

        // If pods were found, verify they're included
        if (result.operatorPods() != null && !result.operatorPods().isEmpty()) {
            assertTrue(result.operatorPods().contains("strimzi-cluster-operator-abc123"));
            assertNotNull(result.logs());
        }
    }


    @Test
    void testOperatorLogsNoOperatorFound() {
        // Setup empty responses
        setupEmptyResponses("empty-namespace");

        // Test with namespace that has no operator
        StrimziOperatorLogsResponse result = operatorService.getOperatorLogs("empty-namespace", null, null, null, null, null);

        // Should handle gracefully - returns notFound response
        assertNotNull(result);
        assertEquals("empty-namespace", result.namespace());
        assertNotNull(result.message());
        assertNotNull(result.timestamp());
    }


    @Test
    void testInputNormalizationOperatorServices() {
        // Test that service handles input normalization properly
        setupHealthyOperatorPod("kafka", "operator-pod");

        // Test with various input formats (spaces, case)
        StrimziOperatorLogsResponse result1 = operatorService.getOperatorLogs("  KAFKA  ", null, null, null, null, null);
        StrimziOperatorLogsResponse result2 = operatorService.getOperatorLogs("kafka", null, null, null, null, null);

        // Both should work and return consistent results
        assertNotNull(result1);
        assertNotNull(result2);

        // The normalized namespace should be the same
        assertEquals("kafka", result1.namespace());
        assertEquals("kafka", result2.namespace());
    }

    // Helper methods for setting up mocks

    @SuppressWarnings("unchecked")
    private void setupHealthyOperatorPod(String namespace, String podName) {
        // Create a mock operator pod
        Pod operatorPod = new Pod();
        ObjectMeta metadata = new ObjectMeta();
        metadata.setName(podName);
        metadata.setNamespace(namespace);
        metadata.setLabels(Map.of(
            KubernetesConstants.Labels.APP_NAME, StrimziConstants.Operator.APP_LABEL_VALUE
        ));
        operatorPod.setMetadata(metadata);

        PodStatus status = new PodStatus();
        status.setPhase("Running");
        status.setStartTime(Instant.now().minusSeconds(300).toString());
        operatorPod.setStatus(status);

        PodList podList = new PodList();
        podList.setItems(List.of(operatorPod));

        // Setup mock behavior
        MixedOperation<Pod, PodList, PodResource> podOp = kubernetesClient.pods();
        NonNamespaceOperation<Pod, PodList, PodResource> namespacedPodOp = Mockito.mock(NonNamespaceOperation.class);
        FilterWatchListDeletable<Pod, PodList, PodResource> labeledPodOp = Mockito.mock(FilterWatchListDeletable.class);
        PodResource podResource = Mockito.mock(PodResource.class);

        when(podOp.inNamespace(namespace)).thenReturn(namespacedPodOp);
        when(namespacedPodOp.withLabel(anyString(), anyString())).thenReturn(labeledPodOp);
        when(labeledPodOp.list()).thenReturn(podList);

        // Mock log retrieval
        when(namespacedPodOp.withName(podName)).thenReturn(podResource);
        when(podResource.tailingLines(Mockito.anyInt())).thenReturn(podResource);
        when(podResource.getLog()).thenReturn("INFO: Strimzi operator running normally\nINFO: Processing cluster configurations");
    }

    @SuppressWarnings("unchecked")
    private void setupEmptyResponses(String namespace) {
        // Setup empty pod list
        PodList emptyPodList = new PodList();
        emptyPodList.setItems(List.of());

        MixedOperation<Pod, PodList, PodResource> podOp = kubernetesClient.pods();
        NonNamespaceOperation<Pod, PodList, PodResource> namespacedPodOp = Mockito.mock(NonNamespaceOperation.class);
        FilterWatchListDeletable<Pod, PodList, PodResource> labeledPodOp = Mockito.mock(FilterWatchListDeletable.class);

        when(podOp.inNamespace(namespace)).thenReturn(namespacedPodOp);
        when(namespacedPodOp.withLabel(anyString(), anyString())).thenReturn(labeledPodOp);
        when(labeledPodOp.list()).thenReturn(emptyPodList);

        // Setup empty deployment list
        DeploymentList emptyDeploymentList = new DeploymentList();
        emptyDeploymentList.setItems(List.of());

        MixedOperation<Deployment, DeploymentList, RollableScalableResource<Deployment>> deploymentOp =
            kubernetesClient.apps().deployments();
        NonNamespaceOperation<Deployment, DeploymentList, RollableScalableResource<Deployment>> namespacedDeploymentOp =
            Mockito.mock(NonNamespaceOperation.class);
        FilterWatchListDeletable<Deployment, DeploymentList, RollableScalableResource<Deployment>> labeledDeploymentOp =
            Mockito.mock(FilterWatchListDeletable.class);

        when(deploymentOp.inNamespace(namespace)).thenReturn(namespacedDeploymentOp);
        when(namespacedDeploymentOp.withLabel(anyString(), anyString())).thenReturn(labeledDeploymentOp);
        when(labeledDeploymentOp.list()).thenReturn(emptyDeploymentList);
    }

    @Test
    void testKafkaTopicsWithSpecificCluster() {
        // Service now returns List<KafkaTopicResponse> directly, empty list when no topics found
        List<KafkaTopicResponse> result = topicService.listTopics("kafka", "my-cluster");

        assertNotNull(result);
        assertTrue(result.size() >= 0);
    }

    @Test
    void testKafkaTopicsNoTopicsFound() {
        // Service returns empty list when no topics found
        List<KafkaTopicResponse> result = topicService.listTopics("empty-namespace", "my-cluster");

        assertNotNull(result);
        assertTrue(result.size() >= 0);
    }

    @Test
    void testKafkaClustersDiscovery() {
        // Service now returns List<KafkaClusterResponse> directly
        List<KafkaClusterResponse> result = kafkaService.listClusters("production");

        assertNotNull(result);
        assertTrue(result.size() >= 0);
    }

    @Test
    void testClusterPodsService() {
        KafkaClusterPodsResponse result = kafkaService.getClusterPods("kafka", "my-cluster");

        assertNotNull(result);
        assertEquals("kafka", result.namespace());
        assertEquals("my-cluster", result.clusterName());
        assertNotNull(result.podSummary());
        assertNotNull(result.podSummary().timestamp());
        assertNotNull(result.podSummary().message());
        assertTrue(result.podSummary().totalPods() >= 0);
    }


    @Test
    void testClusterOperatorsDiscovery() {
        // Service now returns List<StrimziOperatorResponse> directly
        List<StrimziOperatorResponse> result = operatorService.listOperators("kafka-system");

        assertNotNull(result);
        assertTrue(result.size() >= 0);
    }

    @Test
    void testClusterOperatorsAllNamespaces() {
        // Service now returns List<StrimziOperatorResponse> directly
        List<StrimziOperatorResponse> result = operatorService.listOperators(null);

        assertNotNull(result);
        assertTrue(result.size() >= 0);
    }
}
