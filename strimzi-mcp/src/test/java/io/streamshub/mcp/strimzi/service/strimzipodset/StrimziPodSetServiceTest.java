/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.service.strimzipodset;

import io.fabric8.kubernetes.api.model.OwnerReference;
import io.fabric8.kubernetes.api.model.OwnerReferenceBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.quarkiverse.mcp.server.McpException;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.streamshub.mcp.common.service.KubernetesResourceService;
import io.streamshub.mcp.strimzi.config.StrimziConstants;
import io.streamshub.mcp.strimzi.dto.strimzipodset.StrimziPodSetResponse;
import io.streamshub.mcp.strimzi.service.kafka.KafkaService;
import io.strimzi.api.ResourceLabels;
import io.strimzi.api.kafka.model.kafka.Kafka;
import io.strimzi.api.kafka.model.kafka.KafkaBuilder;
import io.strimzi.api.kafka.model.podset.StrimziPodSet;
import io.strimzi.api.kafka.model.podset.StrimziPodSetBuilder;
import io.strimzi.api.kafka.model.podset.StrimziPodSetSpec;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link StrimziPodSetService}.
 */
@QuarkusTest
class StrimziPodSetServiceTest {

    private static final String NAMESPACE = "kafka";
    private static final String CLUSTER_NAME = "my-cluster";
    private static final String CONTROLLERS = "my-cluster-controllers";
    private static final String BROKERS = "my-cluster-brokers";
    private static final String REV_CURRENT = "rev-a";
    private static final String REV_DESIRED = "rev-b";

    @InjectMock
    KafkaService kafkaService;

    @InjectMock
    KubernetesResourceService k8sService;

    @Inject
    StrimziPodSetService podSetService;

    StrimziPodSetServiceTest() {
    }

    @BeforeEach
    void setUp() {
        when(kafkaService.findKafkaCluster(NAMESPACE, CLUSTER_NAME)).thenReturn(buildKafka());
    }

    @Test
    void testAllPodsOnDesiredRevisionOmitsRevisionMap() {
        // desired revision == live revision for every pod -> fully rolled out
        mockPodSets(buildPodSet(BROKERS, 3, 3, 3,
            specPod("my-cluster-brokers-0", REV_CURRENT),
            specPod("my-cluster-brokers-1", REV_CURRENT),
            specPod("my-cluster-brokers-2", REV_CURRENT)));
        mockPods(
            buildPod("my-cluster-brokers-0", BROKERS, REV_CURRENT),
            buildPod("my-cluster-brokers-1", BROKERS, REV_CURRENT),
            buildPod("my-cluster-brokers-2", BROKERS, REV_CURRENT)
        );

        List<StrimziPodSetResponse> result = podSetService.listPodSets(NAMESPACE, CLUSTER_NAME);

        assertEquals(1, result.size());
        StrimziPodSetResponse brokers = result.getFirst();
        assertEquals(BROKERS, brokers.name());
        assertEquals(NAMESPACE, brokers.namespace());
        assertEquals(CLUSTER_NAME, brokers.cluster());
        assertEquals(3, brokers.pods());
        assertEquals(3, brokers.readyPods());
        assertEquals(3, brokers.currentPods());
        assertNull(brokers.podRevisions(),
            "Revision map must be omitted when every pod is on the desired revision");
    }

    @Test
    void testStalePodsIncludedInRevisionMap() {
        // pod-0 desired revision differs from its live revision -> stale (still on old config)
        mockPodSets(buildPodSet(BROKERS, 3, 2, 2,
            specPod("my-cluster-brokers-0", REV_DESIRED),
            specPod("my-cluster-brokers-1", REV_CURRENT),
            specPod("my-cluster-brokers-2", REV_CURRENT)));
        mockPods(
            buildPod("my-cluster-brokers-0", BROKERS, REV_CURRENT),
            buildPod("my-cluster-brokers-1", BROKERS, REV_CURRENT),
            buildPod("my-cluster-brokers-2", BROKERS, REV_CURRENT)
        );

        List<StrimziPodSetResponse> result = podSetService.listPodSets(NAMESPACE, CLUSTER_NAME);

        StrimziPodSetResponse brokers = result.getFirst();
        assertNotNull(brokers.podRevisions(), "Revision map must be present during a rolling update");
        assertEquals(1, brokers.podRevisions().size(), "Only the stale pod should be listed");
        assertEquals(REV_CURRENT, brokers.podRevisions().get("my-cluster-brokers-0"),
            "Stale pod should map to its current (outdated) revision");
        assertNull(brokers.podRevisions().get("my-cluster-brokers-1"),
            "Up-to-date pods must not appear in the revision map");
    }

