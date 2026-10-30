/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.service.kafka;

import io.streamshub.mcp.strimzi.dto.kafka.KafkaClusterResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests that certificates and Drain Cleaner are always investigated by the upgrade readiness
 * workflow, whatever the triage answered.
 */
class UpgradeReadinessInvestigationFloorTest {

    UpgradeReadinessInvestigationFloorTest() {
    }

    private static KafkaClusterResponse cluster(final String readiness) {
        return KafkaClusterResponse.of("c", "kafka", "Kafka", "4.0.0", "4.0.0",
            null, null, null, readiness, null, null, null, null,
            null, null, null, null, 1L, null, null, null, null, List.of());
    }

    /**
     * Verify certificates and Drain Cleaner are enabled even when triage answered false for everything.
     */
    @Test
    void testCertificatesAndDrainCleanerAreAlwaysEnabled() {
        UpgradeReadinessDiagnosticService.InvestigationAreas none =
            new UpgradeReadinessDiagnosticService.InvestigationAreas(false, false, false, false, false);

        UpgradeReadinessDiagnosticService.InvestigationAreas areas = none.withFloor(cluster("Ready"));

        assertTrue(areas.certificates());
        assertTrue(areas.drainCleaner());
        assertFalse(areas.events(), "events stay off for a Ready cluster when triage skipped them");
        assertFalse(areas.performanceMetrics());
    }

    /**
     * Verify events are gathered when the cluster is not Ready.
     */
    @Test
    void testEventsEnabledWhenClusterNotReady() {
        UpgradeReadinessDiagnosticService.InvestigationAreas none =
            new UpgradeReadinessDiagnosticService.InvestigationAreas(false, false, false, false, false);

        assertTrue(none.withFloor(cluster("NotReady")).events());
    }
}
