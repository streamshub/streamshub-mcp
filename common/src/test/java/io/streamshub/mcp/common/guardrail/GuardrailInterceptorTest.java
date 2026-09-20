/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.guardrail;

import io.quarkiverse.mcp.server.InputRequiredException;
import io.quarkiverse.mcp.server.McpException;
import io.quarkiverse.mcp.server.ToolCallException;
import jakarta.enterprise.inject.Instance;
import jakarta.interceptor.InvocationContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link GuardrailInterceptor} exception handling.
 *
 * <p>The interceptor runs inside {@code @WrapBusinessError}, so any exception it wraps reaches the
 * framework already wrapped. These tests lock in which exception types must propagate unwrapped.</p>
 */
class GuardrailInterceptorTest {

    private GuardrailInterceptor interceptor;

    GuardrailInterceptorTest() {
    }

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        interceptor = new GuardrailInterceptor();
        Instance<GuardrailFilter> filters = mock(Instance.class);
        when(filters.iterator()).thenReturn(Collections.emptyIterator());
        interceptor.filterInstances = filters;
    }

    /**
     * Regression guard for MRTR (#251): a stateless client's {@link InputRequiredException} must
     * propagate unwrapped so the framework renders an {@code input_required} result. If the generic
     * {@code catch (Exception)} branch wrapped it in {@link ToolCallException}, the retry flow would
     * never trigger.
     */
    @Test
    void testInputRequiredExceptionPropagatesUnwrapped() throws Exception {
        InputRequiredException inputRequired =
            InputRequiredException.builder().setRequestState("kafka").build();
        InvocationContext ctx = mockContext();
        when(ctx.proceed()).thenThrow(inputRequired);

        InputRequiredException thrown = assertThrows(InputRequiredException.class,
            () -> interceptor.intercept(ctx));

        assertSame(inputRequired, thrown);
    }

    @Test
    void testToolCallExceptionPropagatesUnwrapped() throws Exception {
        ToolCallException toolError = new ToolCallException("boom");
        InvocationContext ctx = mockContext();
        when(ctx.proceed()).thenThrow(toolError);

        ToolCallException thrown = assertThrows(ToolCallException.class,
            () -> interceptor.intercept(ctx));

        assertSame(toolError, thrown);
    }

    @Test
    void testGenericExceptionIsWrappedInToolCallException() throws Exception {
        IllegalStateException generic = new IllegalStateException("unexpected");
        InvocationContext ctx = mockContext();
        when(ctx.proceed()).thenThrow(generic);

        ToolCallException thrown = assertThrows(ToolCallException.class,
            () -> interceptor.intercept(ctx));

        assertSame(generic, thrown.getCause());
    }

    @Test
    void testMcpExceptionPropagatesUnwrapped() throws Exception {
        McpException mcpError = new McpException("ambiguous", -32602);
        InvocationContext ctx = mockContext();
        when(ctx.proceed()).thenThrow(mcpError);

        Throwable thrown = assertThrows(McpException.class, () -> interceptor.intercept(ctx));

        assertInstanceOf(McpException.class, thrown);
        assertSame(mcpError, thrown);
    }

    private InvocationContext mockContext() throws NoSuchMethodException {
        Method method = GuardrailInterceptorTest.class.getDeclaredMethod("sampleToolMethod");
        InvocationContext ctx = mock(InvocationContext.class);
        when(ctx.getMethod()).thenReturn(method);
        when(ctx.getParameters()).thenReturn(new Object[0]);
        return ctx;
    }

    /**
     * Reflection target used only to give the interceptor a real {@link Method} to resolve a tool
     * name from.
     */
    void sampleToolMethod() {
    }
}
