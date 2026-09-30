/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.service.kafkarebalance;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.quarkiverse.mcp.server.McpException;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.streamshub.mcp.common.dto.PodLogsResult;
import io.streamshub.mcp.common.service.log.LogCollectionService;
import io.streamshub.mcp.strimzi.dto.kafkarebalance.KafkaRebalanceDiagnosticReport;
import io.streamshub.mcp.strimzi.service.KubernetesMockHelper;
import io.strimzi.api.ResourceLabels;
import io.strimzi.api.kafka.model.rebalance.KafkaRebalance;
import io.strimzi.api.kafka.model.rebalance.KafkaRebalanceBuilder;
import io.strimzi.api.kafka.model.rebalance.KafkaRebalanceMode;
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
import static org.mockito.ArgumentMatchers.any;

/**
 * Service-level tests for {@link KafkaRebalanceDiagnosticService} with mocked Kubernetes.
 */
@QuarkusTest
class KafkaRebalanceDiagnosticServiceTest {

    private static final String NAMESPACE = "kafka";
    private static final String CLUSTER_NAME = "my-cluster";
    private static final String REBALANCE_NAME = "my-rebalance";
    private static final String PROGRESS_CM_NAME = "strimzi-rebalance-progress-12345";

    @InjectMock
    KubernetesClient kubernetesClient;

    @InjectMock
    LogCollectionService logCollectionService;

    @Inject
    KafkaRebalanceDiagnosticService diagnosticService;

    KafkaRebalanceDiagnosticServiceTest() {
    }

    @BeforeEach
    void setUp() {
        KubernetesMockHelper.setupEmptyResourceQuery(kubernetesClient, KafkaRebalance.class);
        KubernetesMockHelper.setupEmptyResourceQuery(kubernetesClient, Pod.class);
        KubernetesMockHelper.setupEmptyResourceQuery(kubernetesClient, ConfigMap.class);
        Mockito.lenient().when(logCollectionService.collectLogs(any(), any(), any()))
            .thenReturn(new PodLogsResult(List.of(), "No logs", 0, 0, 0, false, 0, List.of()));
    }

    /**
     * Verify diagnose rejects a missing rebalance name before touching Kubernetes.
     */
    @Test
    void testThrowsWhenRebalanceNameNull() {
        assertThrows(McpException.class, () ->
            diagnosticService.diagnose(NAMESPACE, null, null, null,
                null, null, null, null));
    }

    /**
     * Verify diagnose fails when the rebalance does not exist.
     */
    @Test
    void testThrowsWhenRebalanceNotFound() {
        assertThrows(McpException.class, () ->
            diagnosticService.diagnose(NAMESPACE, "nonexistent", null, null,
                null, null, null, null));
    }

    /**
     * Verify the full workflow completes without Sampling, gathering phase 1 and
     * falling back to all phase 2 areas.
     */
    @Test
    void testFullWorkflowWithoutSampling() {
        KubernetesMockHelper.setupResourceQuery(kubernetesClient, KafkaRebalance.class,
            List.of(rebalancingRebalance()));

        KafkaRebalanceDiagnosticReport report = diagnosticService.diagnose(
            NAMESPACE, REBALANCE_NAME, null, null,
            null, null, null, null);

        assertNotNull(report);
        assertNotNull(report.rebalance());
        assertEquals(REBALANCE_NAME, report.rebalance().name());
        assertEquals("Rebalancing", report.rebalance().state());
        assertTrue(report.stepsCompleted().contains("rebalance_status"),
            "Rebalance status is a phase 1 step and must always be attempted");

        for (String area : List.of("progress_config_map", "cruise_control_logs", "events")) {
            assertTrue(attempted(report, area),
                "Without Sampling every phase 2 area runs, so '" + area
                    + "' must appear in steps_completed or steps_failed");
        }
        assertNull(report.analysis(), "No analysis without Sampling support");
        assertNotNull(report.timestamp());
    }

    /**
     * Verify progress ConfigMap data is gathered when referenced in status.
     */
    @Test
    void testProgressConfigMapGathered() {
        KubernetesMockHelper.setupResourceQuery(kubernetesClient, KafkaRebalance.class,
            List.of(rebalancingRebalance()));
        KubernetesMockHelper.setupResourceQuery(kubernetesClient, ConfigMap.class,
            List.of(progressConfigMap()));

        KafkaRebalanceDiagnosticReport report = diagnosticService.diagnose(
            NAMESPACE, REBALANCE_NAME, null, null,
            null, null, null, null);

        assertNotNull(report);
        assertNotNull(report.progressConfigMap());
        assertEquals(PROGRESS_CM_NAME, report.progressConfigMap().get("name"));
        assertTrue(report.stepsCompleted().contains("progress_config_map"));
    }

    /**
     * Verify a failing phase 2 step does not abort the workflow.
     */
    @Test
    void testStepFailureDoesNotAbortWorkflow() {
        KubernetesMockHelper.setupResourceQuery(kubernetesClient, KafkaRebalance.class,
            List.of(rebalancingRebalance()));
        Mockito.lenient().when(kubernetesClient.resources(ConfigMap.class))
            .thenThrow(new RuntimeException("ConfigMap query failed"));

        KafkaRebalanceDiagnosticReport report = diagnosticService.diagnose(
            NAMESPACE, REBALANCE_NAME, "stuck rebalance", 30,
            null, null, null, null);

        assertNotNull(report);
        assertNotNull(report.message());
        assertTrue(report.stepsCompleted().contains("rebalance_status"));
        assertTrue(report.stepsFailed() != null
                && report.stepsFailed().stream().anyMatch(s -> s.startsWith("progress_config_map:")),
            "A failing step must be recorded in steps_failed, not abort the workflow");
    }

    private static boolean attempted(final KafkaRebalanceDiagnosticReport report, final String step) {
        if (report.stepsCompleted().contains(step)) {
            return true;
        }
        return report.stepsFailed() != null
            && report.stepsFailed().stream().anyMatch(s -> s.startsWith(step + ":"));
    }

    private static KafkaRebalance rebalancingRebalance() {
        return new KafkaRebalanceBuilder()
            .withNewMetadata()
                .withName(REBALANCE_NAME)
                .withNamespace(NAMESPACE)
                .withLabels(Map.of(ResourceLabels.STRIMZI_CLUSTER_LABEL, CLUSTER_NAME))
            .endMetadata()
            .withNewSpec()
                .withMode(KafkaRebalanceMode.FULL)
            .endSpec()
            .withNewStatus()
                .withSessionId("session-abc-123")
                .withNewProgress()
                    .withRebalanceProgressConfigMap(PROGRESS_CM_NAME)
                .endProgress()
                .addNewCondition()
                    .withType("Rebalancing")
                    .withStatus("True")
                .endCondition()
            .endStatus()
            .build();
    }

    private static ConfigMap progressConfigMap() {
        return new ConfigMapBuilder()
            .withNewMetadata()
                .withName(PROGRESS_CM_NAME)
                .withNamespace(NAMESPACE)
            .endMetadata()
            .withData(Map.of("progress", "{\"percentage\": 50}"))
            .build();
    }
}
