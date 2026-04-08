/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.util.metrics;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link TimeSeriesCompressor}.
 */
class TimeSeriesCompressorTest {

    TimeSeriesCompressorTest() {
    }

    @Test
    void emptyListReturnsEmpty() {
        List<List<Object>> result = TimeSeriesCompressor.compress(List.of());
        assertTrue(result.isEmpty());
    }

    @Test
    void singlePointReturnsSinglePoint() {
        List<List<Object>> input = List.of(List.of(100L, 5.0));
        List<List<Object>> result = TimeSeriesCompressor.compress(input);
        assertEquals(1, result.size());
        assertEquals(List.of(100L, 5.0), result.getFirst());
    }

    @Test
    void twoPointsReturnedAsIs() {
        List<List<Object>> input = List.of(
            List.of(100L, 5.0),
            List.of(200L, 5.0)
        );
        List<List<Object>> result = TimeSeriesCompressor.compress(input);
        assertEquals(2, result.size());
    }

    @Test
    void allConstantValuesCompressedToFirstAndLast() {
        List<List<Object>> input = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            input.add(List.of((long) (i * 60), 42.0));
        }

        List<List<Object>> result = TimeSeriesCompressor.compress(input);

        assertEquals(2, result.size());
        assertEquals(List.of(0L, 42.0), result.get(0));
        assertEquals(List.of(5940L, 42.0), result.get(1));
    }

    @Test
    void allDifferentValuesNoCompression() {
        List<List<Object>> input = List.of(
            List.of(100L, 1.0),
            List.of(200L, 2.0),
            List.of(300L, 3.0),
            List.of(400L, 4.0)
        );

        List<List<Object>> result = TimeSeriesCompressor.compress(input);

        assertEquals(4, result.size());
    }

    @Test
    void constantRunThenChangingValues() {
        List<List<Object>> input = List.of(
            List.of(100L, 0.0),
            List.of(200L, 0.0),
            List.of(300L, 0.0),
            List.of(400L, 5.0),
            List.of(500L, 10.0)
        );

        List<List<Object>> result = TimeSeriesCompressor.compress(input);

        assertEquals(4, result.size());
        assertEquals(List.of(100L, 0.0), result.get(0));
        assertEquals(List.of(300L, 0.0), result.get(1));
        assertEquals(List.of(400L, 5.0), result.get(2));
        assertEquals(List.of(500L, 10.0), result.get(3));
    }

    @Test
    void changingValuesThenConstantRun() {
        List<List<Object>> input = List.of(
            List.of(100L, 1.0),
            List.of(200L, 2.0),
            List.of(300L, 5.0),
            List.of(400L, 5.0),
            List.of(500L, 5.0)
        );

        List<List<Object>> result = TimeSeriesCompressor.compress(input);

        assertEquals(4, result.size());
        assertEquals(List.of(100L, 1.0), result.get(0));
        assertEquals(List.of(200L, 2.0), result.get(1));
        assertEquals(List.of(300L, 5.0), result.get(2));
        assertEquals(List.of(500L, 5.0), result.get(3));
    }

    @Test
    void multipleConstantRuns() {
        List<List<Object>> input = List.of(
            List.of(100L, 0.0),
            List.of(200L, 0.0),
            List.of(300L, 0.0),
            List.of(400L, 5.0),
            List.of(500L, 5.0),
            List.of(600L, 5.0),
            List.of(700L, 0.0),
            List.of(800L, 0.0)
        );

        List<List<Object>> result = TimeSeriesCompressor.compress(input);

        assertEquals(6, result.size());
        assertEquals(List.of(100L, 0.0), result.get(0));
        assertEquals(List.of(300L, 0.0), result.get(1));
        assertEquals(List.of(400L, 5.0), result.get(2));
        assertEquals(List.of(600L, 5.0), result.get(3));
        assertEquals(List.of(700L, 0.0), result.get(4));
        assertEquals(List.of(800L, 0.0), result.get(5));
    }

    @Test
    void valuesWithinEpsilonTreatedAsEqual() {
        List<List<Object>> input = List.of(
            List.of(100L, 1.0),
            List.of(200L, 1.0 + 1e-10),
            List.of(300L, 1.0 - 1e-10)
        );

        List<List<Object>> result = TimeSeriesCompressor.compress(input);

        assertEquals(2, result.size());
        assertEquals(List.of(100L, 1.0), result.get(0));
        assertEquals(List.of(300L, 1.0 - 1e-10), result.get(1));
    }
}
