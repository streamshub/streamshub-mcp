/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Records MCP tool-call metrics using Micrometer.
 *
 * <p>Records two metrics, tagged by {@code server}, {@code tool}, {@code status}
 * and {@code error_type}:</p>
 * <ul>
 *   <li>{@code mcp.tool.calls} (counter) &mdash; total tool invocations</li>
 *   <li>{@code mcp.tool.call.duration} (timer) &mdash; tool execution duration</li>
 * </ul>
 *
 * <p>The {@code server} tag is read from {@code quarkus.mcp.server.server-info.name}
 * so metrics from different MCP servers can be distinguished in a shared registry.
 * When no {@link MeterRegistry} is available (e.g., Micrometer is not on the
 * classpath) every method is a no-op.</p>
 */
@Singleton
public class ToolCallMetricsRecorder {

    private static final String METRIC_TOOL_CALLS = "mcp.tool.calls";
    private static final String METRIC_TOOL_DURATION = "mcp.tool.call.duration";
    private static final String TAG_SERVER = "server";
    private static final String TAG_TOOL = "tool";
    private static final String TAG_STATUS = "status";
    private static final String TAG_ERROR_TYPE = "error_type";

    @Inject
    Instance<MeterRegistry> registry;

    @ConfigProperty(name = "quarkus.mcp.server.server-info.name", defaultValue = "mcp")
    String serverName;

    ToolCallMetricsRecorder() {
    }

    /**
     * Starts a timer sample, or returns {@code null} when no registry is available.
     *
     * @return a running {@link Timer.Sample}, or {@code null} if metrics are disabled
     */
    public Timer.Sample start() {
        return registry.isResolvable() ? Timer.start(registry.get()) : null;
    }

    /**
     * Stops the given sample and records the duration and call count for a completed invocation.
     *
     * @param sample   the sample returned by {@link #start()} (a {@code null} sample is a no-op)
     * @param toolName the tool name (from {@code @Tool(name = ...)})
     * @param outcome  the classified call outcome
     */
    public void record(final Timer.Sample sample, final String toolName, final ToolCallOutcome outcome) {
        if (sample == null || !registry.isResolvable()) {
            return;
        }
        MeterRegistry meterRegistry = registry.get();
        sample.stop(Timer.builder(METRIC_TOOL_DURATION)
            .tag(TAG_SERVER, serverName)
            .tag(TAG_TOOL, toolName)
            .tag(TAG_STATUS, outcome.status())
            .tag(TAG_ERROR_TYPE, outcome.errorType())
            .register(meterRegistry));
        incrementCounter(meterRegistry, toolName, outcome);
    }

    /**
     * Records a call that was rejected before execution (e.g., by a rate-limit guardrail).
     * Only the call counter is incremented; no duration is recorded because the tool never ran.
     *
     * @param toolName the tool name (from {@code @Tool(name = ...)})
     * @param outcome  the rejection outcome (e.g., {@link ToolCallOutcome#RATE_LIMITED})
     */
    public void recordRejection(final String toolName, final ToolCallOutcome outcome) {
        if (!registry.isResolvable()) {
            return;
        }
        incrementCounter(registry.get(), toolName, outcome);
    }

    private void incrementCounter(final MeterRegistry meterRegistry, final String toolName,
                                  final ToolCallOutcome outcome) {
        meterRegistry.counter(METRIC_TOOL_CALLS,
                TAG_SERVER, serverName,
                TAG_TOOL, toolName,
                TAG_STATUS, outcome.status(),
                TAG_ERROR_TYPE, outcome.errorType())
            .increment();
    }
}
