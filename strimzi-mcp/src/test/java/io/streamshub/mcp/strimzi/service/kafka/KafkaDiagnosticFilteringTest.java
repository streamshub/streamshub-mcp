/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.service.kafka;

import io.quarkus.test.junit.QuarkusTest;
import io.streamshub.mcp.common.dto.LogCollectionParams;
import io.streamshub.mcp.common.dto.PodSummaryResponse;
import io.streamshub.mcp.strimzi.dto.kafka.KafkaClusterPodsResponse;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for pod filtering and time window logic in {@link KafkaClusterDiagnosticService}.
 */
@QuarkusTest
class KafkaDiagnosticFilteringTest {

    @Inject
    KafkaClusterDiagnosticService diagnosticService;

    KafkaDiagnosticFilteringTest() {
    }

    // ---- Pod filtering tests ----

    @Test
    void testIdentifyProblematicPodsAllHealthy() {
        List<PodSummaryResponse.PodInfo> pods = List.of(
            PodSummaryResponse.PodInfo.summary("pod-0", "Running", true, "kafka", 0, 100),
            PodSummaryResponse.PodInfo.summary("pod-1", "Running", true, "kafka", 0, 100),
            PodSummaryResponse.PodInfo.summary("pod-2", "Running", true, "kafka", 0, 100)
        );
        KafkaClusterPodsResponse podsResponse = KafkaClusterPodsResponse.of(
            "my-cluster", "kafka", PodSummaryResponse.of("kafka", pods));

        Set<String> result = diagnosticService.identifyProblematicPods(podsResponse);
        assertTrue(result.isEmpty());
    }

    @Test
    void testIdentifyProblematicPodsNotReady() {
        List<PodSummaryResponse.PodInfo> pods = List.of(
            PodSummaryResponse.PodInfo.summary("pod-0", "Running", true, "kafka", 0, 100),
            PodSummaryResponse.PodInfo.summary("pod-1", "Running", false, "kafka", 0, 100),
            PodSummaryResponse.PodInfo.summary("pod-2", "Running", true, "kafka", 0, 100)
        );
        KafkaClusterPodsResponse podsResponse = KafkaClusterPodsResponse.of(
            "my-cluster", "kafka", PodSummaryResponse.of("kafka", pods));

        Set<String> result = diagnosticService.identifyProblematicPods(podsResponse);
        assertEquals(Set.of("pod-1"), result);
    }

    @Test
    void testIdentifyProblematicPodsNotRunning() {
        List<PodSummaryResponse.PodInfo> pods = List.of(
            PodSummaryResponse.PodInfo.summary("pod-0", "Running", true, "kafka", 0, 100),
            PodSummaryResponse.PodInfo.summary("pod-1", "Pending", false, "kafka", 0, 5),
            PodSummaryResponse.PodInfo.summary("pod-2", "Failed", false, "kafka", 0, 100)
        );
        KafkaClusterPodsResponse podsResponse = KafkaClusterPodsResponse.of(
            "my-cluster", "kafka", PodSummaryResponse.of("kafka", pods));

        Set<String> result = diagnosticService.identifyProblematicPods(podsResponse);
        assertEquals(Set.of("pod-1", "pod-2"), result);
    }

    @Test
    void testIdentifyProblematicPodsHighRestarts() {
        List<PodSummaryResponse.PodInfo> pods = List.of(
            PodSummaryResponse.PodInfo.summary("pod-0", "Running", true, "kafka", 0, 100),
            PodSummaryResponse.PodInfo.summary("pod-1", "Running", true, "kafka", 5, 100),
            PodSummaryResponse.PodInfo.summary("pod-2", "Running", true, "kafka", 0, 100)
        );
        KafkaClusterPodsResponse podsResponse = KafkaClusterPodsResponse.of(
            "my-cluster", "kafka", PodSummaryResponse.of("kafka", pods));

        Set<String> result = diagnosticService.identifyProblematicPods(podsResponse);
        assertEquals(Set.of("pod-1"), result);
    }

