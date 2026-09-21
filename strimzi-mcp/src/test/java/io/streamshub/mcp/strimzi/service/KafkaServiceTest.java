/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.service;

import io.fabric8.kubernetes.api.model.KubernetesResourceList;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
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
import io.streamshub.mcp.strimzi.dto.kafka.KafkaClusterPodsResponse;
import io.streamshub.mcp.strimzi.dto.kafka.KafkaClusterResponse;
import io.streamshub.mcp.strimzi.service.kafka.KafkaService;
import io.strimzi.api.kafka.model.kafka.Kafka;
import io.strimzi.api.kafka.model.kafka.KafkaBuilder;
import io.strimzi.api.kafka.model.kafka.KafkaStatus;
import io.strimzi.api.kafka.model.kafka.KafkaStatusBuilder;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
/**
 * Service-level tests for {@link KafkaService} with mocked Kubernetes.
 */
@QuarkusTest
class KafkaServiceTest {

    private static final String NAMESPACE = "kafka";
    private static final String CLUSTER_NAME = "my-cluster";

    @InjectMock
    KubernetesClient kubernetesClient;

    @Inject
    KafkaService kafkaService;

    @SuppressWarnings("rawtypes")
    private MixedOperation kafkaOp;

    @SuppressWarnings("rawtypes")
    private NonNamespaceOperation kafkaNsOp;

    KafkaServiceTest() {
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

        KubernetesMockHelper.setupEmptyResourceQuery(kubernetesClient, Pod.class);

        kafkaOp = Mockito.mock(MixedOperation.class);
        kafkaNsOp = Mockito.mock(NonNamespaceOperation.class);
        Mockito.lenient().doReturn(kafkaOp).when(kubernetesClient).resources(Kafka.class);
        // Wire inNamespace for any string so listClusters works for any namespace
        Mockito.lenient().doReturn(kafkaNsOp).when(kafkaOp).inNamespace(ArgumentMatchers.anyString());
        Mockito.lenient().doReturn(kafkaNsOp).when(kafkaOp).inAnyNamespace();

        // Default list response: empty
        KubernetesResourceList emptyList = Mockito.mock(KubernetesResourceList.class);
        Mockito.lenient().when(emptyList.getItems()).thenReturn(List.of());
        Mockito.lenient().when(kafkaNsOp.list()).thenReturn(emptyList);

        // Default withName: return null resource
        Resource<Kafka> nullResource = Mockito.mock(Resource.class);
        Mockito.lenient().when(kafkaNsOp.withName(ArgumentMatchers.anyString())).thenReturn(nullResource);
        Mockito.lenient().when(nullResource.get()).thenReturn(null);
    }

