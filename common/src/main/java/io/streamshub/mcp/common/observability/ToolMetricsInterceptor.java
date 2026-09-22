/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.observability;

import io.micrometer.core.instrument.Timer;
import io.quarkiverse.mcp.server.InputRequiredException;
import io.quarkiverse.mcp.server.McpException;
import io.quarkiverse.mcp.server.Tool;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InvocationContext;

/**
 * CDI interceptor that records tool-call metrics around {@link MeasuredTool}-annotated
 * tool methods.
 *
 * <p>Metrics is a cross-cutting concern that must observe <em>every</em> exit path,
 * including thrown {@code McpException} protocol errors. Output guardrails cannot do
 * this: the framework skips {@code ToolOutputGuardrail} processing for failures that
 * are not {@code ToolCallException}. An interceptor sits at the same layer as
 * {@code @WithSpan} tracing and observes all outcomes.</p>
 *
 * <p>Runs at {@code LIBRARY_BEFORE + 1}, inside (after) {@code @WrapBusinessError}, so it
 * sees the raw exception type before it is wrapped, allowing it to distinguish protocol
 * errors ({@code McpException}) from tool-execution errors. {@code InputRequiredException}
 * is a normal MRTR protocol signal, not an outcome, and is deliberately not recorded.</p>
 *
 * @see MeasuredTool
 * @see ToolCallMetricsRecorder
 */
@MeasuredTool
@Interceptor
@Priority(Interceptor.Priority.LIBRARY_BEFORE + 1)
public class ToolMetricsInterceptor {

    @Inject
    ToolCallMetricsRecorder recorder;

    ToolMetricsInterceptor() {
    }

    /**
     * Measures a tool method invocation and records its outcome.
     *
     * @param ctx the invocation context
     * @return the tool result
     * @throws Exception if the tool method throws
     */
    @AroundInvoke
    Object measure(final InvocationContext ctx) throws Exception {
        Tool tool = ctx.getMethod().getAnnotation(Tool.class);
        if (tool == null) {
            // Not a tool method (e.g., an intercepted helper) - nothing to measure.
            return ctx.proceed();
        }

        Timer.Sample sample = recorder.start();
        if (sample == null) {
            // No meter registry available - metrics disabled.
            return ctx.proceed();
        }

        String toolName = !tool.name().isEmpty() ? tool.name() : ctx.getMethod().getName();
        ToolCallOutcome outcome = ToolCallOutcome.SUCCESS;
        boolean record = true;
        try {
            return ctx.proceed();
        } catch (InputRequiredException e) {
            // MRTR control signal (not an error): the server needs more input. Do not record.
            record = false;
            throw e;
        } catch (McpException e) {
            outcome = ToolCallOutcome.PROTOCOL_ERROR;
            throw e;
        } catch (Exception e) {
            outcome = ToolCallOutcome.TOOL_ERROR;
            throw e;
        } finally {
            if (record) {
                recorder.record(sample, toolName, outcome);
            }
        }
    }
}
