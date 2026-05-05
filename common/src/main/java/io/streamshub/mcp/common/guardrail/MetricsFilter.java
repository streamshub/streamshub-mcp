/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.guardrail;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Guardrail filter that records MCP tool call metrics using Micrometer.
 *
 * <p>Runs at priority 999 (last in the filter chain) to capture the final
 * state of each tool call after all other filters have processed it.</p>
 *
 * <p>Records two metrics:</p>
 * <ul>
 *   <li>{@code mcp.tool.calls} (counter) &mdash; total tool invocations by server, tool name, and status</li>
 *   <li>{@code mcp.tool.call.duration} (timer) &mdash; tool execution duration by server, tool name, and status</li>
 * </ul>
 *
 * <p>The {@code server} tag is read from the {@code quarkus.mcp.server.server-info.name}
 * config property, allowing metrics from different MCP servers to be distinguished
 * in a shared Prometheus instance.</p>
 *
 * <p>Activates only when a {@link MeterRegistry} CDI bean is available
 * (e.g., when {@code quarkus-micrometer-registry-prometheus} is on the classpath).
 * Acts as a no-op otherwise.</p>
 */
@ApplicationScoped
@Priority(999)
public class MetricsFilter implements GuardrailFilter {

    private static final Logger LOG = Logger.getLogger(MetricsFilter.class);
    private static final String METRIC_TOOL_CALLS = "mcp.tool.calls";
    private static final String METRIC_TOOL_DURATION = "mcp.tool.call.duration";
    private static final String TAG_SERVER = "server";
    private static final String TAG_TOOL = "tool";
    private static final String TAG_STATUS = "status";
    private static final String STATUS_SUCCESS = "success";
    private static final String STATUS_ERROR = "error";

    private final ThreadLocal<Timer.Sample> timerSample = new ThreadLocal<>();

    @Inject
    Instance<MeterRegistry> registryInstance;

    @ConfigProperty(name = "quarkus.mcp.server.server-info.name", defaultValue = "mcp")
    String serverName;

    private MeterRegistry registry;

    MetricsFilter() {
    }

    /**
     * Resolves the meter registry from CDI if available.
     */
    @PostConstruct
    void init() {
        if (registryInstance != null && registryInstance.isResolvable()) {
            registry = registryInstance.get();
            LOG.info("Metrics filter activated");
        } else {
            LOG.debug("No MeterRegistry available, metrics filter disabled");
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Starts a timer sample for measuring tool execution duration.</p>
     */
    @Override
    public Object[] filterInput(final String toolName, final Object[] parameters) {
        if (registry != null) {
            timerSample.set(Timer.start(registry));
        }
        return parameters;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Stops the timer and records the tool call as successful.</p>
     */
    @Override
    public Object filterOutput(final String toolName, final Object result) {
        recordMetrics(toolName, STATUS_SUCCESS);
        return result;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Stops the timer and records the tool call as failed.</p>
     */
    @Override
    public void filterError(final String toolName, final Exception error) {
        recordMetrics(toolName, STATUS_ERROR);
    }

    private void recordMetrics(final String toolName, final String status) {
        if (registry == null) {
            return;
        }
        Timer.Sample sample = timerSample.get();
        if (sample == null) {
            return;
        }
        timerSample.remove();
        sample.stop(Timer.builder(METRIC_TOOL_DURATION)
            .tag(TAG_SERVER, serverName)
            .tag(TAG_TOOL, toolName)
            .tag(TAG_STATUS, status)
            .register(registry));
        registry.counter(METRIC_TOOL_CALLS, TAG_SERVER, serverName, TAG_TOOL, toolName, TAG_STATUS, status)
            .increment();
    }

    /**
     * Replace the meter registry. Intended for testing.
     *
     * @param registry the meter registry to use
     */
    void setRegistry(final MeterRegistry registry) {
        this.registry = registry;
    }
}
