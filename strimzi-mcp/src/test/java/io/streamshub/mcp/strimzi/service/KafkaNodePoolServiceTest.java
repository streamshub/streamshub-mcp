/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.service;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.FilterWatchListDeletable;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.NonNamespaceOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.fabric8.kubernetes.client.utils.Serialization;
import io.quarkiverse.mcp.server.McpException;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.streamshub.mcp.common.config.KubernetesConstants;
import io.streamshub.mcp.common.dto.error.McpErrorCategory;
import io.streamshub.mcp.common.dto.error.McpErrorData;
import io.streamshub.mcp.strimzi.dto.kafkanodepool.KafkaNodePoolResponse;
import io.streamshub.mcp.strimzi.service.kafkanodepool.KafkaNodePoolService;
import io.strimzi.api.ResourceLabels;
import io.strimzi.api.kafka.model.kafka.Kafka;
import io.strimzi.api.kafka.model.kafka.KafkaBuilder;
import io.strimzi.api.kafka.model.nodepool.KafkaNodePool;
import io.strimzi.api.kafka.model.nodepool.KafkaNodePoolBuilder;
import io.strimzi.api.kafka.model.nodepool.KafkaNodePoolList;
import io.strimzi.api.kafka.model.nodepool.ProcessRoles;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
/**
 * Service-level tests for {@link KafkaNodePoolService} with mocked Kubernetes.
 */
@QuarkusTest
class KafkaNodePoolServiceTest {

    private static final String NAMESPACE = "kafka";
    private static final String CLUSTER_NAME = "my-cluster";
    private static final String POOL_NAME = "brokers";

    @InjectMock
    KubernetesClient kubernetesClient;

    @Inject
    KafkaNodePoolService nodePoolService;

    @SuppressWarnings("rawtypes")
    private MixedOperation nodePoolOp;

    @SuppressWarnings("rawtypes")
    private NonNamespaceOperation nodePoolNsOp;

    KafkaNodePoolServiceTest() {
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        nodePoolOp = Mockito.mock(MixedOperation.class);
        nodePoolNsOp = Mockito.mock(NonNamespaceOperation.class);
        Mockito.lenient().doReturn(nodePoolOp).when(kubernetesClient).resources(KafkaNodePool.class);
        Mockito.lenient().doReturn(nodePoolNsOp).when(nodePoolOp).inNamespace(NAMESPACE);
        Mockito.lenient().doReturn(nodePoolNsOp).when(nodePoolOp).inAnyNamespace();

        // withLabel support for listNodePools queries
        FilterWatchListDeletable labeledOp = Mockito.mock(FilterWatchListDeletable.class);
        Mockito.lenient().when(nodePoolNsOp.withLabel(
            ArgumentMatchers.anyString(), ArgumentMatchers.anyString())).thenReturn(labeledOp);
        KafkaNodePoolList emptyList = new KafkaNodePoolList();
        emptyList.setItems(List.of());
        Mockito.lenient().when(labeledOp.list()).thenReturn(emptyList);
        Mockito.lenient().when(nodePoolNsOp.list()).thenReturn(emptyList);

        // withName returning null by default (not found)
        Resource<KafkaNodePool> nullResource = Mockito.mock(Resource.class);
        Mockito.lenient().when(nodePoolNsOp.withName(ArgumentMatchers.anyString())).thenReturn(nullResource);
        Mockito.lenient().when(nullResource.get()).thenReturn(null);

        KubernetesMockHelper.setupResourceQuery(kubernetesClient, Kafka.class, List.of(kafka(CLUSTER_NAME, NAMESPACE)));
    }

