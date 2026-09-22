/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.guardrail;

import io.quarkiverse.mcp.server.McpConnection;
import io.quarkiverse.mcp.server.Meta;
import io.quarkiverse.mcp.server.RequestId;
import io.quarkiverse.mcp.server.ToolInputGuardrail;
import io.quarkiverse.mcp.server.ToolManager.ToolInfo;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Unit tests for {@link ArgumentSanitizationGuardrail}.
 */
class ArgumentSanitizationGuardrailTest {

    private final ArgumentSanitizationGuardrail guardrail = new ArgumentSanitizationGuardrail();

    ArgumentSanitizationGuardrailTest() {
    }

    @Test
    void stripsControlChars() {
        // given: arguments with control characters (null byte)
        JsonObject args = new JsonObject().put("a", "x\u0000y");
        FakeToolInputContext ctx = new FakeToolInputContext(args);

        // when: apply guardrail
        guardrail.apply(ctx);

        // then: control char stripped, setArguments called
        assertNotNull(ctx.capturedArguments, "setArguments should have been called");
        assertEquals("xy", ctx.capturedArguments.getString("a"));
    }

    @Test
    void preservesNewlineTabCr() {
        // given: arguments with newline, tab, carriage return (these should be preserved)
        JsonObject args = new JsonObject().put("text", "line1\nline2\ttab\rreturn");
        FakeToolInputContext ctx = new FakeToolInputContext(args);

        // when: apply guardrail
        guardrail.apply(ctx);

        // then: newlines/tabs/CR preserved, but setArguments should still be called if something else changed
        // Actually, nothing changed in this case, so setArguments should NOT be called
        assertNull(ctx.capturedArguments, "setArguments should not be called when nothing changes");
    }

    @Test
    void nonStringUntouched() {
        // given: arguments with non-string values (int, boolean, null)
        JsonObject args = new JsonObject()
            .put("text", "clean")
            .put("count", 42)
            .put("flag", true)
            .putNull("nullValue");
        FakeToolInputContext ctx = new FakeToolInputContext(args);

        // when: apply guardrail
        guardrail.apply(ctx);

        // then: nothing changed, setArguments NOT called
        assertNull(ctx.capturedArguments, "setArguments should not be called when nothing changes");
    }

    @Test
    void sanitizesStringArrayElements() {
        // given: arguments with an array containing strings with control chars
        JsonArray keywords = new JsonArray()
            .add("a\u0007b")
            .add("clean");
        JsonObject args = new JsonObject().put("keywords", keywords);
        FakeToolInputContext ctx = new FakeToolInputContext(args);

        // when: apply guardrail
        guardrail.apply(ctx);

        // then: array element sanitized, setArguments called
        assertNotNull(ctx.capturedArguments, "setArguments should have been called");
        JsonArray result = ctx.capturedArguments.getJsonArray("keywords");
        assertEquals("ab", result.getString(0));
        assertEquals("clean", result.getString(1));
    }

    @Test
    void sanitizesNestedObjects() {
        // given: nested object with control chars
        JsonObject nested = new JsonObject().put("inner", "x\u0001y");
        JsonObject args = new JsonObject().put("outer", nested);
        FakeToolInputContext ctx = new FakeToolInputContext(args);

        // when: apply guardrail
        guardrail.apply(ctx);

        // then: nested value sanitized
        assertNotNull(ctx.capturedArguments, "setArguments should have been called");
        JsonObject resultNested = ctx.capturedArguments.getJsonObject("outer");
        assertEquals("xy", resultNested.getString("inner"));
    }

    @Test
    void emptyArgumentsUnchanged() {
        // given: empty arguments
        JsonObject args = new JsonObject();
        FakeToolInputContext ctx = new FakeToolInputContext(args);

        // when: apply guardrail
        guardrail.apply(ctx);

        // then: nothing changed, setArguments NOT called
        assertNull(ctx.capturedArguments, "setArguments should not be called for empty args");
    }

    /**
     * Fake implementation of {@link ToolInputGuardrail.ToolInputContext} for testing.
     * Records the arguments passed to {@link #setArguments(JsonObject)}.
     */
    private static class FakeToolInputContext implements ToolInputGuardrail.ToolInputContext {
        private final JsonObject originalArguments;
        JsonObject capturedArguments;

        FakeToolInputContext(JsonObject originalArguments) {
            this.originalArguments = originalArguments;
        }

        @Override
        public JsonObject getArguments() {
            return originalArguments;
        }

        @Override
        public void setArguments(JsonObject arguments) {
            if (arguments == null) {
                throw new IllegalArgumentException("arguments cannot be null");
            }
            this.capturedArguments = arguments;
        }

        @Override
        public ToolInfo getTool() {
            return null;
        }

        @Override
        public Meta getMeta() {
            return null;
        }

        @Override
        public RequestId getRequestId() {
            return new RequestId("test-request-id");
        }

        @Override
        public McpConnection getConnection() {
            return null;
        }
    }
}
