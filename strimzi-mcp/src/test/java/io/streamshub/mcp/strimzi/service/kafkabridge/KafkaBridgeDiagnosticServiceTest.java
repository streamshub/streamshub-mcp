/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.service.kafkabridge;

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodList;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.PodResource;
import io.quarkiverse.mcp.server.McpException;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.streamshub.mcp.strimzi.dto.kafkabridge.KafkaBridgeDiagnosticReport;
import io.streamshub.mcp.strimzi.service.KubernetesMockHelper;
import io.strimzi.api.kafka.model.bridge.KafkaBridge;
import io.strimzi.api.kafka.model.bridge.KafkaBridgeBuilder;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
/**
 * Service-level tests for {@link KafkaBridgeDiagnosticService} with mocked Kubernetes.
 */
@QuarkusTest
class KafkaBridgeDiagnosticServiceTest {

    @InjectMock
    KubernetesClient kubernetesClient;

    @Inject
    KafkaBridgeDiagnosticService diagnosticService;

    KafkaBridgeDiagnosticServiceTest() {
    }

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        MixedOperation<Pod, PodList, PodResource> podOp = Mockito.mock(MixedOperation.class);
        Mockito.lenient().when(kubernetesClient.pods()).thenReturn(podOp);

        KubernetesMockHelper.setupEmptyResourceQuery(kubernetesClient, KafkaBridge.class);
        KubernetesMockHelper.setupEmptyResourceQuery(kubernetesClient, Pod.class);
    }

    /**
     * Verify diagnose rejects a missing bridge name before touching Kubernetes.
     */
    @Test
    void testThrowsWhenBridgeNameNull() {
        assertThrows(McpException.class, () ->
            diagnosticService.diagnose("kafka", null, null, null,
                null, null, null, null));
    }

    /**
     * Verify diagnose fails when the bridge does not exist.
     */
    @Test
    void testThrowsWhenBridgeNotFound() {
        assertThrows(McpException.class, () ->
            diagnosticService.diagnose("kafka", "nonexistent", null, null,
                null, null, null, null));
    }

    /**
     * Verify the full workflow completes without Sampling, gathering phase 1 and
     * falling back to all phase 2 areas.
     */
    @Test
    void testFullWorkflowWithoutSampling() {
        KubernetesMockHelper.setupResourceQuery(kubernetesClient, KafkaBridge.class,
            List.of(readyBridge()));

        KafkaBridgeDiagnosticReport report = diagnosticService.diagnose(
            "kafka", "my-bridge", null, null,
            null, null, null, null);

        assertNotNull(report);
        assertNotNull(report.bridge());
        assertTrue(report.stepsCompleted().contains("bridge_status"),
            "Bridge status is a phase 1 step and must always be attempted");
        assertTrue(report.stepsCompleted().contains("bridge_pods"),
            "Pod health is a phase 1 step and must always be attempted");
        for (String area : List.of("bridge_logs", "events", "metrics")) {
            assertTrue(attempted(report, area),
                "Without Sampling every phase 2 area runs, so '" + area
                    + "' must appear in steps_completed or steps_failed");
        }
        assertNull(report.analysis(), "No analysis without Sampling support");
        assertNotNull(report.timestamp());
    }

    /**
     * Verify a failing phase 2 step is recorded in steps_failed instead of aborting the report.
     */
    @Test
    void testMetricsFailureDoesNotAbortWorkflow() {
        KubernetesMockHelper.setupResourceQuery(kubernetesClient, KafkaBridge.class,
            List.of(readyBridge()));

        KafkaBridgeDiagnosticReport report = diagnosticService.diagnose(
            "kafka", "my-bridge", "HTTP 500 from the bridge", 10,
            null, null, null, null);

        assertNotNull(report);
        assertNotNull(report.message());
        assertTrue(report.stepsCompleted().contains("bridge_status"));
        assertTrue(attempted(report, "metrics"),
            "Metrics gathering must either complete or degrade into steps_failed");
    }

    private static boolean attempted(final KafkaBridgeDiagnosticReport report, final String step) {
        if (report.stepsCompleted().contains(step)) {
            return true;
        }
        return report.stepsFailed() != null
            && report.stepsFailed().stream().anyMatch(s -> s.startsWith(step + ":"));
    }

    private static KafkaBridge readyBridge() {
        return new KafkaBridgeBuilder()
            .withNewMetadata()
                .withName("my-bridge")
                .withNamespace("kafka")
            .endMetadata()
            .withNewSpec()
                .withBootstrapServers("my-cluster-kafka-bootstrap:9092")
                .withReplicas(1)
                .withNewHttp(8080)
            .endSpec()
            .withNewStatus()
                .addNewCondition()
                    .withType("Ready")
                    .withStatus("True")
                .endCondition()
                .withUrl("http://my-bridge-bridge-service.kafka.svc:8080")
            .endStatus()
            .build();
    }
}
