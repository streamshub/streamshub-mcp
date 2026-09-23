/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.guardrail;

import io.quarkiverse.mcp.server.ToolCallException;
import io.quarkiverse.mcp.server.ToolInputGuardrail;
import io.streamshub.mcp.common.observability.ToolCallMetricsRecorder;
import io.streamshub.mcp.common.observability.ToolCallOutcome;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.LongSupplier;

/**
 * Abstract base class for rate limit guardrails.
 *
 * <p>Enforces a sliding-window rate limit on tool calls. Each subclass
 * defines its category name and requests-per-minute limit via abstract
 * methods.</p>
 *
 * <p>A limit of 0 or negative disables rate limiting.</p>
 */
public abstract class AbstractRateLimitGuardrail implements ToolInputGuardrail {

    private static final Logger LOG = Logger.getLogger(AbstractRateLimitGuardrail.class);
    private static final long WINDOW_MS = 60_000L;

    private final Deque<Long> timestamps = new ArrayDeque<>();
    private LongSupplier clock = System::currentTimeMillis;

    @Inject
    ToolCallMetricsRecorder metricsRecorder;

    protected AbstractRateLimitGuardrail() {
    }

    /**
     * Returns the rate limit category name for this guardrail.
     *
     * @return the category name
     */
    protected abstract String category();

    /**
     * Returns the requests-per-minute limit for this guardrail.
     *
     * @return the RPM limit (0 or negative disables rate limiting)
     */
    protected abstract int limitRpm();

    @Override
    public void apply(final ToolInputGuardrail.ToolInputContext ctx) {
        int limit = limitRpm();

        if (limit <= 0) {
            return;
        }

        long now = clock.getAsLong();
        long windowStart = now - WINDOW_MS;

        synchronized (timestamps) {
            while (!timestamps.isEmpty() && timestamps.peekFirst() < windowStart) {
                timestamps.pollFirst();
            }

            if (timestamps.size() >= limit) {
                Long oldest = timestamps.peekFirst();
                long retryAfterMs = oldest != null ? (oldest + WINDOW_MS) - now : WINDOW_MS;
                long retryAfterSec = Math.max(1, (retryAfterMs + 999) / 1000);
                LOG.warnf("Rate limit exceeded for %s tools (%d/min)", category(), limit);
                if (metricsRecorder != null) {
                    String toolName = ctx.getTool() != null ? ctx.getTool().name() : "unknown";
                    metricsRecorder.recordRejection(toolName, ToolCallOutcome.RATE_LIMITED);
                }
                throw new ToolCallException(
                    "Rate limit exceeded for " + category() + " tools (" + limit
                        + "/min). Try again in " + retryAfterSec + " seconds.");
            }

            timestamps.addLast(now);
        }
    }

    /**
     * Replace the clock source. Intended for testing.
     *
     * @param clock supplier of current time in milliseconds
     */
    void setClock(final LongSupplier clock) {
        this.clock = clock;
    }
}