    @Test
    void testIdentifyProblematicPodsAtThresholdNotProblematic() {
        List<PodSummaryResponse.PodInfo> pods = List.of(
            PodSummaryResponse.PodInfo.summary("pod-0", "Running", true, "kafka", 3, 100)
        );
        KafkaClusterPodsResponse podsResponse = KafkaClusterPodsResponse.of(
            "my-cluster", "kafka", PodSummaryResponse.of("kafka", pods));

        Set<String> result = diagnosticService.identifyProblematicPods(podsResponse);
        assertTrue(result.isEmpty());
    }

    @Test
    void testIdentifyProblematicPodsNullInput() {
        assertTrue(diagnosticService.identifyProblematicPods(null).isEmpty());
    }

    @Test
    void testIdentifyProblematicPodsEmptyPodList() {
        KafkaClusterPodsResponse podsResponse = KafkaClusterPodsResponse.empty("my-cluster", "kafka");
        assertTrue(diagnosticService.identifyProblematicPods(podsResponse).isEmpty());
    }

    // ---- Time window tests ----

    @Test
    void testExpandTimeWindowDoublesRelative() {
        LogCollectionParams params = LogCollectionParams.builder(200)
            .filter("errors")
            .sinceSeconds(1800)
            .build();

        LogCollectionParams expanded = diagnosticService.expandTimeWindow(params);
        assertNotNull(expanded);
        assertEquals(3600, expanded.sinceSeconds());
    }

    @Test
    void testExpandTimeWindowCapsAt60Minutes() {
        LogCollectionParams params = LogCollectionParams.builder(200)
            .filter("errors")
            .sinceSeconds(3600)
            .build();

        LogCollectionParams expanded = diagnosticService.expandTimeWindow(params);
        assertNull(expanded);
    }

    @Test
    void testExpandTimeWindowExpandsAbsolute() {
        LogCollectionParams params = LogCollectionParams.builder(200)
            .filter("errors")
            .startTime("2024-01-15T02:00:00Z")
            .endTime("2024-01-15T03:00:00Z")
            .build();

        LogCollectionParams expanded = diagnosticService.expandTimeWindow(params);
        assertNotNull(expanded);
        assertEquals("2024-01-15T01:30:00Z", expanded.startTime());
        assertEquals("2024-01-15T03:30:00Z", expanded.endTime());
    }

    @Test
    void testExpandTimeWindowDefaultsTo60() {
        LogCollectionParams params = LogCollectionParams.builder(200)
            .filter("errors")
            .build();

        LogCollectionParams expanded = diagnosticService.expandTimeWindow(params);
        assertNotNull(expanded);
        assertEquals(3600, expanded.sinceSeconds());
    }

    @Test
    void testResolveEffectiveSinceMinutesUserOverride() {
        KafkaClusterDiagnosticService.InvestigationAreas areas =
            KafkaClusterDiagnosticService.InvestigationAreas.all();
        assertEquals(15, diagnosticService.resolveEffectiveSinceMinutes(15, areas));
    }

    @Test
    void testResolveEffectiveSinceMinutesTriageRelative() {
        KafkaClusterDiagnosticService.InvestigationAreas areas =
            new KafkaClusterDiagnosticService.InvestigationAreas(
                true, true, true, true, true, true, 45, null, null);
        assertEquals(45, diagnosticService.resolveEffectiveSinceMinutes(null, areas));
    }

    @Test
    void testResolveEffectiveSinceMinutesDefault() {
        KafkaClusterDiagnosticService.InvestigationAreas areas =
            KafkaClusterDiagnosticService.InvestigationAreas.all();
        assertEquals(30, diagnosticService.resolveEffectiveSinceMinutes(null, areas));
    }

    @Test
    void testResolveEffectiveSinceMinutesAbsoluteWindowReturnsNull() {
        KafkaClusterDiagnosticService.InvestigationAreas areas =
            new KafkaClusterDiagnosticService.InvestigationAreas(
                true, true, true, true, true, true,
                null, "2024-01-15T02:00:00Z", "2024-01-15T03:00:00Z");
        assertNull(diagnosticService.resolveEffectiveSinceMinutes(null, areas));
    }
}
