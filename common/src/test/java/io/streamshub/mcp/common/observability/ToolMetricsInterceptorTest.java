/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.quarkiverse.mcp.server.InputRequiredException;
import io.quarkiverse.mcp.server.McpException;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolCallException;
import jakarta.interceptor.InvocationContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit tests for {@link ToolMetricsInterceptor} outcome classification.
 */
class ToolMetricsInterceptorTest {

    private SimpleMeterRegistry registry;
    private ToolMetricsInterceptor interceptor;

    ToolMetricsInterceptorTest() {
    }

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        ToolCallMetricsRecorder recorder = new ToolCallMetricsRecorder();
        recorder.registry = FakeInstance.of(registry);
        recorder.serverName = "test-mcp";
        interceptor = new ToolMetricsInterceptor();
        interceptor.recorder = recorder;
    }

    @Test
    void successRecordsSuccess() throws Exception {
        Object result = interceptor.measure(toolContext("result", null));
        assertEquals("result", result);
        assertCounter("success", "none");
    }

    @Test
    void protocolErrorFromMcpException() {
        McpException failure = new McpException("not found", -32002);
        assertThrows(McpException.class, () -> interceptor.measure(toolContext(null, failure)));
        assertCounter("error", "protocol_error");
    }

    @Test
    void toolErrorFromGenericException() {
        RuntimeException failure = new RuntimeException("boom");
        assertThrows(RuntimeException.class, () -> interceptor.measure(toolContext(null, failure)));
        assertCounter("error", "tool_error");
    }

    @Test
    void genericExceptionIsNormalizedToCleanToolCallException() {
        // A raw @WrapBusinessError wrap would surface "java.lang.IllegalStateException: boom"
        // (the cause's toString()); the interceptor must strip the class name to just the message.
        ToolCallException thrown = assertThrows(ToolCallException.class,
            () -> interceptor.measure(toolContext(null, new IllegalStateException("boom"))));
        assertEquals("boom", thrown.getMessage(), "message must not carry the exception class name");
        assertCounter("error", "tool_error");
    }

    @Test
    void existingToolCallExceptionIsNotDoubleWrapped() {
        ToolCallException original = new ToolCallException("curated message");
        ToolCallException thrown = assertThrows(ToolCallException.class,
            () -> interceptor.measure(toolContext(null, original)));
        assertSame(original, thrown, "an existing ToolCallException must propagate unwrapped");
        assertCounter("error", "tool_error");
    }

    @Test
    void genericExceptionIsNormalizedEvenWithoutRegistry() {
        ToolCallMetricsRecorder noReg = new ToolCallMetricsRecorder();
        noReg.registry = FakeInstance.unresolvable();
        noReg.serverName = "test-mcp";
        interceptor.recorder = noReg;

        ToolCallException thrown = assertThrows(ToolCallException.class,
            () -> interceptor.measure(toolContext(null, new IllegalStateException("boom"))));
        assertEquals("boom", thrown.getMessage(), "normalization must run even when metrics are disabled");
        assertNull(registry.find("mcp.tool.calls").counter());
    }

    @Test
    void inputRequiredIsNotRecorded() {
        InputRequiredException failure = InputRequiredException.builder()
            .setRequestState("state").build();
        assertThrows(InputRequiredException.class, () -> interceptor.measure(toolContext(null, failure)));
        assertNull(registry.find("mcp.tool.calls").counter(), "input_required must not be recorded");
    }

    @Test
    void nonToolMethodIsNotMeasured() throws Exception {
        Method plain = Sample.class.getDeclaredMethod("plainMethod");
        Object result = interceptor.measure(new FakeInvocationContext(plain, "x", null));
        assertEquals("x", result);
        assertNull(registry.find("mcp.tool.calls").counter());
    }

    @Test
    void noRegistryProceedsWithoutRecording() throws Exception {
        ToolCallMetricsRecorder noReg = new ToolCallMetricsRecorder();
        noReg.registry = FakeInstance.unresolvable();
        noReg.serverName = "test-mcp";
        interceptor.recorder = noReg;

        Object result = interceptor.measure(toolContext("ok", null));
        assertEquals("ok", result);
        assertNull(registry.find("mcp.tool.calls").counter());
    }

    private void assertCounter(final String status, final String errorType) {
        Counter counter = registry.find("mcp.tool.calls")
            .tag("server", "test-mcp").tag("tool", "sample_tool")
            .tag("status", status).tag("error_type", errorType).counter();
        assertNotNull(counter, "expected counter with status=" + status + " error_type=" + errorType);
        assertEquals(1.0, counter.count());
    }

    private FakeInvocationContext toolContext(final Object result, final Exception toThrow) throws Exception {
        Method toolMethod = Sample.class.getDeclaredMethod("toolMethod");
        return new FakeInvocationContext(toolMethod, result, toThrow);
    }

    /**
     * Sample bean supplying reflective {@code @Tool} and plain methods for the tests.
     */
    static class Sample {

        Sample() {
        }

        @Tool(name = "sample_tool")
        String toolMethod() {
            return null;
        }

        String plainMethod() {
            return null;
        }
    }

    /**
     * Minimal {@link InvocationContext} fake whose {@code proceed()} returns a canned
     * value or throws a canned exception.
     */
    static final class FakeInvocationContext implements InvocationContext {

        private final Method method;
        private final Object result;
        private final Exception toThrow;

        FakeInvocationContext(final Method method, final Object result, final Exception toThrow) {
            this.method = method;
            this.result = result;
            this.toThrow = toThrow;
        }

        @Override
        public Object proceed() throws Exception {
            if (toThrow != null) {
                throw toThrow;
            }
            return result;
        }

        @Override
        public Method getMethod() {
            return method;
        }

        @Override
        public Object getTarget() {
            return null;
        }

        @Override
        public Object getTimer() {
            return null;
        }

        @Override
        public Constructor<?> getConstructor() {
            return null;
        }

        @Override
        public Object[] getParameters() {
            return new Object[0];
        }

        @Override
        public void setParameters(final Object[] params) {
        }

        @Override
        public Map<String, Object> getContextData() {
            return new HashMap<>();
        }
    }
}