    @Test
    void testStalenessIsScopedPerPodSet() {
        mockPodSets(
            buildPodSet(CONTROLLERS, 3, 3, 3,
                specPod("my-cluster-controllers-0", REV_CURRENT),
                specPod("my-cluster-controllers-1", REV_CURRENT),
                specPod("my-cluster-controllers-2", REV_CURRENT)),
            buildPodSet(BROKERS, 3, 2, 2,
                specPod("my-cluster-brokers-0", REV_DESIRED),
                specPod("my-cluster-brokers-1", REV_CURRENT),
                specPod("my-cluster-brokers-2", REV_CURRENT)));
        mockPods(
            // controllers fully rolled out
            buildPod("my-cluster-controllers-0", CONTROLLERS, REV_CURRENT),
            buildPod("my-cluster-controllers-1", CONTROLLERS, REV_CURRENT),
            buildPod("my-cluster-controllers-2", CONTROLLERS, REV_CURRENT),
            // brokers mid-rollout (pod-0 still on old revision)
            buildPod("my-cluster-brokers-0", BROKERS, REV_CURRENT),
            buildPod("my-cluster-brokers-1", BROKERS, REV_CURRENT),
            buildPod("my-cluster-brokers-2", BROKERS, REV_CURRENT)
        );

        List<StrimziPodSetResponse> result = podSetService.listPodSets(NAMESPACE, CLUSTER_NAME);

        assertEquals(2, result.size());
        StrimziPodSetResponse controllers = findByName(result, CONTROLLERS);
        StrimziPodSetResponse brokers = findByName(result, BROKERS);
        assertNull(controllers.podRevisions(), "Controllers are fully rolled out, so no revision map");
        assertNotNull(brokers.podRevisions(), "Brokers are mid-rollout, so a revision map is present");
        assertEquals(1, brokers.podRevisions().size());
        assertTrue(brokers.podRevisions().containsKey("my-cluster-brokers-0"));
    }

    @Test
    void testDistinctRevisionsAloneDoNotSignalRollout() {
        // Every pod has a distinct live revision (the normal steady state, since the revision hashes the
        // whole pod). With matching desired revisions, nothing is stale and no map is emitted.
        mockPodSets(buildPodSet(BROKERS, 3, 3, 3,
            specPod("my-cluster-brokers-0", "rev-0"),
            specPod("my-cluster-brokers-1", "rev-1"),
            specPod("my-cluster-brokers-2", "rev-2")));
        mockPods(
            buildPod("my-cluster-brokers-0", BROKERS, "rev-0"),
            buildPod("my-cluster-brokers-1", BROKERS, "rev-1"),
            buildPod("my-cluster-brokers-2", BROKERS, "rev-2")
        );

        List<StrimziPodSetResponse> result = podSetService.listPodSets(NAMESPACE, CLUSTER_NAME);

        assertNull(result.getFirst().podRevisions(),
            "Per-pod distinct revisions are normal; only a mismatch with the desired revision is a rollout");
    }

    @Test
    void testReturnsEmptyWhenNoPodSets() {
        mockPodSets();
        mockPods();

        List<StrimziPodSetResponse> result = podSetService.listPodSets(NAMESPACE, CLUSTER_NAME);

        assertTrue(result.isEmpty());
    }

    @Test
    void testNullStatusReportsZeroCounters() {
        StrimziPodSet podSet = new StrimziPodSetBuilder()
            .withNewMetadata()
                .withName(BROKERS)
                .withNamespace(NAMESPACE)
                .withLabels(Map.of(ResourceLabels.STRIMZI_CLUSTER_LABEL, CLUSTER_NAME))
            .endMetadata()
            .build();
        StrimziPodSetSpec spec = new StrimziPodSetSpec();
        spec.setPods(List.of(specPod("my-cluster-brokers-0", REV_CURRENT)));
        podSet.setSpec(spec);
        mockPodSets(podSet);
        mockPods(buildPod("my-cluster-brokers-0", BROKERS, REV_CURRENT));

        List<StrimziPodSetResponse> result = podSetService.listPodSets(NAMESPACE, CLUSTER_NAME);

        StrimziPodSetResponse brokers = result.getFirst();
        assertEquals(0, brokers.pods());
        assertEquals(0, brokers.readyPods());
        assertEquals(0, brokers.currentPods());
        assertNull(brokers.podRevisions());
    }