    /**
     * Verify list returns empty when no node pools exist for the cluster.
     */
    @Test
    void testListNodePoolsReturnsEmptyWhenNoneExist() {
        List<KafkaNodePoolResponse> result = nodePoolService.listNodePools("kafka", "my-cluster");

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    /**
     * Verify list searches all namespaces when namespace is null.
     */
    @Test
    void testListNodePoolsAllNamespaces() {
        List<KafkaNodePoolResponse> result = nodePoolService.listNodePools(null, "my-cluster");

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    /**
     * Verify list throws when cluster name is null.
     */
    @Test
    void testListNodePoolsThrowsWhenClusterNameNull() {
        McpException ex = assertThrows(McpException.class,
            () -> nodePoolService.listNodePools("kafka", null));

        assertTrue(ex.getMessage().contains("required"));
    }

    /**
     * Verify list reports RESOURCE_NOT_FOUND when no Kafka cluster has the requested name,
     * instead of an empty list.
     */
    @Test
    void testListNodePoolsThrowsNotFoundForUnknownCluster() {
        KubernetesMockHelper.setupEmptyResourceQuery(kubernetesClient, Kafka.class);

        McpException ex = assertThrows(McpException.class,
            () -> nodePoolService.listNodePools(NAMESPACE, "missing"));

        assertTrue(ex.getMessage().contains("Kafka cluster 'missing' not found in namespace kafka"), ex.getMessage());
        assertEquals(McpErrorCategory.RESOURCE_NOT_FOUND, ((McpErrorData) ex.getData()).category());
    }

    /**
     * Verify get throws when node pool name is null.
     */
    @Test
    void testGetNodePoolThrowsWhenNameNull() {
        McpException ex = assertThrows(McpException.class,
            () -> nodePoolService.getNodePool("kafka", "my-cluster", null));

        assertTrue(ex.getMessage().contains("required"));
    }

    /**
     * Verify get throws when node pool is not found in namespace.
     */
    @Test
    void testGetNodePoolThrowsWhenNotFoundInNamespace() {
        McpException ex = assertThrows(McpException.class,
            () -> nodePoolService.getNodePool("kafka", "my-cluster", "nonexistent"));

        assertTrue(ex.getMessage().contains("not found"));
    }

    /**
     * Verify get throws when node pool is not found in any namespace.
     */
    @Test
    void testGetNodePoolThrowsWhenNotFoundInAnyNamespace() {
        McpException ex = assertThrows(McpException.class,
            () -> nodePoolService.getNodePool(null, "my-cluster", "nonexistent"));

        assertTrue(ex.getMessage().contains("not found"));
    }

    /**
     * Verify node_ids is extracted from KafkaNodePool.status.nodeIds.
     */
    @Test
    @SuppressWarnings("unchecked")
    void testGetNodePoolEnrichesNodeIdsFromStatus() {
        KafkaNodePool nodePool = buildNodePoolWithStatus(List.of(0, 1, 2));
        mockGetNodePool(nodePool);

        KafkaNodePoolResponse response = nodePoolService.getNodePool(NAMESPACE, CLUSTER_NAME, POOL_NAME);

        assertNotNull(response.nodeIds());
        assertEquals(List.of(0, 1, 2), response.nodeIds());
    }

    /**
     * Verify status_replicas is extracted from KafkaNodePool.status.replicas.
     */
    @Test
    @SuppressWarnings("unchecked")
    void testGetNodePoolEnrichesStatusReplicasFromStatus() {
        KafkaNodePool nodePool = buildNodePoolWithStatus(List.of(0, 1, 2));
        mockGetNodePool(nodePool);

        KafkaNodePoolResponse response = nodePoolService.getNodePool(NAMESPACE, CLUSTER_NAME, POOL_NAME);

        assertEquals(3, response.statusReplicas());
    }

    /**
     * Verify status_roles is extracted from KafkaNodePool.status.roles.
     */
    @Test
    @SuppressWarnings("unchecked")
    void testGetNodePoolEnrichesStatusRolesFromStatus() {
        KafkaNodePool nodePool = buildNodePoolWithStatus(List.of(0, 1, 2));
        mockGetNodePool(nodePool);

        KafkaNodePoolResponse response = nodePoolService.getNodePool(NAMESPACE, CLUSTER_NAME, POOL_NAME);

        assertNotNull(response.statusRoles());
        assertTrue(response.statusRoles().contains("broker"));
    }

    /**
     * Verify status_replicas can diverge from spec replicas mid-scale-operation (e.g. scale-up
     * in progress, where status has not yet caught up with the desired spec replica count).
     */
    @Test
    @SuppressWarnings("unchecked")
    void testGetNodePoolStatusReplicasDivergesFromSpecReplicasDuringScaling() {
        KafkaNodePool nodePool = new KafkaNodePoolBuilder()
            .withNewMetadata()
                .withName(POOL_NAME)
                .withNamespace(NAMESPACE)
                .withLabels(Map.of(ResourceLabels.STRIMZI_CLUSTER_LABEL, CLUSTER_NAME))
            .endMetadata()
            .withNewSpec()
                .withReplicas(5)
                .withRoles(List.of(ProcessRoles.BROKER))
                .withNewEphemeralStorage().endEphemeralStorage()
            .endSpec()
            .withNewStatus()
                .withReplicas(3)
                .withNodeIds(List.of(0, 1, 2))
                .withRoles(List.of(ProcessRoles.BROKER))
            .endStatus()
            .build();
        mockGetNodePool(nodePool);

        KafkaNodePoolResponse response = nodePoolService.getNodePool(NAMESPACE, CLUSTER_NAME, POOL_NAME);

        assertEquals(5, response.replicas());
        assertEquals(3, response.statusReplicas());
    }

    /**
     * Verify conditions are mapped from status and ready is true when the Ready condition
     * status is True (healthy case).
     */
    @Test
    @SuppressWarnings("unchecked")
    void testGetNodePoolReadyTrueAndConditionsMappedWhenReadyConditionTrue() {
        KafkaNodePool nodePool = new KafkaNodePoolBuilder()
            .withNewMetadata()
                .withName(POOL_NAME)
                .withNamespace(NAMESPACE)
                .withLabels(Map.of(ResourceLabels.STRIMZI_CLUSTER_LABEL, CLUSTER_NAME))
            .endMetadata()
            .withNewSpec()
                .withReplicas(3)
                .withRoles(List.of(ProcessRoles.BROKER))
                .withNewEphemeralStorage().endEphemeralStorage()
            .endSpec()
            .withNewStatus()
                .withReplicas(3)
                .withRoles(List.of(ProcessRoles.BROKER))
                .addNewCondition()
                    .withType("Ready").withStatus("True")
                    .withReason("Reconciled").withMessage("Node pool is ready")
                    .withLastTransitionTime("2026-01-01T00:00:00Z")
                .endCondition()
            .endStatus()
            .build();
        mockGetNodePool(nodePool);

        KafkaNodePoolResponse response = nodePoolService.getNodePool(NAMESPACE, CLUSTER_NAME, POOL_NAME);

        assertEquals(Boolean.TRUE, response.ready());
        assertNotNull(response.conditions());
        assertEquals(1, response.conditions().size());
        assertEquals("Ready", response.conditions().get(0).type());
        assertEquals("True", response.conditions().get(0).status());
        assertEquals("Reconciled", response.conditions().get(0).reason());
        assertEquals("Node pool is ready", response.conditions().get(0).message());
        assertEquals("2026-01-01T00:00:00Z", response.conditions().get(0).lastTransitionTime());
    }

    /**
     * Verify ready is false when the Ready condition status is False (not-ready case).
     */
    @Test
    @SuppressWarnings("unchecked")
    void testGetNodePoolReadyFalseWhenReadyConditionFalse() {
        KafkaNodePool nodePool = new KafkaNodePoolBuilder()
            .withNewMetadata()
                .withName(POOL_NAME)
                .withNamespace(NAMESPACE)
                .withLabels(Map.of(ResourceLabels.STRIMZI_CLUSTER_LABEL, CLUSTER_NAME))
            .endMetadata()
            .withNewSpec()
                .withReplicas(3)
                .withRoles(List.of(ProcessRoles.BROKER))
                .withNewEphemeralStorage().endEphemeralStorage()
            .endSpec()
            .withNewStatus()
                .withReplicas(3)
                .withRoles(List.of(ProcessRoles.BROKER))
                .addNewCondition()
                    .withType("Ready").withStatus("False")
                    .withReason("PodNotReady").withMessage("1 out of 3 nodes not ready")
                .endCondition()
            .endStatus()
            .build();
        mockGetNodePool(nodePool);

        KafkaNodePoolResponse response = nodePoolService.getNodePool(NAMESPACE, CLUSTER_NAME, POOL_NAME);

        assertEquals(Boolean.FALSE, response.ready());
    }

    /**
     * Verify an explicit Ready=True condition wins over a replica mismatch that would derive not ready.
     */
    @Test
    @SuppressWarnings("unchecked")
    void testReadyTrueConditionWinsOverReplicaMismatch() {
        KafkaNodePool nodePool = new KafkaNodePoolBuilder()
            .withNewMetadata()
                .withName(POOL_NAME)
                .withNamespace(NAMESPACE)
                .withLabels(Map.of(ResourceLabels.STRIMZI_CLUSTER_LABEL, CLUSTER_NAME))
            .endMetadata()
            .withNewSpec()
                .withReplicas(3)
                .withRoles(List.of(ProcessRoles.BROKER))
                .withNewEphemeralStorage().endEphemeralStorage()
            .endSpec()
            .withNewStatus()
                .withReplicas(2)
                .withRoles(List.of(ProcessRoles.BROKER))
                .addNewCondition()
                    .withType("Ready").withStatus("True")
                .endCondition()
            .endStatus()
            .build();
        mockGetNodePool(nodePool);

        KafkaNodePoolResponse response = nodePoolService.getNodePool(NAMESPACE, CLUSTER_NAME, POOL_NAME);

        assertEquals(Boolean.TRUE, response.ready());
    }

    /**
     * Verify ready is false and conditions is null when the pool has no status (never reconciled).
     */
    @Test
    @SuppressWarnings("unchecked")
    void testReadyFalseAndConditionsNullWhenNoStatus() {
        KafkaNodePool nodePool = new KafkaNodePoolBuilder()
            .withNewMetadata()
                .withName(POOL_NAME)
                .withNamespace(NAMESPACE)
                .withLabels(Map.of(ResourceLabels.STRIMZI_CLUSTER_LABEL, CLUSTER_NAME))
            .endMetadata()
            .withNewSpec()
                .withReplicas(3)
                .withRoles(List.of(ProcessRoles.BROKER))
                .withNewEphemeralStorage().endEphemeralStorage()
            .endSpec()
            .build();
        mockGetNodePool(nodePool);

        KafkaNodePoolResponse response = nodePoolService.getNodePool(NAMESPACE, CLUSTER_NAME, POOL_NAME);

        assertEquals(Boolean.FALSE, response.ready());
        assertNull(response.conditions());
    }

    /**
     * Verify a reconciled pool with empty conditions (the real Strimzi shape) is ready.
     */
    @Test
    void testReadyTrueForReconciledPoolWithEmptyConditions() {
        mockGetNodePool(poolFromJson(1, 3, "1", 3, "[]"));

        assertEquals(Boolean.TRUE, nodePoolService.getNodePool(NAMESPACE, CLUSTER_NAME, POOL_NAME).ready());
    }

    /**
     * Verify a pool whose spec change has not been observed is not ready.
     */
    @Test
    void testReadyFalseWhileSpecChangeNotObserved() {
        mockGetNodePool(poolFromJson(2, 3, "1", 3, "[]"));

        assertEquals(Boolean.FALSE, nodePoolService.getNodePool(NAMESPACE, CLUSTER_NAME, POOL_NAME).ready());
    }

    /**
     * Verify a pool still scaling (status replicas differ from spec) is not ready.
     */
    @Test
    void testReadyFalseWhileScaling() {
        mockGetNodePool(poolFromJson(1, 3, "1", 2, "[]"));

        assertEquals(Boolean.FALSE, nodePoolService.getNodePool(NAMESPACE, CLUSTER_NAME, POOL_NAME).ready());
    }

    /**
     * Verify a pool scaled to zero and reconciled is ready.
     */
    @Test
    void testReadyTrueForPoolScaledToZero() {
        mockGetNodePool(poolFromJson(4, 0, "4", 0, "[]"));

        assertEquals(Boolean.TRUE, nodePoolService.getNodePool(NAMESPACE, CLUSTER_NAME, POOL_NAME).ready());
    }

    /**
     * Verify warning conditions do not affect ready and stay in the conditions list.
     */
    @Test
    void testReadyIgnoresWarningConditions() {
        mockGetNodePool(poolFromJson(1, 3, "1", 3,
            "[{\"type\":\"Warning\",\"status\":\"True\",\"reason\":\"KafkaStorage\","
                + "\"message\":\"Some warning\"}]"));

        KafkaNodePoolResponse response = nodePoolService.getNodePool(NAMESPACE, CLUSTER_NAME, POOL_NAME);

        assertEquals(Boolean.TRUE, response.ready());
        assertEquals(1, response.conditions().size());
        assertEquals("Warning", response.conditions().get(0).type());
    }

    /**
     * Verify the generation check is skipped when status has no observedGeneration.
     */
    @Test
    void testReadyTrueWhenObservedGenerationUnset() {
        mockGetNodePool(poolFromJson(1, 3, null, 3, "[]"));

        assertEquals(Boolean.TRUE, nodePoolService.getNodePool(NAMESPACE, CLUSTER_NAME, POOL_NAME).ready());
    }

    /**
     * Verify an explicit NotReady=True condition makes the pool not ready.
     */
    @Test
    void testReadyFalseWhenNotReadyConditionTrue() {
        mockGetNodePool(poolFromJson(1, 3, "1", 3,
            "[{\"type\":\"NotReady\",\"status\":\"True\"}]"));

        assertEquals(Boolean.FALSE, nodePoolService.getNodePool(NAMESPACE, CLUSTER_NAME, POOL_NAME).ready());
    }

    private KafkaNodePool poolFromJson(final long generation, final int specReplicas,
                                       final String observedGeneration, final int statusReplicas,
                                       final String conditionsJson) {
        String json = "{\"apiVersion\":\"kafka.strimzi.io/v1\",\"kind\":\"KafkaNodePool\","
            + "\"metadata\":{\"name\":\"" + POOL_NAME + "\",\"namespace\":\"" + NAMESPACE + "\","
            + "\"generation\":" + generation + ",\"labels\":{\"strimzi.io/cluster\":\"" + CLUSTER_NAME + "\"}},"
            + "\"spec\":{\"replicas\":" + specReplicas + ",\"roles\":[\"broker\"],"
            + "\"storage\":{\"type\":\"persistent-claim\",\"size\":\"50Gi\"}},"
            + "\"status\":{\"conditions\":" + conditionsJson
            + (observedGeneration != null ? ",\"observedGeneration\":" + observedGeneration : "")
            + ",\"nodeIds\":[3,4,5],\"replicas\":" + statusReplicas
            + ",\"roles\":[\"broker\"],\"clusterId\":\"x\"}}";
        return Serialization.unmarshal(json, KafkaNodePool.class);
    }

    /**
     * Verify reconciliation is populated from generation and observedGeneration.
     */
    @Test
    @SuppressWarnings("unchecked")
    void testGetNodePoolReconciliationIsPopulated() {
        KafkaNodePool nodePool = new KafkaNodePoolBuilder()
            .withNewMetadata()
                .withName(POOL_NAME)
                .withNamespace(NAMESPACE)
                .withLabels(Map.of(ResourceLabels.STRIMZI_CLUSTER_LABEL, CLUSTER_NAME))
                .withGeneration(3L)
            .endMetadata()
            .withNewSpec()
                .withReplicas(1)
                .withRoles(List.of(ProcessRoles.BROKER))
                .withNewEphemeralStorage().endEphemeralStorage()
            .endSpec()
            .withNewStatus()
                .withObservedGeneration(3L)
                .withReplicas(1)
            .endStatus()
            .build();
        mockGetNodePool(nodePool);

        KafkaNodePoolResponse response = nodePoolService.getNodePool(NAMESPACE, CLUSTER_NAME, POOL_NAME);

        assertNotNull(response.reconciliation());
        assertEquals(3L, response.reconciliation().generation());
        assertEquals(3L, response.reconciliation().observedGeneration());
        assertTrue(response.reconciliation().upToDate());
    }

    /**
     * Verify storage_type and storage_size are extracted for persistent-claim storage.
     */
    @Test
    @SuppressWarnings("unchecked")
    void testGetNodePoolEnrichesPersistentClaimStorage() {
        KafkaNodePool nodePool = new KafkaNodePoolBuilder()
            .withNewMetadata()
                .withName(POOL_NAME)
                .withNamespace(NAMESPACE)
                .withLabels(Map.of(ResourceLabels.STRIMZI_CLUSTER_LABEL, CLUSTER_NAME))
            .endMetadata()
            .withNewSpec()
                .withReplicas(3)
                .withRoles(List.of(ProcessRoles.BROKER))
                .withNewPersistentClaimStorage()
                    .withSize("100Gi")
                .endPersistentClaimStorage()
            .endSpec()
            .build();
        mockGetNodePool(nodePool);

        KafkaNodePoolResponse response = nodePoolService.getNodePool(NAMESPACE, CLUSTER_NAME, POOL_NAME);

        assertEquals("persistent-claim", response.storageType());
        assertEquals("100Gi", response.storageSize());
    }

    /**
     * Verify storage_size is the total across JBOD volumes and volumes lists each one.
     */
    @Test
    @SuppressWarnings("unchecked")
    void testGetNodePoolJbodStorageReportsTotalAndVolumes() {
        KafkaNodePool nodePool = new KafkaNodePoolBuilder()
            .withNewMetadata()
                .withName(POOL_NAME)
                .withNamespace(NAMESPACE)
                .withLabels(Map.of(ResourceLabels.STRIMZI_CLUSTER_LABEL, CLUSTER_NAME))
            .endMetadata()
            .withNewSpec()
                .withReplicas(3)
                .withRoles(List.of(ProcessRoles.BROKER))
                .withNewJbodStorage()
                    .addNewPersistentClaimStorageVolume()
                        .withId(0)
                        .withSize("50Gi")
                    .endPersistentClaimStorageVolume()
                    .addNewPersistentClaimStorageVolume()
                        .withId(1)
                        .withSize("50Gi")
                    .endPersistentClaimStorageVolume()
                .endJbodStorage()
            .endSpec()
            .build();
        mockGetNodePool(nodePool);

        KafkaNodePoolResponse response = nodePoolService.getNodePool(NAMESPACE, CLUSTER_NAME, POOL_NAME);

        assertEquals("jbod", response.storageType());
        assertEquals("100Gi", response.storageSize());
        assertEquals(2, response.volumes().size());
    }

    /**
     * Verify storage_type falls back to UNKNOWN and storage_size is null when spec has no storage.
     */
    @Test
    @SuppressWarnings("unchecked")
    void testGetNodePoolStorageUnknownWhenNoStorageInSpec() {
        KafkaNodePool nodePool = new KafkaNodePoolBuilder()
            .withNewMetadata()
                .withName(POOL_NAME)
                .withNamespace(NAMESPACE)
                .withLabels(Map.of(ResourceLabels.STRIMZI_CLUSTER_LABEL, CLUSTER_NAME))
            .endMetadata()
            .withNewSpec()
                .withReplicas(3)
                .withRoles(List.of(ProcessRoles.BROKER))
            .endSpec()
            .build();
        mockGetNodePool(nodePool);

        KafkaNodePoolResponse response = nodePoolService.getNodePool(NAMESPACE, CLUSTER_NAME, POOL_NAME);

        assertEquals(KubernetesConstants.UNKNOWN, response.storageType());
        assertNull(response.storageSize());
    }

    private static Kafka kafka(final String name, final String namespace) {
        return new KafkaBuilder()
            .withNewMetadata().withName(name).withNamespace(namespace).endMetadata()
            .build();
    }

    @SuppressWarnings("unchecked")
    private void mockGetNodePool(final KafkaNodePool nodePool) {
        Resource<KafkaNodePool> resource = Mockito.mock(Resource.class);
        Mockito.lenient().when(nodePoolNsOp.withName(POOL_NAME)).thenReturn(resource);
        Mockito.lenient().when(resource.get()).thenReturn(nodePool);

        // Also make inAnyNamespace work for null-namespace path
        Mockito.lenient().when(nodePoolOp.inAnyNamespace()).thenReturn(nodePoolNsOp);
        KafkaNodePoolList list = new KafkaNodePoolList();
        list.setItems(List.of(nodePool));
        Mockito.lenient().when(nodePoolNsOp.list()).thenReturn(list);
    }

    private KafkaNodePool buildNodePoolWithStatus(final List<Integer> nodeIds) {
        return new KafkaNodePoolBuilder()
            .withNewMetadata()
                .withName(POOL_NAME)
                .withNamespace(NAMESPACE)
                .withLabels(Map.of(ResourceLabels.STRIMZI_CLUSTER_LABEL, CLUSTER_NAME))
            .endMetadata()
            .withNewSpec()
                .withReplicas(nodeIds.size())
                .withRoles(List.of(ProcessRoles.BROKER))
                .withNewEphemeralStorage().endEphemeralStorage()
            .endSpec()
            .withNewStatus()
                .withReplicas(nodeIds.size())
                .withNodeIds(nodeIds)
                .withRoles(List.of(ProcessRoles.BROKER))
            .endStatus()
            .build();
    }
}
