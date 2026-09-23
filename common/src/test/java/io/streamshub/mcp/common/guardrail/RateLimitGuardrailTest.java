/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.guardrail;

import io.quarkiverse.mcp.server.McpConnection;
import io.quarkiverse.mcp.server.Meta;
import io.quarkiverse.mcp.server.RequestId;
import io.quarkiverse.mcp.server.ToolCallException;
import io.quarkiverse.mcp.server.ToolInputGuardrail;
import io.quarkiverse.mcp.server.ToolManager.ToolInfo;
import io.streamshub.mcp.common.observability.ToolCallMetricsRecorder;
import io.streamshub.mcp.common.observability.ToolCallOutcome;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for rate limit guardrails.
 */
class RateLimitGuardrailTest {

    private AtomicLong currentTime;

    RateLimitGuardrailTest() {
    }

    @BeforeEach
    void setUp() {
        currentTime = new AtomicLong(1_000_000L);
    }

    @Test
    void disabledByDefault() {
        TestRateLimitGuardrail guardrail = new TestRateLimitGuardrail("general", 0);
        guardrail.setClock(currentTime::get);
        FakeToolInputContext context = createContext();

        for (int i = 0; i < 100; i++) {
            assertDoesNotThrow(() -> guardrail.apply(context));
        }
    }

    @Test
    void throwsAtLimitWithMessage() {
        TestRateLimitGuardrail guardrail = new TestRateLimitGuardrail("log", 3);
        guardrail.setClock(currentTime::get);
        FakeToolInputContext context = createContext();

        guardrail.apply(context);
        guardrail.apply(context);
        guardrail.apply(context);

        ToolCallException ex = assertThrows(ToolCallException.class,
            () -> guardrail.apply(context));
        assertTrue(ex.getMessage().contains("Rate limit exceeded"));
        assertTrue(ex.getMessage().contains("log"));
        assertTrue(ex.getMessage().contains("3/min"));
        assertTrue(ex.getMessage().contains("Try again in"));
        assertTrue(ex.getMessage().contains("seconds"));
    }

    @Test
    void slidingWindowExpiry() {
        TestRateLimitGuardrail guardrail = new TestRateLimitGuardrail("log", 2);
        guardrail.setClock(currentTime::get);
        FakeToolInputContext context = createContext();

        guardrail.apply(context);
        guardrail.apply(context);

        assertThrows(ToolCallException.class, () -> guardrail.apply(context));

        currentTime.addAndGet(61_000L);

        assertDoesNotThrow(() -> guardrail.apply(context));
    }

    @Test
    void categoriesIndependent() {
        TestRateLimitGuardrail logGuardrail = new TestRateLimitGuardrail("log", 2);
        logGuardrail.setClock(currentTime::get);
        TestRateLimitGuardrail generalGuardrail = new TestRateLimitGuardrail("general", 2);
        generalGuardrail.setClock(currentTime::get);

        FakeToolInputContext context = createContext();

        logGuardrail.apply(context);
        logGuardrail.apply(context);

        assertDoesNotThrow(() -> generalGuardrail.apply(context));
        assertDoesNotThrow(() -> generalGuardrail.apply(context));

        assertThrows(ToolCallException.class, () -> logGuardrail.apply(context));
        assertThrows(ToolCallException.class, () -> generalGuardrail.apply(context));
    }

    @Test
    void recordsRejectionMetricWhenRateLimited() {
        TestRateLimitGuardrail guardrail = new TestRateLimitGuardrail("log", 1);
        guardrail.setClock(currentTime::get);
        ToolCallMetricsRecorder recorder = mock(ToolCallMetricsRecorder.class);
        guardrail.metricsRecorder = recorder;
        FakeToolInputContext context = createContext();

        guardrail.apply(context);
        assertThrows(ToolCallException.class, () -> guardrail.apply(context));

        verify(recorder).recordRejection("unknown", ToolCallOutcome.RATE_LIMITED);
    }

    private FakeToolInputContext createContext() {
        return new FakeToolInputContext();
    }

    /**
     * Fake implementation of {@link ToolInputGuardrail.ToolInputContext} for testing.
     */
    private static class FakeToolInputContext implements ToolInputGuardrail.ToolInputContext {

        FakeToolInputContext() {
        }

        @Override
        public JsonObject getArguments() {
            return new JsonObject();
        }

        @Override
        public void setArguments(JsonObject arguments) {
            // No-op for rate limit tests
        }

        @Override
        public ToolInfo getTool() {
            return null;
        }

        @Override
        public Meta getMeta() {
            return null;
        }

        @Override
        public RequestId getRequestId() {
            return new RequestId("test-request-id");
        }

        @Override
        public McpConnection getConnection() {
            return null;
        }
    }

    /**
     * Test subclass of AbstractRateLimitGuardrail for testing purposes.
     */
    static class TestRateLimitGuardrail extends AbstractRateLimitGuardrail {
        private final String category;
        private final int rpm;

        TestRateLimitGuardrail(final String category, final int rpm) {
            this.category = category;
            this.rpm = rpm;
        }

        @Override
        protected String category() {
            return category;
        }

        @Override
        protected int limitRpm() {
            return rpm;
        }
    }
}
