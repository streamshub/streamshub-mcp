/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.observability;

import io.micrometer.core.instrument.Timer;
import io.quarkiverse.mcp.server.InputRequiredException;
import io.quarkiverse.mcp.server.McpException;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolCallException;
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
 * <p>Sitting inside {@code @WrapBusinessError} also lets it <em>normalize</em> the error surfaced
 * to the client: {@code @WrapBusinessError} would otherwise wrap an uncaught business exception with
 * {@code new ToolCallException(cause)}, whose message is {@code cause.toString()} and leaks the
 * fully-qualified exception class name into the tool response. This interceptor re-wraps such
 * exceptions with the contextual message only, so clients see a clean failed tool response.
 * Normalization runs even when no meter registry is present (metrics disabled).</p>
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

        // sample is null when no meter registry is present; the invocation is still guarded so
        // error-message normalization happens regardless of whether metrics are enabled.
        Timer.Sample sample = recorder.start();
        String toolName = !tool.name().isEmpty() ? tool.name() : ctx.getMethod().getName();
        ToolCallOutcome outcome = ToolCallOutcome.SUCCESS;
        boolean record = sample != null;
        try {
            return ctx.proceed();
        } catch (InputRequiredException e) {
            // MRTR control signal (not an error): the server needs more input. Do not record.
            record = false;
            throw e;
        } catch (McpException e) {
            // Structured JSON-RPC protocol error: propagate unwrapped so the framework renders it
            // (with its error.data) rather than as a plain tool response.
            outcome = ToolCallOutcome.PROTOCOL_ERROR;
            throw e;
        } catch (ToolCallException e) {
            // Already a tool-execution error with a curated message (e.g., cancellation):
            // propagate unwrapped to avoid double-wrapping.
            outcome = ToolCallOutcome.TOOL_ERROR;
            throw e;
        } catch (Exception e) {
            // Uncaught business/infrastructure exception. @WrapBusinessError would wrap it with
            // new ToolCallException(cause), whose message is cause.toString() and leaks the
            // fully-qualified exception class name into the tool response. Re-wrap with the
            // contextual message only so the client sees a clean failed tool response.
            outcome = ToolCallOutcome.TOOL_ERROR;
            throw new ToolCallException(e.getMessage(), e);
        } finally {
            if (record) {
                recorder.record(sample, toolName, outcome);
            }
        }
    }
}
