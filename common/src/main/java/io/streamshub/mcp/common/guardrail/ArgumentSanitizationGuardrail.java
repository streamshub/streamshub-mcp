/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.guardrail;

import io.quarkiverse.mcp.server.ExecutionModel;
import io.quarkiverse.mcp.server.SupportedExecutionModels;
import io.quarkiverse.mcp.server.ToolInputGuardrail;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Singleton;
import org.jboss.logging.Logger;

import java.util.Objects;

/**
 * Input guardrail that strips control characters from all {@link String}
 * tool arguments before execution.
 *
 * <p>MCP clients are not guaranteed to be well-behaved — a buggy or malicious
 * client could send null bytes, escape sequences, or other control characters
 * in tool arguments. This guardrail provides universal input sanitization.</p>
 *
 * <p>Recursively processes nested {@link JsonObject} and {@link JsonArray} structures,
 * preserving newlines ({@code \n}), tabs ({@code \t}), and carriage returns ({@code \r}).</p>
 */
@SupportedExecutionModels({ExecutionModel.WORKER_THREAD, ExecutionModel.VIRTUAL_THREAD})
@Singleton
public class ArgumentSanitizationGuardrail implements ToolInputGuardrail {

    private static final Logger LOG = Logger.getLogger(ArgumentSanitizationGuardrail.class);

    ArgumentSanitizationGuardrail() {
    }

    @Override
    public void apply(final ToolInputContext ctx) {
        JsonObject arguments = ctx.getArguments();
        if (arguments == null || arguments.isEmpty()) {
            return;
        }

        ObjectResult result = sanitizeObject(arguments);
        if (result.modified()) {
            ctx.setArguments(result.value());
        }
    }

    /**
     * Recursively sanitize a JsonObject, stripping control characters from all String values.
     *
     * @param obj the original JsonObject
     * @return the sanitization result (sanitized object and whether it was modified)
     */
    private ObjectResult sanitizeObject(final JsonObject obj) {
        JsonObject sanitized = new JsonObject();
        boolean modified = false;

        for (String key : obj.fieldNames()) {
            Object value = obj.getValue(key);
            if (value instanceof String str) {
                String cleaned = stripControlChars(str);
                if (!Objects.equals(cleaned, str)) {
                    modified = true;
                }
                sanitized.put(key, cleaned);
            } else if (value instanceof JsonObject nested) {
                ObjectResult nestedResult = sanitizeObject(nested);
                modified |= nestedResult.modified();
                sanitized.put(key, nestedResult.value());
            } else if (value instanceof JsonArray arr) {
                ArrayResult arrayResult = sanitizeArray(arr);
                modified |= arrayResult.modified();
                sanitized.put(key, arrayResult.value());
            } else {
                // Non-string, non-object, non-array: pass through unchanged
                sanitized.put(key, value);
            }
        }

        return new ObjectResult(sanitized, modified);
    }

    /**
     * Recursively sanitize a JsonArray, stripping control characters from all String elements.
     *
     * @param arr the original JsonArray
     * @return the sanitization result (sanitized array and whether it was modified)
     */
    private ArrayResult sanitizeArray(final JsonArray arr) {
        JsonArray sanitized = new JsonArray();
        boolean modified = false;

        for (int i = 0; i < arr.size(); i++) {
            Object value = arr.getValue(i);
            if (value instanceof String str) {
                String cleaned = stripControlChars(str);
                if (!Objects.equals(cleaned, str)) {
                    modified = true;
                }
                sanitized.add(cleaned);
            } else if (value instanceof JsonObject nested) {
                ObjectResult nestedResult = sanitizeObject(nested);
                modified |= nestedResult.modified();
                sanitized.add(nestedResult.value());
            } else if (value instanceof JsonArray nested) {
                ArrayResult nestedResult = sanitizeArray(nested);
                modified |= nestedResult.modified();
                sanitized.add(nestedResult.value());
            } else {
                // Non-string, non-object, non-array: pass through unchanged
                sanitized.add(value);
            }
        }

        return new ArrayResult(sanitized, modified);
    }

    /**
     * Strip control characters from a string, preserving newlines, tabs, and carriage returns.
     *
     * @param input the input string
     * @return the sanitized string
     */
    static String stripControlChars(final String input) {
        if (input == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder(input.length());
        boolean modified = false;
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c < 0x20 && c != '\n' && c != '\t' && c != '\r') {
                modified = true;
            } else {
                sb.append(c);
            }
        }
        if (modified) {
            LOG.debugf("Stripped control characters from input parameter");
        }
        return modified ? sb.toString() : input;
    }

    /**
     * Result of sanitizing a {@link JsonObject}.
     */
    private record ObjectResult(JsonObject value, boolean modified) {
    }

    /**
     * Result of sanitizing a {@link JsonArray}.
     */
    private record ArrayResult(JsonArray value, boolean modified) {
    }
}
