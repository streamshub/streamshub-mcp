/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.util.metrics;

import io.streamshub.mcp.common.dto.metrics.MetricSample;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link PerMetricSampleLimit}.
 */
class PerMetricSampleLimitTest {

    PerMetricSampleLimitTest() {
    }

    private static List<MetricSample> samples(final String name, final int count) {
        List<MetricSample> result = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            result.add(MetricSample.of(name, Map.of("pod", "pod-" + i), i));
        }
        return result;
    }

    @Test
    void tryAcceptCountsEachMetricSeparately() {
        PerMetricSampleLimit limit = new PerMetricSampleLimit(2);

        assertTrue(limit.tryAccept("a"));
        assertTrue(limit.tryAccept("a"));
        assertFalse(limit.tryAccept("a"));
        assertTrue(limit.tryAccept("b"));
        assertEquals(Set.of("a"), limit.truncatedMetrics());
    }

    @Test
    void tryAcceptIsUnlimitedWhenLimitIsZero() {
        PerMetricSampleLimit limit = new PerMetricSampleLimit(0);
        for (int i = 0; i < 100; i++) {
            assertTrue(limit.tryAccept("a"));
        }
        assertTrue(limit.truncatedMetrics().isEmpty());
    }

    @Test
    void applyKeepsSmallMetricsWhenALargeOneComesFirst() {
        List<MetricSample> input = new ArrayList<>(samples("large", 50));
        input.addAll(samples("small", 3));

        List<MetricSample> result = PerMetricSampleLimit.apply(input, 10);

        assertEquals(13, result.size());
        assertEquals(10, result.stream().filter(s -> "large".equals(s.name())).count());
        assertEquals(3, result.stream().filter(s -> "small".equals(s.name())).count());
        assertEquals("pod-0", result.getFirst().labels().get("pod"));
    }

    @Test
    void applyReturnsInputWhenNothingIsDropped() {
        List<MetricSample> input = new ArrayList<>(samples("a", 5));
        input.addAll(samples("b", 5));

        assertSame(input, PerMetricSampleLimit.apply(input, 5));
    }

    @Test
    void reachedLimitLooksAtEachMetric() {
        List<MetricSample> input = new ArrayList<>(samples("a", 4));
        input.addAll(samples("b", 4));

        assertFalse(PerMetricSampleLimit.reachedLimit(input, 5));
        assertTrue(PerMetricSampleLimit.reachedLimit(input, 4));
        assertFalse(PerMetricSampleLimit.reachedLimit(input, 0));
    }
}
