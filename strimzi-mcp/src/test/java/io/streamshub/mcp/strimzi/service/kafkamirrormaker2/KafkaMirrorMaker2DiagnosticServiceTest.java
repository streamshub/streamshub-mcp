/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.service.kafkamirrormaker2;

import io.quarkiverse.mcp.server.McpException;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.streamshub.mcp.common.util.McpErrors;
import io.streamshub.mcp.strimzi.dto.kafkamirrormaker2.KafkaMirrorMaker2DiagnosticReport;
import io.streamshub.mcp.strimzi.dto.kafkamirrormaker2.KafkaMirrorMaker2Response;
import io.streamshub.mcp.strimzi.service.operator.StrimziEventsService;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Service-level tests for {@link KafkaMirrorMaker2DiagnosticService} covering the stateful/no-Sampling
 * orchestration and per-gather graceful degradation. The stateless MRTR paths are covered separately by
 * {@link KafkaMirrorMaker2DiagnosticServiceStatelessTest}.
 */
@QuarkusTest
class KafkaMirrorMaker2DiagnosticServiceTest {

    private static final String NS = "kafka";
    private static final String NAME = "my-mm2";

    @InjectMock
    KafkaMirrorMaker2Service mirrorMakerService;

    @InjectMock
    StrimziEventsService eventsService;

    @Inject
    KafkaMirrorMaker2DiagnosticService diagnosticService;

    KafkaMirrorMaker2DiagnosticServiceTest() {
    }

    @Test
    void testThrowsWhenMirrorMakerNameNull() {
        assertThrows(McpException.class, () ->
            diagnosticService.diagnose(NS, null, null, null, null, null, null, null));
    }

    @Test
    void testPropagatesNotFound() {
        when(mirrorMakerService.getMirrorMaker(any(), anyString()))
            .thenThrow(McpErrors.notFound("KafkaMirrorMaker2", NAME, NS));

        assertThrows(McpException.class, () ->
            diagnosticService.diagnose(NS, NAME, null, null, null, null, null, null));
    }

    @Test
    void testFullWorkflowWithoutSamplingGathersAllSteps() {
        when(mirrorMakerService.getMirrorMaker(any(), anyString())).thenReturn(readyMirrorMaker());

        KafkaMirrorMaker2DiagnosticReport report = diagnosticService.diagnose(
            NS, NAME, null, null, null, null, null, null);

        assertNotNull(report);
        assertNotNull(report.mirrorMaker());
        assertTrue(report.stepsCompleted().contains("mm2_status"));
        assertTrue(report.stepsCompleted().contains("mm2_pods"));
        assertTrue(report.stepsCompleted().contains("mm2_logs"));
        assertTrue(report.stepsCompleted().contains("events"));
        assertNull(report.analysis(), "No analysis without Sampling support");
        assertNotNull(report.timestamp());
    }

    @Test
    void testGatherFailureRecordedInStepsFailed() {
        when(mirrorMakerService.getMirrorMaker(any(), anyString())).thenReturn(readyMirrorMaker());
        when(mirrorMakerService.getMirrorMakerPods(any(), anyString()))
            .thenThrow(new RuntimeException("API server unreachable"));

        KafkaMirrorMaker2DiagnosticReport report = diagnosticService.diagnose(
            NS, NAME, null, null, null, null, null, null);

        assertNotNull(report, "A gather failure must not abort the workflow");
        assertTrue(report.stepsCompleted().contains("mm2_status"));
        assertNotNull(report.stepsFailed());
        assertFalse(report.stepsFailed().isEmpty());
        assertTrue(report.stepsFailed().stream().anyMatch(s -> s.startsWith("mm2_pods:")),
            "The failed pod gather must be recorded in steps_failed");
    }

    private static KafkaMirrorMaker2Response readyMirrorMaker() {
        return KafkaMirrorMaker2Response.of(
            NAME, NS, "Ready", null, null, null, null, null, null,
            "4.2.0", null, List.of(), null, null, null);
    }
}