    @Test
    void testListClustersReturnsEmptyWhenNoneExist() {
        List<KafkaClusterResponse> result = kafkaService.listClusters("production");

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void testGetClusterPodsReturnsEmptyForMissingCluster() {
        KafkaClusterPodsResponse result = kafkaService.getClusterPods("kafka", "my-cluster");

        assertNotNull(result);
        assertEquals("kafka", result.namespace());
        assertEquals("my-cluster", result.clusterName());
        assertNotNull(result.podSummary());
        assertEquals(0, result.podSummary().totalPods());
    }

    /**
     * Verify running_kafka_version is extracted from Kafka.status.kafkaVersion.
     */
    @Test
    void testGetClusterEnrichesRunningKafkaVersionFromStatus() {
        Kafka kafka = buildKafkaWithStatus(new KafkaStatusBuilder()
            .withKafkaVersion("3.9.0")
            .build());
        mockKafkaResource(kafka);

        KafkaClusterResponse response = kafkaService.getCluster(NAMESPACE, CLUSTER_NAME);

        assertNotNull(response);
        assertEquals("3.9.0", response.runningKafkaVersion());
    }

    /**
     * Verify kafka_version reflects the spec (configured) version while running_kafka_version
     * reflects the actual running version from status, distinguishing the two during an
     * in-progress upgrade where the spec has been bumped ahead of what is actually running.
     */
    @Test
    void testGetClusterDistinguishesConfiguredVersionFromRunningVersionDuringUpgrade() {
        Kafka kafka = new KafkaBuilder()
            .withMetadata(new ObjectMetaBuilder()
                .withName(CLUSTER_NAME)
                .withNamespace(NAMESPACE)
                .build())
            .withNewSpec()
                .withNewKafka()
                    .withVersion("4.0.0")
                .endKafka()
            .endSpec()
            .withStatus(new KafkaStatusBuilder()
                .withKafkaVersion("3.9.0")
                .build())
            .build();
        mockKafkaResource(kafka);

        KafkaClusterResponse response = kafkaService.getCluster(NAMESPACE, CLUSTER_NAME);

        assertEquals("4.0.0", response.kafkaVersion());
        assertEquals("3.9.0", response.runningKafkaVersion());
    }

    /**
     * Verify kafka_metadata_version is extracted from Kafka.status.kafkaMetadataVersion.
     */
    @Test
    void testGetClusterEnrichesKafkaMetadataVersionFromStatus() {
        Kafka kafka = buildKafkaWithStatus(new KafkaStatusBuilder()
            .withKafkaMetadataVersion("3.9-IV0")
            .build());
        mockKafkaResource(kafka);

        KafkaClusterResponse response = kafkaService.getCluster(NAMESPACE, CLUSTER_NAME);

        assertEquals("3.9-IV0", response.kafkaMetadataVersion());
    }

    /**
     * Verify operator_last_successful_version is extracted from
     * Kafka.status.operatorLastSuccessfulVersion.
     */
    @Test
    void testGetClusterEnrichesOperatorLastSuccessfulVersionFromStatus() {
        Kafka kafka = buildKafkaWithStatus(new KafkaStatusBuilder()
            .withOperatorLastSuccessfulVersion("0.44.0")
            .build());
        mockKafkaResource(kafka);

        KafkaClusterResponse response = kafkaService.getCluster(NAMESPACE, CLUSTER_NAME);

        assertEquals("0.44.0", response.operatorLastSuccessfulVersion());
    }

    /**
     * Verify cluster_id is extracted from Kafka.status.clusterId.
     */
    @Test
    void testGetClusterEnrichesClusterIdFromStatus() {
        Kafka kafka = buildKafkaWithStatus(new KafkaStatusBuilder()
            .withClusterId("abcdef-1234")
            .build());
        mockKafkaResource(kafka);

        KafkaClusterResponse response = kafkaService.getCluster(NAMESPACE, CLUSTER_NAME);

        assertEquals("abcdef-1234", response.clusterId());
    }

    /**
     * Verify auto_rebalance is null when status has no autoRebalance.
     */
    @Test
    void testGetClusterAutoRebalanceIsNullWhenNotInStatus() {
        Kafka kafka = buildKafkaWithStatus(new KafkaStatusBuilder().build());
        mockKafkaResource(kafka);

        KafkaClusterResponse response = kafkaService.getCluster(NAMESPACE, CLUSTER_NAME);

        assertNull(response.autoRebalance());
    }

    /**
     * Verify cluster_security is null when status has no clusterSecurity.
     */
    @Test
    void testGetClusterClusterSecurityIsNullWhenNotInStatus() {
        Kafka kafka = buildKafkaWithStatus(new KafkaStatusBuilder().build());
        mockKafkaResource(kafka);

        KafkaClusterResponse response = kafkaService.getCluster(NAMESPACE, CLUSTER_NAME);

        assertNull(response.clusterSecurity());
    }

    /**
     * Verify reconciliation is populated from generation and observedGeneration.
     */
    @Test
    void testGetClusterReconciliationIsPopulated() {
        Kafka kafka = new KafkaBuilder()
            .withMetadata(new ObjectMetaBuilder()
                .withName(CLUSTER_NAME)
                .withNamespace(NAMESPACE)
                .withGeneration(5L)
                .build())
            .withStatus(new KafkaStatusBuilder()
                .withObservedGeneration(5L)
                .build())
            .build();
        mockKafkaResource(kafka);

        KafkaClusterResponse response = kafkaService.getCluster(NAMESPACE, CLUSTER_NAME);

        assertNotNull(response.reconciliation());
        assertEquals(5L, response.reconciliation().generation());
        assertEquals(5L, response.reconciliation().observedGeneration());
        assertTrue(response.reconciliation().upToDate());
    }

    /**
     * Verify all status fields are null when the Kafka resource has no status.
     */
    @Test
    void testGetClusterStatusFieldsNullWhenNoStatus() {
        Kafka kafka = new KafkaBuilder()
            .withMetadata(new ObjectMetaBuilder()
                .withName(CLUSTER_NAME)
                .withNamespace(NAMESPACE)
                .build())
            .build();
        mockKafkaResource(kafka);

        KafkaClusterResponse response = kafkaService.getCluster(NAMESPACE, CLUSTER_NAME);

        assertNull(response.runningKafkaVersion());
        assertNull(response.kafkaMetadataVersion());
        assertNull(response.operatorLastSuccessfulVersion());
        assertNull(response.clusterId());
        assertNull(response.autoRebalance());
        assertNull(response.clusterSecurity());
    }

    @SuppressWarnings("unchecked")
    private void mockKafkaResource(final Kafka kafka) {
        Resource<Kafka> resource = Mockito.mock(Resource.class);
        Mockito.lenient().when(kafkaNsOp.withName(CLUSTER_NAME)).thenReturn(resource);
        Mockito.lenient().when(resource.get()).thenReturn(kafka);
    }

    private Kafka buildKafkaWithStatus(final KafkaStatus status) {
        return new KafkaBuilder()
            .withMetadata(new ObjectMetaBuilder()
                .withName(CLUSTER_NAME)
                .withNamespace(NAMESPACE)
                .build())
            .withStatus(status)
            .build();
    }
}
