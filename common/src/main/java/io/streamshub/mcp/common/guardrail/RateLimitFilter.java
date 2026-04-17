/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.guardrail;

import io.quarkiverse.mcp.server.ToolCallException;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Input filter that enforces per-category rate limits on tool calls.
 *
 * <p>Tools are classified by the {@link RateCategory} annotation on the tool
 * method. Methods without the annotation default to the {@code general}
 * category. Each category has a configurable requests-per-minute limit.</p>
 *
 * <p>A limit of 0 disables rate limiting for that category (default).
 * Runs at priority 50 (before {@link InputValidationFilter}).</p>
 */
@ApplicationScoped
@Priority(50)
public class RateLimitFilter implements GuardrailFilter {

    private static final Logger LOG = Logger.getLogger(RateLimitFilter.class);
    private static final long WINDOW_MS = 60_000L;

    static final String CATEGORY_LOG = "log";
    static final String CATEGORY_METRICS = "metrics";
    static final String CATEGORY_GENERAL = "general";

    @ConfigProperty(name = "mcp.guardrail.rate-limit.log-rpm", defaultValue = "0")
    int logRpm;

    @ConfigProperty(name = "mcp.guardrail.rate-limit.metrics-rpm", defaultValue = "0")
    int metricsRpm;

    @ConfigProperty(name = "mcp.guardrail.rate-limit.general-rpm", defaultValue = "0")
    int generalRpm;

    private final ConcurrentHashMap<String, Deque<Long>> callTimestamps = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> categoryCache = new ConcurrentHashMap<>();
    private LongSupplier clock = System::currentTimeMillis;

    RateLimitFilter() {
    }

    @Override
    public Object[] filterInput(final String toolName, final Object[] parameters,
                                 final Method method) {
        String category = resolveCategory(toolName, method);
        int limit = limitForCategory(category);

        if (limit <= 0) {
            return parameters;
        }

        long now = clock.getAsLong();
        long windowStart = now - WINDOW_MS;

        Deque<Long> timestamps = callTimestamps.computeIfAbsent(category, k -> new ArrayDeque<>());

        synchronized (timestamps) {
            while (!timestamps.isEmpty() && timestamps.peekFirst() < windowStart) {
                timestamps.pollFirst();
            }

            if (timestamps.size() >= limit) {
                Long oldest = timestamps.peekFirst();
                long retryAfterMs = oldest != null ? (oldest + WINDOW_MS) - now : WINDOW_MS;
                long retryAfterSec = Math.max(1, (retryAfterMs + 999) / 1000);
                LOG.warnf("Rate limit exceeded for %s tools (%d/%d per minute), tool=%s",
                    category, timestamps.size(), limit, toolName);
                throw new ToolCallException(
                    "Rate limit exceeded for " + category + " tools (" + limit
                        + "/min). Try again in " + retryAfterSec + " seconds.");
            }

            timestamps.addLast(now);
        }

        return parameters;
    }

    private String resolveCategory(final String toolName, final Method method) {
        return categoryCache.computeIfAbsent(toolName, k -> {
            RateCategory annotation = method.getAnnotation(RateCategory.class);
            return annotation != null ? annotation.value() : CATEGORY_GENERAL;
        });
    }

    private int limitForCategory(final String category) {
        return switch (category) {
            case CATEGORY_LOG -> logRpm;
            case CATEGORY_METRICS -> metricsRpm;
            default -> generalRpm;
        };
    }

    /**
     * Replace the clock source. Intended for testing.
     *
     * @param clock supplier of current time in milliseconds
     */
    void setClock(final LongSupplier clock) {
        this.clock = clock;
    }

    /**
     * Clear all tracked timestamps and cached categories. Intended for testing.
     */
    void reset() {
        callTimestamps.clear();
        categoryCache.clear();
    }
}
