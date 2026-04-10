/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.guardrail;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.util.Objects;

/**
 * Input filter that strips control characters from all {@link String}
 * tool parameters before execution.
 *
 * <p>MCP clients are not guaranteed to be well-behaved — a buggy or malicious
 * client could send null bytes, escape sequences, or other control characters
 * in tool arguments. This filter provides universal input sanitization.</p>
 *
 * <p>Runs at priority 100 (first in the filter chain).</p>
 */
@ApplicationScoped
@Priority(100)
public class InputValidationFilter implements GuardrailFilter {

    private static final Logger LOG = Logger.getLogger(InputValidationFilter.class);

    InputValidationFilter() {
    }

    @Override
    public Object[] filterInput(final String toolName, final Object[] parameters) {
        Object[] result = parameters;
        for (int i = 0; i < parameters.length; i++) {
            if (parameters[i] instanceof String str) {
                String cleaned = stripControlChars(str);
                if (!Objects.equals(cleaned, str)) {
                    if (result == parameters) {
                        result = parameters.clone();
                    }
                    result[i] = cleaned;
                }
            }
        }
        return result;
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
