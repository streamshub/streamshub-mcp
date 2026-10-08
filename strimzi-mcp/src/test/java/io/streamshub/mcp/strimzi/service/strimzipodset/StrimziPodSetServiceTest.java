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
    void testAllPodsOnSameRevisionOmitsRevisionMap() {
        mockPodSets(buildPodSet(BROKERS, 3, 3, 3));
        mockPods(
            buildPod("my-cluster-brokers-0", BROKERS, "rev-a"),
            buildPod("my-cluster-brokers-1", BROKERS, "rev-a"),
            buildPod("my-cluster-brokers-2", BROKERS, "rev-a")
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
        assertNull(brokers.podRevisions(), "Revision map must be omitted when all pods share one revision");
    }

    @Test
    void testMixedRevisionsIncludesRevisionMap() {
        mockPodSets(buildPodSet(BROKERS, 3, 2, 2));
        mockPods(
            buildPod("my-cluster-brokers-0", BROKERS, "rev-b"),
            buildPod("my-cluster-brokers-1", BROKERS, "rev-a"),
            buildPod("my-cluster-brokers-2", BROKERS, "rev-a")
        );

        List<StrimziPodSetResponse> result = podSetService.listPodSets(NAMESPACE, CLUSTER_NAME);

        StrimziPodSetResponse brokers = result.getFirst();
        assertNotNull(brokers.podRevisions(), "Revision map must be present during a rolling update");
        assertEquals(3, brokers.podRevisions().size());
        assertEquals("rev-b", brokers.podRevisions().get("my-cluster-brokers-0"));
        assertEquals("rev-a", brokers.podRevisions().get("my-cluster-brokers-1"));
        assertEquals(2, brokers.currentPods());
    }

    @Test
    void testRevisionsAreScopedPerPodSetViaOwnerReference() {
        mockPodSets(buildPodSet(CONTROLLERS, 3, 3, 3), buildPodSet(BROKERS, 3, 2, 2));
        mockPods(
            // controllers all on the same revision
            buildPod("my-cluster-controllers-0", CONTROLLERS, "rev-a"),
            buildPod("my-cluster-controllers-1", CONTROLLERS, "rev-a"),
            buildPod("my-cluster-controllers-2", CONTROLLERS, "rev-a"),
            // brokers mid-rollout
            buildPod("my-cluster-brokers-0", BROKERS, "rev-b"),
            buildPod("my-cluster-brokers-1", BROKERS, "rev-a"),
            buildPod("my-cluster-brokers-2", BROKERS, "rev-a")
        );

        List<StrimziPodSetResponse> result = podSetService.listPodSets(NAMESPACE, CLUSTER_NAME);

        assertEquals(2, result.size());
        StrimziPodSetResponse controllers = findByName(result, CONTROLLERS);
        StrimziPodSetResponse brokers = findByName(result, BROKERS);
        assertNull(controllers.podRevisions(), "Controllers are all current, so no revision map");
        assertNotNull(brokers.podRevisions(), "Brokers are mid-rollout, so a revision map is present");
        assertEquals(3, brokers.podRevisions().size());
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
        mockPodSets(new StrimziPodSetBuilder()
            .withNewMetadata()
                .withName(BROKERS)
                .withNamespace(NAMESPACE)
                .withLabels(Map.of(ResourceLabels.STRIMZI_CLUSTER_LABEL, CLUSTER_NAME))
            .endMetadata()
            .build());
        mockPods(buildPod("my-cluster-brokers-0", BROKERS, "rev-a"));

        List<StrimziPodSetResponse> result = podSetService.listPodSets(NAMESPACE, CLUSTER_NAME);

        StrimziPodSetResponse brokers = result.getFirst();
        assertEquals(0, brokers.pods());
        assertEquals(0, brokers.readyPods());
        assertEquals(0, brokers.currentPods());
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
        mockPodSets(buildPodSet(BROKERS, 2, 2, 2));
        mockPods(
            buildPod("my-cluster-brokers-0", BROKERS, "rev-a"),
            buildPod("my-cluster-brokers-1", BROKERS, "rev-a"),
            // a pod with a different revision but owned by a different pod set must not trigger a rollout map
            buildPod("my-cluster-controllers-0", CONTROLLERS, "rev-z")
        );

        List<StrimziPodSetResponse> result = podSetService.listPodSets(NAMESPACE, CLUSTER_NAME);

        assertNull(result.getFirst().podRevisions(),
            "A foreign pod set's pod must not count toward this pod set's revisions");
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

    private StrimziPodSet buildPodSet(final String name, final int pods, final int readyPods, final int currentPods) {
        return new StrimziPodSetBuilder()
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
