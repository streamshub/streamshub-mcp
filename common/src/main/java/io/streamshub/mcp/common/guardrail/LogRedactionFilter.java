/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.guardrail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Output filter that redacts sensitive data patterns in tool responses.
 *
 * <p>Scans all text fields for common sensitive patterns (bearer tokens,
 * passwords, API keys, connection strings with credentials) and replaces
 * matches with {@code [REDACTED]}.</p>
 *
 * <p>Can be disabled via {@code mcp.guardrail.log-redaction.enabled=false}.</p>
 *
 * <p>Runs at priority 300 (after output sanitization, before response size limit).</p>
 */
@ApplicationScoped
@Priority(300)
public class LogRedactionFilter implements GuardrailFilter {

    private static final Logger LOG = Logger.getLogger(LogRedactionFilter.class);
    private static final String REDACTED = "[REDACTED]";

    private static final List<RedactionRule> DEFAULT_RULES = List.of(
        new RedactionRule("bearer-token",
            Pattern.compile("(?i)(bearer\\s+)[a-zA-Z0-9._\\-]+"),
            "$1" + REDACTED),
        new RedactionRule("password",
            Pattern.compile("(?i)(password|passwd|pwd)\\s*[=:]\\s*\\S+"),
            "$1=" + REDACTED),
        new RedactionRule("api-key",
            Pattern.compile("(?i)(api[_\\-]?key|apikey)\\s*[=:]\\s*\\S+"),
            "$1=" + REDACTED),
        new RedactionRule("connection-string",
            Pattern.compile("(?i)://[^:]+:[^@]+@"),
            "://" + REDACTED + "@"),
        new RedactionRule("base64-token",
            Pattern.compile("(?<![a-zA-Z0-9/+])[A-Za-z0-9+/]{40,}={0,2}(?![a-zA-Z0-9/+=])"),
            REDACTED)
    );

    @Inject
    ObjectMapper objectMapper;

    @ConfigProperty(name = "mcp.guardrail.log-redaction.enabled", defaultValue = "true")
    boolean enabled;

    LogRedactionFilter() {
    }

    @Override
    public Object filterOutput(final String toolName, final Object result) {
        if (!enabled || result == null) {
            return result;
        }
        try {
            JsonNode tree = objectMapper.valueToTree(result);
            boolean modified = JsonNodeSanitizer.transformTextNodes(tree, this::applyRedaction);
            if (modified) {
                LOG.debugf("Redacted sensitive data in output for tool '%s'", toolName);
                return objectMapper.treeToValue(tree, result.getClass());
            }
            return result;
        } catch (Exception e) {
            LOG.debugf("Could not redact output for tool '%s': %s", toolName, e.getMessage());
            return result;
        }
    }

    /**
     * Apply all redaction rules to a text value.
     *
     * @param text the text to redact
     * @return the redacted text
     */
    String applyRedaction(final String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String result = text;
        for (RedactionRule rule : DEFAULT_RULES) {
            result = rule.pattern().matcher(result).replaceAll(rule.replacement());
        }
        return result;
    }

    /**
     * A named redaction rule with a regex pattern and replacement string.
     *
     * @param name        the rule name (for logging)
     * @param pattern     the regex pattern to match
     * @param replacement the replacement string (may use capture groups)
     */
    record RedactionRule(String name, Pattern pattern, String replacement) {
    }
}
