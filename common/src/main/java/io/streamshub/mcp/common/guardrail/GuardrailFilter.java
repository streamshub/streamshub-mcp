/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.guardrail;

import java.lang.reflect.Method;

/**
 * Pluggable filter for MCP tool request/response processing.
 *
 * <p>Implementations are CDI beans discovered automatically and chained together
 * in priority order by {@link GuardrailInterceptor}. Lower CDI priority values
 * execute first. Use {@code @jakarta.annotation.Priority} to control ordering.</p>
 *
 * <p>Filters compose rather than replace each other: every active filter runs
 * in sequence. Input filters run before tool execution; output filters run after.
 * A filter can throw to reject a request (the exception is caught by
 * {@code @WrapBusinessError} and returned as an MCP error).</p>
 *
 * @see GuardrailInterceptor
 * @see Guarded
 */
public interface GuardrailFilter {

    /**
     * Filter tool input parameters before execution.
     * Can modify parameters in place or throw to reject the request.
     * Default implementation passes through unchanged.
     *
     * @param toolName   the tool method name
     * @param parameters the tool method parameters
     * @return the (possibly modified) parameters
     */
    default Object[] filterInput(final String toolName, final Object[] parameters) {
        return parameters;
    }

    /**
     * Filter tool input parameters before execution, with access to the tool method.
     * Delegates to {@link #filterInput(String, Object[])} by default.
     * Override this method when access to method annotations (such as
     * {@link RateCategory}) is needed.
     *
     * @param toolName   the tool method name
     * @param parameters the tool method parameters
     * @param method     the tool method being invoked
     * @return the (possibly modified) parameters
     */
    default Object[] filterInput(final String toolName, final Object[] parameters,
                                  final Method method) {
        return filterInput(toolName, parameters);
    }

    /**
     * Filter tool output after execution.
     * Can modify or replace the result object.
     * Default implementation passes through unchanged.
     *
     * @param toolName the tool method name
     * @param result   the tool return value
     * @return the (possibly modified) result
     */
    default Object filterOutput(final String toolName, final Object result) {
        return result;
    }
}
