/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.service.kafkanodepool;

import io.fabric8.kubernetes.api.model.PersistentVolumeClaim;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaimBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.quarkiverse.mcp.server.McpException;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.streamshub.mcp.strimzi.dto.kafkanodepool.KafkaNodePoolDiagnosticReport;
import io.streamshub.mcp.strimzi.dto.kafkanodepool.KafkaNodePoolPvcStatus;
import io.streamshub.mcp.strimzi.service.KubernetesMockHelper;
import io.strimzi.api.ResourceLabels;
import io.strimzi.api.kafka.model.nodepool.KafkaNodePool;
import io.strimzi.api.kafka.model.nodepool.KafkaNodePoolBuilder;
import io.strimzi.api.kafka.model.nodepool.ProcessRoles;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
/**
 * Service-level tests for {@link KafkaNodePoolDiagnosticService} with mocked Kubernetes.
 */
@QuarkusTest
class KafkaNodePoolDiagnosticServiceTest {

    private static final String NAMESPACE = "kafka";
    private static final String CLUSTER_NAME = "my-cluster";
    private static final String POOL_NAME = "broker-np";

    @InjectMock
    KubernetesClient kubernetesClient;

    @Inject
    KafkaNodePoolDiagnosticService diagnosticService;

    KafkaNodePoolDiagnosticServiceTest() {
    }

    @BeforeEach
    void setUp() {
        KubernetesMockHelper.setupEmptyResourceQuery(kubernetesClient, KafkaNodePool.class);
        KubernetesMockHelper.setupEmptyResourceQuery(kubernetesClient, Pod.class);
        KubernetesMockHelper.setupEmptyResourceQuery(kubernetesClient, PersistentVolumeClaim.class);
    }

    /**
     * Verify diagnose rejects a missing node pool name before touching Kubernetes.
     */
    @Test
    void testThrowsWhenNodePoolNameNull() {
        assertThrows(McpException.class, () ->
            diagnosticService.diagnose(NAMESPACE, null, CLUSTER_NAME, null, null,
                null, null, null, null));
    }

    /**
     * Verify diagnose fails when the node pool does not exist.
     */
    @Test
    void testThrowsWhenNodePoolNotFound() {
        assertThrows(McpException.class, () ->
            diagnosticService.diagnose(NAMESPACE, "nonexistent", CLUSTER_NAME, null, null,
                null, null, null, null));
    }

    /**
     * Verify the full workflow completes without Sampling, gathering phase 1 and
     * falling back to all phase 2 areas.
     */
    @Test
    void testFullWorkflowWithoutSampling() {
        KubernetesMockHelper.setupResourceQuery(kubernetesClient, KafkaNodePool.class,
            List.of(readyNodePool()));

        KafkaNodePoolDiagnosticReport report = diagnosticService.diagnose(
            NAMESPACE, POOL_NAME, CLUSTER_NAME, null, null,
            null, null, null, null);

        assertNotNull(report);
        assertNotNull(report.nodePool());
        assertTrue(report.stepsCompleted().contains("node_pool"),
            "Node pool status is a phase 1 step and must always be attempted");
        assertTrue(report.stepsCompleted().contains("pods"),
            "Pod health is a phase 1 step and must always be attempted");
        for (String area : List.of("pvcs", "events", "logs")) {
            assertTrue(attempted(report, area),
                "Without Sampling every phase 2 area runs, so '" + area
                    + "' must appear in steps_completed or steps_failed");
        }
        assertNull(report.analysis(), "No analysis without Sampling support");
        assertNotNull(report.timestamp());
    }

    /**
     * Verify a failing phase 2 step (PVC lookup) is recorded in steps_failed instead of
     * aborting the report.
     */
    @Test
    void testPvcsFailureDoesNotAbortWorkflow() {
        KubernetesMockHelper.setupResourceQuery(kubernetesClient, KafkaNodePool.class,
            List.of(readyNodePool()));
        Mockito.lenient().when(kubernetesClient.resources(PersistentVolumeClaim.class))
            .thenThrow(new RuntimeException("PVC query failed"));

        KafkaNodePoolDiagnosticReport report = diagnosticService.diagnose(
            NAMESPACE, POOL_NAME, CLUSTER_NAME, "storage pending", 30,
            null, null, null, null);

        assertNotNull(report);
        assertNotNull(report.message());
        assertTrue(report.stepsCompleted().contains("node_pool"));
        assertTrue(report.stepsFailed() != null
                && report.stepsFailed().stream().anyMatch(s -> s.startsWith("pvcs:")),
            "A failing PVC gather step must be recorded in steps_failed, not abort the workflow");
    }

    /**
     * Verify PVC matching is anchored on the {@code -<pool>-<nodeId>} suffix, so a sibling pool
     * whose name starts with this pool's name does not leak its claims into the report.
     */
    @Test
    void testPvcsExcludeSiblingPoolWithPrefixName() {
        KubernetesMockHelper.setupResourceQuery(kubernetesClient, KafkaNodePool.class,
            List.of(readyNodePool()));
        KubernetesMockHelper.setupResourceQuery(kubernetesClient, PersistentVolumeClaim.class,
            List.of(pvc("data-" + CLUSTER_NAME + "-" + POOL_NAME + "-0"),
                pvc("data-0-" + CLUSTER_NAME + "-" + POOL_NAME + "-1"),
                pvc("data-" + CLUSTER_NAME + "-" + POOL_NAME + "-2-0")));

        KafkaNodePoolDiagnosticReport report = diagnosticService.diagnose(
            NAMESPACE, POOL_NAME, CLUSTER_NAME, null, null,
            null, null, null, null);

        assertNotNull(report.pvcs());
        assertEquals(List.of("data-" + CLUSTER_NAME + "-" + POOL_NAME + "-0",
                "data-0-" + CLUSTER_NAME + "-" + POOL_NAME + "-1"),
            report.pvcs().stream().map(KafkaNodePoolPvcStatus::name).toList(),
            "Only this pool's claims belong in the report; 'broker-np-2' is a different pool");
    }

    private static PersistentVolumeClaim pvc(final String name) {
        return new PersistentVolumeClaimBuilder()
            .withNewMetadata()
                .withName(name)
                .withNamespace(NAMESPACE)
                .withLabels(Map.of(ResourceLabels.STRIMZI_CLUSTER_LABEL, CLUSTER_NAME))
            .endMetadata()
            .withNewStatus()
                .withPhase("Bound")
            .endStatus()
            .build();
    }

    private static boolean attempted(final KafkaNodePoolDiagnosticReport report, final String step) {
        if (report.stepsCompleted().contains(step)) {
            return true;
        }
        return report.stepsFailed() != null
            && report.stepsFailed().stream().anyMatch(s -> s.startsWith(step + ":"));
    }

    private static KafkaNodePool readyNodePool() {
        return new KafkaNodePoolBuilder()
            .withNewMetadata()
                .withName(POOL_NAME)
                .withNamespace(NAMESPACE)
                .withLabels(Map.of(ResourceLabels.STRIMZI_CLUSTER_LABEL, CLUSTER_NAME))
            .endMetadata()
            .withNewSpec()
                .withReplicas(1)
                .withRoles(List.of(ProcessRoles.BROKER))
                .withNewEphemeralStorage().endEphemeralStorage()
            .endSpec()
            .withNewStatus()
                .withReplicas(1)
                .withNodeIds(List.of(0))
                .withRoles(List.of(ProcessRoles.BROKER))
                .addNewCondition()
                    .withType("Ready")
                    .withStatus("True")
                .endCondition()
            .endStatus()
            .build();
    }
}
