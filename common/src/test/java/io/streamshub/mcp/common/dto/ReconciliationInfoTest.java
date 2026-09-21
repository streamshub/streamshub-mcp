/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.dto;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link ReconciliationInfo}.
 */
class ReconciliationInfoTest {

    ReconciliationInfoTest() {
    }

    @Test
    void ofReturnsUpToDateTrueWhenGenerationsMatch() {
        ReconciliationInfo info = ReconciliationInfo.of(5L, 5L);

        assertEquals(5L, info.generation());
        assertEquals(5L, info.observedGeneration());
        assertTrue(info.upToDate());
    }

    @Test
    void ofReturnsUpToDateFalseWhenObservedGenerationLags() {
        ReconciliationInfo info = ReconciliationInfo.of(5L, 3L);

        assertFalse(info.upToDate());
    }

    @Test
    void ofReturnsNullUpToDateWhenObservedGenerationIsNull() {
        ReconciliationInfo info = ReconciliationInfo.of(5L, null);

        assertEquals(5L, info.generation());
        assertNull(info.observedGeneration());
        assertNull(info.upToDate());
    }

    @Test
    void ofReturnsNullUpToDateWhenGenerationIsNull() {
        ReconciliationInfo info = ReconciliationInfo.of(null, 5L);

        assertNull(info.upToDate());
    }

    @Test
    void ofStatusTreatsZeroObservedGenerationAsNeverReconciled() {
        // Status.observedGeneration is a primitive long defaulting to 0 both when the operator has never
        // reconciled the resource and when it genuinely observed generation zero (which cannot happen -
        // Kubernetes generations start at 1). 0 must not be reported as a stalled reconciliation.
        ReconciliationInfo info = ReconciliationInfo.ofStatus(3L, 0L);

        assertEquals(3L, info.generation());
        assertNull(info.observedGeneration());
        assertNull(info.upToDate());
    }

    @Test
    void ofStatusReportsUpToDateForSteadyStateResource() {
        ReconciliationInfo info = ReconciliationInfo.ofStatus(3L, 3L);

        assertEquals(3L, info.observedGeneration());
        assertTrue(info.upToDate());
    }

    @Test
    void ofStatusReportsStalledWhenGenerationHasMovedPastObserved() {
        ReconciliationInfo info = ReconciliationInfo.ofStatus(5L, 3L);

        assertEquals(3L, info.observedGeneration());
        assertFalse(info.upToDate());
    }
}
