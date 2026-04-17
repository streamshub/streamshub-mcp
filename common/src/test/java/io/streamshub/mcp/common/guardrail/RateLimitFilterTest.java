/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.guardrail;

import io.quarkiverse.mcp.server.ToolCallException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link RateLimitFilter}.
 */
class RateLimitFilterTest {

    private RateLimitFilter filter;
    private AtomicLong currentTime;

    RateLimitFilterTest() {
    }

    @BeforeEach
    void setUp() {
        filter = new RateLimitFilter();
        filter.logRpm = 0;
        filter.metricsRpm = 0;
        filter.generalRpm = 0;
        currentTime = new AtomicLong(1_000_000L);
        filter.setClock(currentTime::get);
    }

    @Test
    void testDisabledByDefault() {
        Object[] params = new Object[]{"cluster"};
        Method method = getMethod("generalTool");
        for (int i = 0; i < 100; i++) {
            assertSame(params, filter.filterInput("generalTool", params, method));
        }
    }

    @Test
    void testCallsUnderLimitPassThrough() {
        filter.logRpm = 5;
        Object[] params = new Object[]{"cluster"};
        Method method = getMethod("logTool");
        for (int i = 0; i < 5; i++) {
            assertDoesNotThrow(() -> filter.filterInput("logTool", params, method));
        }
    }

    @Test
    void testCallAtLimitIsRejected() {
        filter.logRpm = 3;
        Object[] params = new Object[]{"cluster"};
        Method method = getMethod("logTool");

        filter.filterInput("logTool", params, method);
        filter.filterInput("logTool", params, method);
        filter.filterInput("logTool", params, method);

        ToolCallException ex = assertThrows(ToolCallException.class,
            () -> filter.filterInput("logTool", params, method));
        assertTrue(ex.getMessage().contains("Rate limit exceeded"));
        assertTrue(ex.getMessage().contains("log"));
        assertTrue(ex.getMessage().contains("3/min"));
    }

    @Test
    void testCallsResumeAfterWindowExpires() {
        filter.logRpm = 2;
        Object[] params = new Object[]{"cluster"};
        Method method = getMethod("logTool");

        filter.filterInput("logTool", params, method);
        filter.filterInput("logTool", params, method);
        assertThrows(ToolCallException.class,
            () -> filter.filterInput("logTool", params, method));

        currentTime.addAndGet(61_000L);

        assertDoesNotThrow(() -> filter.filterInput("logTool", params, method));
    }

    @Test
    void testDifferentCategoriesTrackedIndependently() {
        filter.logRpm = 2;
        filter.metricsRpm = 2;
        Object[] params = new Object[]{"cluster"};
        Method logMethod = getMethod("logTool");
        Method metricsMethod = getMethod("metricsTool");

        filter.filterInput("logTool", params, logMethod);
        filter.filterInput("logTool", params, logMethod);

        assertDoesNotThrow(() -> filter.filterInput("metricsTool", params, metricsMethod));
        assertDoesNotThrow(() -> filter.filterInput("metricsTool", params, metricsMethod));

        assertThrows(ToolCallException.class,
            () -> filter.filterInput("logTool", params, logMethod));
        assertThrows(ToolCallException.class,
            () -> filter.filterInput("metricsTool", params, metricsMethod));
    }

    @Test
    void testResetClearsState() {
        filter.logRpm = 1;
        Object[] params = new Object[]{"cluster"};
        Method method = getMethod("logTool");

        filter.filterInput("logTool", params, method);
        assertThrows(ToolCallException.class,
            () -> filter.filterInput("logTool", params, method));

        filter.reset();
        assertDoesNotThrow(() -> filter.filterInput("logTool", params, method));
    }

    @Test
    void testAnnotationDeterminesCategory() {
        filter.logRpm = 1;
        filter.metricsRpm = 1;
        filter.generalRpm = 1;
        Object[] params = new Object[]{"cluster"};

        Method logMethod = getMethod("logTool");
        Method metricsMethod = getMethod("metricsTool");
        Method generalMethod = getMethod("generalTool");

        filter.filterInput("logTool", params, logMethod);
        filter.filterInput("metricsTool", params, metricsMethod);
        filter.filterInput("generalTool", params, generalMethod);

        assertThrows(ToolCallException.class,
            () -> filter.filterInput("logTool", params, logMethod));
        assertThrows(ToolCallException.class,
            () -> filter.filterInput("metricsTool", params, metricsMethod));
        assertThrows(ToolCallException.class,
            () -> filter.filterInput("generalTool", params, generalMethod));
    }

    @Test
    void testUnannotatedMethodDefaultsToGeneral() {
        filter.generalRpm = 1;
        filter.logRpm = 100;
        Object[] params = new Object[]{"cluster"};
        Method method = getMethod("unannotatedTool");

        filter.filterInput("unannotatedTool", params, method);
        assertThrows(ToolCallException.class,
            () -> filter.filterInput("unannotatedTool", params, method));
    }

    @Test
    void testErrorMessageContainsRetryInfo() {
        filter.metricsRpm = 1;
        Object[] params = new Object[]{"cluster"};
        Method method = getMethod("metricsTool");

        filter.filterInput("metricsTool", params, method);
        ToolCallException ex = assertThrows(ToolCallException.class,
            () -> filter.filterInput("metricsTool", params, method));

        assertTrue(ex.getMessage().contains("Try again in"));
        assertTrue(ex.getMessage().contains("seconds"));
    }

    @Test
    void testParametersPassedThroughUnmodified() {
        filter.generalRpm = 10;
        Object[] params = new Object[]{"cluster", "namespace", 42};
        Method method = getMethod("generalTool");
        Object[] result = filter.filterInput("generalTool", params, method);
        assertSame(params, result);
    }

    @Test
    void testSlidingWindowEvictsOldEntries() {
        filter.logRpm = 2;
        Object[] params = new Object[]{"cluster"};
        Method method = getMethod("logTool");

        filter.filterInput("logTool", params, method);

        currentTime.addAndGet(30_000L);
        filter.filterInput("logTool", params, method);

        assertThrows(ToolCallException.class,
            () -> filter.filterInput("logTool", params, method));

        currentTime.addAndGet(31_000L);
        assertDoesNotThrow(() -> filter.filterInput("logTool", params, method));
    }

    // --- Stub methods with @RateCategory annotations for testing ---

    @RateCategory("log")
    void logTool() {
    }

    @RateCategory("metrics")
    void metricsTool() {
    }

    @RateCategory("general")
    void generalTool() {
    }

    void unannotatedTool() {
    }

    private Method getMethod(final String name) {
        try {
            return getClass().getDeclaredMethod(name);
        } catch (NoSuchMethodException e) {
            throw new AssertionError("Test method not found: " + name, e);
        }
    }
}
