/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.dto.metrics;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Unit tests for {@link TimeSeriesSummary}.
 */
class TimeSeriesSummaryTest {

    TimeSeriesSummaryTest() {
    }

    @Test
    void nullInputReturnsNull() {
        assertNull(TimeSeriesSummary.of(null));
    }

    @Test
    void emptyInputReturnsNull() {
        assertNull(TimeSeriesSummary.of(List.of()));
    }

    @Test
    void singleDataPointSummary() {
        List<List<Object>> dataPoints = List.of(List.of(100L, 42.0));

        TimeSeriesSummary summary = TimeSeriesSummary.of(dataPoints);

        assertEquals(42.0, summary.min());
        assertEquals(42.0, summary.max());
        assertEquals(42.0, summary.avg());
        assertEquals(42.0, summary.latest());
        assertEquals(0.0, summary.delta());
        assertEquals(1, summary.originalDataPointCount());
    }

    @Test
    void multipleDataPointsSummary() {
        List<List<Object>> dataPoints = List.of(
            List.of(100L, 10.0),
            List.of(200L, 20.0),
            List.of(300L, 30.0)
        );

        TimeSeriesSummary summary = TimeSeriesSummary.of(dataPoints);

        assertEquals(10.0, summary.min());
        assertEquals(30.0, summary.max());
        assertEquals(20.0, summary.avg(), 1e-9);
        assertEquals(30.0, summary.latest());
        assertEquals(20.0, summary.delta());
        assertEquals(3, summary.originalDataPointCount());
    }

    @Test
    void constantValuesSummary() {
        List<List<Object>> dataPoints = List.of(
            List.of(100L, 5.0),
            List.of(200L, 5.0),
            List.of(300L, 5.0)
        );

        TimeSeriesSummary summary = TimeSeriesSummary.of(dataPoints);

        assertEquals(5.0, summary.min());
        assertEquals(5.0, summary.max());
        assertEquals(5.0, summary.avg());
        assertEquals(5.0, summary.latest());
        assertEquals(0.0, summary.delta());
        assertEquals(3, summary.originalDataPointCount());
    }

    @Test
    void negativeDelta() {
        List<List<Object>> dataPoints = List.of(
            List.of(100L, 100.0),
            List.of(200L, 50.0),
            List.of(300L, 25.0)
        );

        TimeSeriesSummary summary = TimeSeriesSummary.of(dataPoints);

        assertEquals(25.0, summary.min());
        assertEquals(100.0, summary.max());
        assertEquals(-75.0, summary.delta());
    }
}
