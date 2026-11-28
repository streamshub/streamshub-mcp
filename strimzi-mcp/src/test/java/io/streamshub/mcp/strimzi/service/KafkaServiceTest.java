/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.service;

import io.fabric8.kubernetes.api.model.KubernetesResourceList;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaim;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaimBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodList;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentList;
import io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicy;
import io.fabric8.kubernetes.api.model.policy.v1.PodDisruptionBudget;
import io.fabric8.kubernetes.api.model.storage.StorageClass;
import io.fabric8.kubernetes.api.model.storage.StorageClassBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.dsl.AppsAPIGroupDSL;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.NonNamespaceOperation;
import io.fabric8.kubernetes.client.dsl.PodResource;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.fabric8.kubernetes.client.dsl.RollableScalableResource;
import io.fabric8.kubernetes.client.utils.Serialization;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.streamshub.mcp.strimzi.dto.kafka.KafkaClusterPodsResponse;
import io.streamshub.mcp.strimzi.dto.kafka.KafkaClusterPoliciesResponse;
import io.streamshub.mcp.strimzi.dto.kafka.KafkaClusterResponse;
import io.streamshub.mcp.strimzi.dto.kafka.KafkaPvcResponse;
import io.streamshub.mcp.strimzi.service.kafka.KafkaService;
import io.strimzi.api.kafka.model.kafka.Kafka;
import io.strimzi.api.kafka.model.kafka.KafkaBuilder;
import io.strimzi.api.kafka.model.kafka.KafkaStatus;
import io.strimzi.api.kafka.model.kafka.KafkaStatusBuilder;
import io.strimzi.api.kafka.model.kafka.clustersecurity.ClusterSecurityAuthenticationType;
import io.strimzi.api.kafka.model.kafka.clustersecurity.ClusterSecurityEncryptionType;
import io.strimzi.api.kafka.model.kafka.clustersecurity.ClusterSecurityStatus;
import io.strimzi.api.kafka.model.kafka.clustersecurity.ClusterSecurityStatusBuilder;
import io.strimzi.api.kafka.model.kafka.cruisecontrol.KafkaAutoRebalanceMode;
import io.strimzi.api.kafka.model.kafka.cruisecontrol.KafkaAutoRebalanceState;
import io.strimzi.api.kafka.model.kafka.cruisecontrol.KafkaAutoRebalanceStatusBrokersBuilder;
import io.strimzi.api.kafka.model.kafka.listener.GenericKafkaListenerBuilder;
import io.strimzi.api.kafka.model.kafka.listener.KafkaListenerAuthenticationTls;
import io.strimzi.api.kafka.model.kafka.listener.KafkaListenerType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;
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
        KubernetesMockHelper.setupEmptyResourceQuery(kubernetesClient, PodDisruptionBudget.class);
        KubernetesMockHelper.setupEmptyResourceQuery(kubernetesClient, NetworkPolicy.class);
        KubernetesMockHelper.setupEmptyResourceQuery(kubernetesClient, PersistentVolumeClaim.class);

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
            .withKafkaVersion("4.2.0")
            .build());
        mockKafkaResource(kafka);

        KafkaClusterResponse response = kafkaService.getCluster(NAMESPACE, CLUSTER_NAME);

        assertNotNull(response);
        assertEquals("4.2.0", response.runningKafkaVersion());
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
                    .withVersion("4.3.0")
                .endKafka()
            .endSpec()
            .withStatus(new KafkaStatusBuilder()
                .withKafkaVersion("4.2.0")
                .build())
            .build();
        mockKafkaResource(kafka);

        KafkaClusterResponse response = kafkaService.getCluster(NAMESPACE, CLUSTER_NAME);

        assertEquals("4.3.0", response.kafkaVersion());
        assertEquals("4.2.0", response.runningKafkaVersion());
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
     * Verify auto_rebalance state, modes, and last_transition_time are extracted from
     * Kafka.status.autoRebalance when populated.
     */
    @Test
    void testGetClusterEnrichesAutoRebalanceFromStatus() {
        Kafka kafka = buildKafkaWithStatus(new KafkaStatusBuilder()
            .withNewAutoRebalance()
                .withState(KafkaAutoRebalanceState.RebalanceOnScaleUp)
                .withLastTransitionTime("2026-01-01T00:00:00Z")
                .withModes(new KafkaAutoRebalanceStatusBrokersBuilder()
                    .withMode(KafkaAutoRebalanceMode.ADD_BROKERS)
                    .withBrokers(3, 4)
                    .build())
            .endAutoRebalance()
            .build());
        mockKafkaResource(kafka);

        KafkaClusterResponse response = kafkaService.getCluster(NAMESPACE, CLUSTER_NAME);

        assertNotNull(response.autoRebalance());
        assertEquals("RebalanceOnScaleUp", response.autoRebalance().state());
        assertEquals(List.of("add-brokers"), response.autoRebalance().modes());
        assertEquals("2026-01-01T00:00:00Z", response.autoRebalance().lastTransitionTime());
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
     * Verify cluster_security encryption/authentication are extracted from
     * Kafka.status.clusterSecurity when populated, using the CRD's serialized values
     * (e.g. "strimzi-tls") rather than the Java enum constant name ("TLS").
     */
    @Test
    void testGetClusterEnrichesClusterSecurityFromTypedStatus() {
        ClusterSecurityStatus clusterSecurityStatus = new ClusterSecurityStatusBuilder()
            .withNewEncryption()
                .withType(ClusterSecurityEncryptionType.TLS)
            .endEncryption()
            .withNewAuthentication()
                .withType(ClusterSecurityAuthenticationType.MTLS)
            .endAuthentication()
            .build();
        Kafka kafka = buildKafkaWithStatus(new KafkaStatusBuilder()
            .withClusterSecurity(clusterSecurityStatus)
            .build());
        mockKafkaResource(kafka);

        KafkaClusterResponse response = kafkaService.getCluster(NAMESPACE, CLUSTER_NAME);

        assertNotNull(response.clusterSecurity());
        assertEquals("strimzi-tls", response.clusterSecurity().encryption());
        assertEquals("strimzi-mtls", response.clusterSecurity().authentication());
    }

    /**
     * Verify cluster_security is read when the Kafka comes from the client's own
     * deserializer, where status.clusterSecurity is an untyped map rather than a typed status.
     */
    @Test
    void testGetClusterEnrichesClusterSecurityFromDeserializedStatus() {
        String json = "{\"apiVersion\":\"kafka.strimzi.io/v1\",\"kind\":\"Kafka\","
            + "\"metadata\":{\"name\":\"my-cluster\",\"namespace\":\"kafka\"},"
            + "\"spec\":{\"kafka\":{\"version\":\"4.3.1\",\"listeners\":"
            + "[{\"name\":\"tls\",\"port\":9093,\"type\":\"internal\",\"tls\":true}]}},"
            + "\"status\":{\"clusterSecurity\":{\"authentication\":{\"type\":\"strimzi-mtls\"},"
            + "\"encryption\":{\"type\":\"strimzi-tls\"}}}}";
        Kafka kafka = Serialization.unmarshal(json, Kafka.class);
        assertInstanceOf(Map.class, kafka.getStatus().getClusterSecurity());
        mockKafkaResource(kafka);

        KafkaClusterResponse response = kafkaService.getCluster(NAMESPACE, CLUSTER_NAME);

        assertNotNull(response.clusterSecurity());
        assertEquals("strimzi-tls", response.clusterSecurity().encryption());
        assertEquals("strimzi-mtls", response.clusterSecurity().authentication());
    }

    /**
     * Verify unknown future type strings are passed through unchanged and a missing
     * block yields null.
     */
    @Test
    void testGetClusterClusterSecurityPassesThroughUnknownTypes() {
        String json = "{\"apiVersion\":\"kafka.strimzi.io/v1\",\"kind\":\"Kafka\","
            + "\"metadata\":{\"name\":\"my-cluster\",\"namespace\":\"kafka\"},"
            + "\"status\":{\"clusterSecurity\":{\"authentication\":{\"type\":\"custom-future\"}}}}";
        Kafka kafka = Serialization.unmarshal(json, Kafka.class);
        mockKafkaResource(kafka);

        KafkaClusterResponse response = kafkaService.getCluster(NAMESPACE, CLUSTER_NAME);

        assertNotNull(response.clusterSecurity());
        assertEquals("custom-future", response.clusterSecurity().authentication());
        assertNull(response.clusterSecurity().encryption());
    }

    /**
     * Verify an empty deserialized clusterSecurity object yields no cluster_security.
     */
    @Test
    void testGetClusterClusterSecurityIsNullForEmptyObject() {
        Kafka kafka = deserializedKafka("{}");
        assertInstanceOf(Map.class, kafka.getStatus().getClusterSecurity());
        mockKafkaResource(kafka);

        assertNull(kafkaService.getCluster(NAMESPACE, CLUSTER_NAME).clusterSecurity());
    }

    /**
     * Verify a JSON null or absent clusterSecurity yields no cluster_security.
     */
    @Test
    void testGetClusterClusterSecurityIsNullForJsonNullOrAbsent() {
        assertNull(clusterSecurityOfDeserialized("null"));
        assertNull(clusterSecurityOfDeserialized(null));
    }

    /**
     * Verify encryption is reported on its own when the status carries no authentication.
     */
    @Test
    void testGetClusterClusterSecurityWithEncryptionOnly() {
        KafkaClusterResponse.ClusterSecurityInfo security =
            clusterSecurityOfDeserialized("{\"encryption\":{\"type\":\"strimzi-tls\"}}");

        assertNotNull(security);
        assertEquals("strimzi-tls", security.encryption());
        assertNull(security.authentication());
    }

    /**
     * Verify a non-string type is dropped: the field is null, and the whole block is null when
     * nothing else is set.
     */
    @Test
    void testGetClusterClusterSecurityIgnoresNonStringType() {
        assertNull(clusterSecurityOfDeserialized("{\"encryption\":{\"type\":42}}"));

        KafkaClusterResponse.ClusterSecurityInfo security = clusterSecurityOfDeserialized(
            "{\"encryption\":{\"type\":42},\"authentication\":{\"type\":\"strimzi-mtls\"}}");

        assertNotNull(security);
        assertNull(security.encryption());
        assertEquals("strimzi-mtls", security.authentication());
    }

    /**
     * Verify list_kafka_clusters reports cluster_security from the client's deserialized status.
     */
    @Test
    @SuppressWarnings("unchecked")
    void testListClustersEnrichesClusterSecurityFromDeserializedStatus() {
        Kafka kafka = deserializedKafka(
            "{\"authentication\":{\"type\":\"strimzi-mtls\"},\"encryption\":{\"type\":\"strimzi-tls\"}}");
        KubernetesResourceList<Kafka> kafkaList = Mockito.mock(KubernetesResourceList.class);
        when(kafkaList.getItems()).thenReturn(List.of(kafka));
        when(kafkaNsOp.list()).thenReturn(kafkaList);

        List<KafkaClusterResponse> result = kafkaService.listClusters(NAMESPACE);

        assertEquals(1, result.size());
        assertNotNull(result.getFirst().clusterSecurity());
        assertEquals("strimzi-tls", result.getFirst().clusterSecurity().encryption());
        assertEquals("strimzi-mtls", result.getFirst().clusterSecurity().authentication());
    }

    /**
     * Runs get_kafka_cluster on a Kafka parsed by the client's deserializer and returns its cluster_security.
     *
     * @param clusterSecurityJson the raw {@code status.clusterSecurity} JSON value, or null to omit the field
     * @return the cluster_security of the response
     */
    private KafkaClusterResponse.ClusterSecurityInfo clusterSecurityOfDeserialized(final String clusterSecurityJson) {
        mockKafkaResource(deserializedKafka(clusterSecurityJson));
        return kafkaService.getCluster(NAMESPACE, CLUSTER_NAME).clusterSecurity();
    }

    /**
     * Parses a Kafka with the client's deserializer, as the API server would return it.
     *
     * @param clusterSecurityJson the raw {@code status.clusterSecurity} JSON value, or null to omit the field
     * @return the deserialized Kafka
     */
    private Kafka deserializedKafka(final String clusterSecurityJson) {
        String status = clusterSecurityJson != null
            ? "{\"clusterSecurity\":" + clusterSecurityJson + "}"
            : "{}";
        String json = "{\"apiVersion\":\"kafka.strimzi.io/v1\",\"kind\":\"Kafka\","
            + "\"metadata\":{\"name\":\"" + CLUSTER_NAME + "\",\"namespace\":\"" + NAMESPACE + "\"},"
            + "\"spec\":{\"kafka\":{\"version\":\"4.3.1\",\"listeners\":"
            + "[{\"name\":\"tls\",\"port\":9093,\"type\":\"internal\",\"tls\":true}]}},"
            + "\"status\":" + status + "}";
        return Serialization.unmarshal(json, Kafka.class);
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

    @Test
    void testGetClusterPolicies() {
        mockKafkaResource(buildKafkaWithStatus(new KafkaStatusBuilder().build()));

        KafkaClusterPoliciesResponse response = kafkaService.getClusterPolicies(NAMESPACE, CLUSTER_NAME);
        assertNotNull(response);
        assertEquals(CLUSTER_NAME, response.clusterName());
        assertEquals(NAMESPACE, response.namespace());
        assertNotNull(response.podDisruptionBudgets());
        assertNotNull(response.networkPolicies());
    }

    @Test
    void testGetClusterPvcs() {
        mockKafkaResource(buildKafkaWithStatus(new KafkaStatusBuilder().build()));

        KafkaPvcResponse response = kafkaService.getClusterPvcs(NAMESPACE, CLUSTER_NAME);
        assertNotNull(response);
        assertEquals(CLUSTER_NAME, response.clusterName());
        assertEquals(NAMESPACE, response.namespace());
        assertNotNull(response.pvcs());
    }

    /**
     * Verify listeners without authentication are named in unauthenticated_listeners, while
     * authentication_enabled keeps its "any listener has auth" meaning.
     */
    @Test
    void testGetClusterListsUnauthenticatedListeners() {
        Kafka kafka = new KafkaBuilder()
            .withMetadata(new ObjectMetaBuilder()
                .withName(CLUSTER_NAME)
                .withNamespace(NAMESPACE)
                .build())
            .withNewSpec()
                .withNewKafka()
                    .withListeners(
                        new GenericKafkaListenerBuilder()
                            .withName("plain").withPort(9092)
                            .withType(KafkaListenerType.INTERNAL).withTls(false)
                            .build(),
                        new GenericKafkaListenerBuilder()
                            .withName("tls").withPort(9093)
                            .withType(KafkaListenerType.INTERNAL).withTls(true)
                            .withAuth(new KafkaListenerAuthenticationTls())
                            .build())
                .endKafka()
            .endSpec()
            .build();
        mockKafkaResource(kafka);

        KafkaClusterResponse response = kafkaService.getCluster(NAMESPACE, CLUSTER_NAME);

        assertTrue(response.authenticationEnabled());
        assertEquals(List.of("plain"), response.unauthenticatedListeners());
    }

    /**
     * Verify unauthenticated_listeners is empty when every listener has authentication.
     */
    @Test
    void testGetClusterUnauthenticatedListenersEmptyWhenAllAuthenticated() {
        Kafka kafka = new KafkaBuilder()
            .withMetadata(new ObjectMetaBuilder()
                .withName(CLUSTER_NAME)
                .withNamespace(NAMESPACE)
                .build())
            .withNewSpec()
                .withNewKafka()
                    .withListeners(new GenericKafkaListenerBuilder()
                        .withName("tls").withPort(9093)
                        .withType(KafkaListenerType.INTERNAL).withTls(true)
                        .withAuth(new KafkaListenerAuthenticationTls())
                        .build())
                .endKafka()
            .endSpec()
            .build();
        mockKafkaResource(kafka);

        KafkaClusterResponse response = kafkaService.getCluster(NAMESPACE, CLUSTER_NAME);

        assertTrue(response.unauthenticatedListeners().isEmpty());
    }

    @Test
    void testGetClusterPvcsReportsAllowVolumeExpansion() {
        mockPvcsWithStorageClass();

        KafkaPvcResponse response = kafkaService.getClusterPvcs(NAMESPACE, CLUSTER_NAME);

        assertEquals(2, response.pvcs().size());
        assertEquals(Boolean.TRUE, response.pvcs().get(0).allowVolumeExpansion());
        assertEquals(Boolean.TRUE, response.pvcs().get(1).allowVolumeExpansion());
    }

    @Test
    void testGetClusterPvcsLooksUpEachStorageClassOnce() {
        mockPvcsWithStorageClass();
        Mockito.clearInvocations(kubernetesClient);

        kafkaService.getClusterPvcs(NAMESPACE, CLUSTER_NAME);

        Mockito.verify(kubernetesClient, Mockito.times(1)).resources(StorageClass.class);
    }

    @Test
    void testGetClusterPvcsOmitsExpansionWhenStorageClassUnreadable() {
        mockPvcsWithStorageClass();
        when(kubernetesClient.resources(StorageClass.class))
            .thenThrow(new KubernetesClientException("forbidden", 403, null));

        KafkaPvcResponse response = kafkaService.getClusterPvcs(NAMESPACE, CLUSTER_NAME);

        assertEquals(2, response.pvcs().size());
        assertNull(response.pvcs().get(0).allowVolumeExpansion());
        assertNull(response.pvcs().get(1).allowVolumeExpansion());
    }

    @Test
    void testGetClusterPvcsReportsFalseWhenStorageClassLeavesExpansionUnset() {
        mockPvcsWithStorageClass();
        StorageClass sc = new StorageClassBuilder()
            .withNewMetadata().withName("rook-ceph-block").endMetadata()
            .withProvisioner("rook-ceph.rbd.csi.ceph.com")
            .build();
        KubernetesMockHelper.setupResourceQuery(kubernetesClient, StorageClass.class, List.of(sc));

        KafkaPvcResponse response = kafkaService.getClusterPvcs(NAMESPACE, CLUSTER_NAME);

        assertEquals(2, response.pvcs().size());
        assertEquals(Boolean.FALSE, response.pvcs().get(0).allowVolumeExpansion());
        assertEquals(Boolean.FALSE, response.pvcs().get(1).allowVolumeExpansion());
    }

    @Test
    void testGetClusterPvcsOmitsExpansionOnlyForUnreadableStorageClass() {
        mockPvcsWithStorageClass();
        KubernetesMockHelper.setupResourceQuery(kubernetesClient, PersistentVolumeClaim.class, List.of(
            pvcWithStorageClass("data-0-mcp-cluster-broker-0", "rook-ceph-block"),
            pvcWithStorageClass("data-1-mcp-cluster-broker-0", "missing-class")));

        KafkaPvcResponse response = kafkaService.getClusterPvcs(NAMESPACE, CLUSTER_NAME);

        assertEquals(2, response.pvcs().size());
        assertEquals(Boolean.TRUE, response.pvcs().get(0).allowVolumeExpansion());
        assertNull(response.pvcs().get(1).allowVolumeExpansion());
    }

    private void mockPvcsWithStorageClass() {
        mockKafkaResource(buildKafkaWithStatus(new KafkaStatusBuilder().build()));
        KubernetesMockHelper.setupResourceQuery(kubernetesClient, PersistentVolumeClaim.class, List.of(
            pvcWithStorageClass("data-0-mcp-cluster-broker-0"),
            pvcWithStorageClass("data-1-mcp-cluster-broker-0")));
        StorageClass sc = new StorageClassBuilder()
            .withNewMetadata().withName("rook-ceph-block").endMetadata()
            .withProvisioner("rook-ceph.rbd.csi.ceph.com")
            .withAllowVolumeExpansion(true)
            .build();
        KubernetesMockHelper.setupResourceQuery(kubernetesClient, StorageClass.class, List.of(sc));
    }

    private PersistentVolumeClaim pvcWithStorageClass(final String name) {
        return pvcWithStorageClass(name, "rook-ceph-block");
    }

    private PersistentVolumeClaim pvcWithStorageClass(final String name, final String storageClassName) {
        return new PersistentVolumeClaimBuilder()
            .withNewMetadata()
                .withName(name)
                .withNamespace(NAMESPACE)
                .addToLabels("strimzi.io/cluster", CLUSTER_NAME)
            .endMetadata()
            .withNewSpec().withStorageClassName(storageClassName).endSpec()
            .build();
    }

    @Test
    void testGetAuthorizationTypeReturnsSimpleWhenConfigured() {
        Kafka kafka = new KafkaBuilder()
            .withNewMetadata()
                .withName(CLUSTER_NAME)
                .withNamespace(NAMESPACE)
            .endMetadata()
            .withNewSpec()
                .withNewKafka()
                    .withNewKafkaAuthorizationSimple()
                    .endKafkaAuthorizationSimple()
                .endKafka()
            .endSpec()
            .build();
        mockKafkaResource(kafka);

        assertEquals("simple", kafkaService.getAuthorizationType(NAMESPACE, CLUSTER_NAME));
    }

    @Test
    void testGetAuthorizationTypeReturnsNullWhenAbsent() {
        Kafka kafka = new KafkaBuilder()
            .withNewMetadata()
                .withName(CLUSTER_NAME)
                .withNamespace(NAMESPACE)
            .endMetadata()
            .withNewSpec()
                .withNewKafka()
                .endKafka()
            .endSpec()
            .build();
        mockKafkaResource(kafka);

        assertNull(kafkaService.getAuthorizationType(NAMESPACE, CLUSTER_NAME));
    }
}
