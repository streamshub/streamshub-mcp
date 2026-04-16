/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.guardrail;

import jakarta.annotation.Priority;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InvocationContext;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * CDI interceptor that chains all {@link GuardrailFilter} beans around
 * {@link Guarded}-annotated tool methods.
 *
 * <p>Runs at {@code LIBRARY_BEFORE + 1} priority, which is inside (after)
 * {@code @WrapBusinessError} at {@code LIBRARY_BEFORE}. This means filter
 * exceptions are caught by the error wrapper and returned as clean MCP errors.</p>
 *
 * <p>Filter execution order is determined by each filter bean's CDI
 * {@link Priority} annotation. Lower values execute first.</p>
 */
@Guarded
@Interceptor
@Priority(Interceptor.Priority.LIBRARY_BEFORE + 1)
public class GuardrailInterceptor {

    private static final Logger LOG = Logger.getLogger(GuardrailInterceptor.class);

    @Any
    @Inject
    Instance<GuardrailFilter> filterInstances;

    private volatile List<GuardrailFilter> sortedFilters;
    private volatile boolean initialized;

    GuardrailInterceptor() {
    }

    /**
     * Intercept tool method calls, applying input and output filters in priority order.
     *
     * @param ctx the invocation context
     * @return the filtered tool result
     * @throws Exception if the tool or any filter throws
     */
    @AroundInvoke
    Object intercept(final InvocationContext ctx) throws Exception {
        ensureInitialized();
        String toolName = ctx.getMethod().getName();

        // Apply input filters
        Object[] params = ctx.getParameters();
        for (GuardrailFilter filter : sortedFilters) {
            params = filter.filterInput(toolName, params);
        }
        ctx.setParameters(params);

        // Execute the tool
        Object result = ctx.proceed();

        // Apply output filters
        for (GuardrailFilter filter : sortedFilters) {
            result = filter.filterOutput(toolName, result);
        }

        return result;
    }

    private void ensureInitialized() {
        if (!initialized) {
            synchronized (this) {
                if (!initialized) {
                    List<GuardrailFilter> filters = new ArrayList<>();
                    for (GuardrailFilter filter : filterInstances) {
                        filters.add(filter);
                    }
                    filters.sort(Comparator.comparingInt(this::getPriority));
                    LOG.infof("Guardrail filter chain initialized with %d filter(s): %s",
                        filters.size(),
                        filters.stream()
                            .map(f -> f.getClass().getSimpleName())
                            .toList());
                    sortedFilters = List.copyOf(filters);
                    initialized = true;
                }
            }
        }
    }

    private int getPriority(final GuardrailFilter filter) {
        Priority priority = filter.getClass().getAnnotation(Priority.class);
        if (priority != null) {
            return priority.value();
        }
        return Interceptor.Priority.APPLICATION;
    }
}