    @Test
    void testThrowsWhenClusterNameNull() {
        assertThrows(McpException.class, () -> podSetService.listPodSets(NAMESPACE, null));
    }

    @Test
    void testThrowsWhenClusterNotFound() {
        when(kafkaService.findKafkaCluster(NAMESPACE, "nonexistent"))
            .thenThrow(new McpException("Kafka cluster 'nonexistent' not found", -32002));

        assertThrows(McpException.class, () -> podSetService.listPodSets(NAMESPACE, "nonexistent"));
    }

    @Test
    void testIgnoresPodsOwnedByOtherPodSets() {
        mockPodSets(buildPodSet(BROKERS, 2, 2, 2,
            specPod("my-cluster-brokers-0", REV_CURRENT),
            specPod("my-cluster-brokers-1", REV_CURRENT)));
        mockPods(
            buildPod("my-cluster-brokers-0", BROKERS, REV_CURRENT),
            buildPod("my-cluster-brokers-1", BROKERS, REV_CURRENT),
            // a stale pod owned by a different pod set must not affect this pod set
            buildPod("my-cluster-controllers-0", CONTROLLERS, "rev-old")
        );

        List<StrimziPodSetResponse> result = podSetService.listPodSets(NAMESPACE, CLUSTER_NAME);

        assertNull(result.getFirst().podRevisions(),
            "A foreign pod set's pod must not count toward this pod set's staleness");
    }

    private void mockPodSets(final StrimziPodSet... podSets) {
        when(k8sService.queryResourcesByLabel(
            eq(StrimziPodSet.class), eq(NAMESPACE), eq(ResourceLabels.STRIMZI_CLUSTER_LABEL), eq(CLUSTER_NAME)))
            .thenReturn(List.of(podSets));
    }

    private void mockPods(final Pod... pods) {
        when(k8sService.queryResourcesByLabel(
            eq(Pod.class), eq(NAMESPACE), eq(ResourceLabels.STRIMZI_CLUSTER_LABEL), eq(CLUSTER_NAME)))
            .thenReturn(List.of(pods));
    }

    private static StrimziPodSetResponse findByName(final List<StrimziPodSetResponse> items, final String name) {
        return items.stream().filter(i -> name.equals(i.name())).findFirst().orElseThrow();
    }

    private Kafka buildKafka() {
        return new KafkaBuilder()
            .withNewMetadata()
                .withName(CLUSTER_NAME)
                .withNamespace(NAMESPACE)
            .endMetadata()
            .build();
    }

    @SafeVarargs
    private StrimziPodSet buildPodSet(final String name, final int pods, final int readyPods,
                                      final int currentPods, final Map<String, Object>... specPods) {
        StrimziPodSet podSet = new StrimziPodSetBuilder()
            .withNewMetadata()
                .withName(name)
                .withNamespace(NAMESPACE)
                .withLabels(Map.of(ResourceLabels.STRIMZI_CLUSTER_LABEL, CLUSTER_NAME))
            .endMetadata()
            .withNewStatus()
                .withPods(pods)
                .withReadyPods(readyPods)
                .withCurrentPods(currentPods)
            .endStatus()
            .build();
        StrimziPodSetSpec spec = new StrimziPodSetSpec();
        spec.setPods(List.of(specPods));
        podSet.setSpec(spec);
        return podSet;
    }

    /**
     * Build a {@code spec.pods[]} entry carrying the desired revision for a pod, mirroring what the operator
     * records (pod metadata with a {@code strimzi.io/revision} annotation).
     */
    private static Map<String, Object> specPod(final String podName, final String desiredRevision) {
        return Map.of("metadata", Map.of(
            "name", podName,
            "annotations", Map.of(StrimziConstants.Annotations.REVISION, desiredRevision)));
    }

    private Pod buildPod(final String podName, final String ownerPodSet, final String revision) {
        OwnerReference ownerReference = new OwnerReferenceBuilder()
            .withKind(StrimziPodSet.RESOURCE_KIND)
            .withName(ownerPodSet)
            .withApiVersion("core.strimzi.io/v1beta2")
            .withUid(ownerPodSet + "-uid")
            .build();

        return new PodBuilder()
            .withNewMetadata()
                .withName(podName)
                .withNamespace(NAMESPACE)
                .withLabels(Map.of(ResourceLabels.STRIMZI_CLUSTER_LABEL, CLUSTER_NAME))
                .withAnnotations(Map.of(StrimziConstants.Annotations.REVISION, revision))
                .withOwnerReferences(ownerReference)
            .endMetadata()
            .build();
    }
}
