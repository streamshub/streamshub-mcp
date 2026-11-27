/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Tests for {@link DiagnosticSteps}.
 */
class DiagnosticStepsTest {

    DiagnosticStepsTest() {
    }

    @Test
    void testNonEmptyOrNull() {
        assertNull(DiagnosticSteps.nonEmptyOrNull(null));
        assertNull(DiagnosticSteps.nonEmptyOrNull(List.of()));
        assertEquals(List.of("a"), DiagnosticSteps.nonEmptyOrNull(List.of("a")));
    }

    @Test
    void testSummaryCountsOnlyNonEmptyLists() {
        assertEquals("3 steps succeeded",
            DiagnosticSteps.summary(List.of("a", "b", "c"), List.of(), null));
        assertEquals("1 step succeeded, 1 step degraded, 2 steps failed",
            DiagnosticSteps.summary(List.of("a"), List.of("b: x"), List.of("c: y", "d: z")));
        assertEquals("0 steps succeeded, 1 step failed",
            DiagnosticSteps.summary(null, null, List.of("a: x")));
    }
}
