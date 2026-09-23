/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.guardrail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkiverse.mcp.server.ExecutionModel;
import io.quarkiverse.mcp.server.SupportedExecutionModels;
import io.quarkiverse.mcp.server.ToolInputGuardrail;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.jboss.logging.Logger;

/**
 * Input guardrail that strips control characters from all {@link String}
 * tool arguments before execution.
 *
 * <p>MCP clients are not guaranteed to be well-behaved — a buggy or malicious
 * client could send null bytes, escape sequences, or other control characters
 * in tool arguments. This guardrail provides universal input sanitization.</p>
 *
 * <p>The framework hands arguments as a Vert.x {@link JsonObject} (the
 * {@link ToolInputContext} boundary type). Sanitization itself walks a Jackson tree via
 * {@link JsonNodeSanitizer} — the same recursive text-node transformer used by
 * {@link LogRedactionGuardrail} — so nested objects and arrays are handled consistently and
 * the project uses Jackson for JSON processing throughout. Newlines ({@code \n}), tabs
 * ({@code \t}), and carriage returns ({@code \r}) are preserved.</p>
 */
@SupportedExecutionModels({ExecutionModel.WORKER_THREAD, ExecutionModel.VIRTUAL_THREAD})
@Singleton
public class ArgumentSanitizationGuardrail implements ToolInputGuardrail {

    private static final Logger LOG = Logger.getLogger(ArgumentSanitizationGuardrail.class);

    @Inject
    ObjectMapper mapper;

    ArgumentSanitizationGuardrail() {
    }

    @Override
    public void apply(final ToolInputContext ctx) {
        JsonObject arguments = ctx.getArguments();
        if (arguments == null || arguments.isEmpty()) {
            return;
        }

        try {
            JsonNode tree = mapper.readTree(arguments.encode());
            boolean modified = JsonNodeSanitizer.transformTextNodes(tree,
                ArgumentSanitizationGuardrail::stripControlChars);
            if (modified) {
                ctx.setArguments(new JsonObject(mapper.writeValueAsString(tree)));
            }
        } catch (Exception e) {
            // Arguments provided by the framework are already valid JSON, so a round-trip failure
            // is not expected; leave them unchanged rather than failing the tool call.
            LOG.warnf("Could not sanitize tool arguments: %s", e.getMessage());
        }
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
}
