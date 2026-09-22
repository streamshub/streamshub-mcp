/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.guardrail;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkiverse.mcp.server.ExecutionModel;
import io.quarkiverse.mcp.server.SupportedExecutionModels;
import io.quarkiverse.mcp.server.ToolOutputGuardrail;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Output guardrail that redacts sensitive data patterns in tool responses.
 *
 * <p>Scans all text fields for common sensitive patterns (bearer tokens,
 * passwords, API keys, connection strings with credentials) and replaces
 * matches with {@code [REDACTED]}.</p>
 *
 * <p>Custom patterns can be added via indexed configuration property
 * {@code mcp.guardrail.log-redaction.custom-patterns[N]}. Custom patterns
 * replace matches with {@code [REDACTED]}. Invalid regex patterns are
 * logged as warnings and skipped.</p>
 *
 * <p>Can be disabled via {@code mcp.guardrail.log-redaction.enabled=false}.</p>
 */
@Singleton
@SupportedExecutionModels({ExecutionModel.WORKER_THREAD, ExecutionModel.VIRTUAL_THREAD})
public class LogRedactionGuardrail implements ToolOutputGuardrail {

    private static final Logger LOG = Logger.getLogger(LogRedactionGuardrail.class);
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
        new RedactionRule("secret-key",
            Pattern.compile("(?i)(secret[_\\-]?key|secret|token)\\s*[=:]\\s*\\S+"),
            "$1=" + REDACTED),
        new RedactionRule("connection-string",
            Pattern.compile("(?i)://[^:\\r\\n]+:[^@\\r\\n]+@"),
            "://" + REDACTED + "@"),
        new RedactionRule("private-key-block",
            Pattern.compile("(?is)-----BEGIN [A-Z0-9 ]*PRIVATE KEY-----.*?-----END [A-Z0-9 ]*PRIVATE KEY-----"),
            REDACTED),
        new RedactionRule("base64-token",
            Pattern.compile("(?<![a-zA-Z0-9/+])[A-Za-z0-9+/]{40,}={0,2}(?![a-zA-Z0-9/+=])"),
            REDACTED)
    );

    @Inject
    ObjectMapper mapper;

    @ConfigProperty(name = "mcp.guardrail.log-redaction.enabled", defaultValue = "true")
    boolean enabled;

    @ConfigProperty(name = "mcp.guardrail.log-redaction.custom-patterns")
    Optional<List<String>> customPatterns;

    // Set once in init() (@PostConstruct) and never mutated afterwards; CDI safely
    // publishes the singleton before it is shared across request threads.
    List<RedactionRule> activeRules;

    LogRedactionGuardrail() {
    }

    /**
     * Compiles custom redaction patterns and merges them with the default rules.
     * Invalid patterns are logged as warnings and skipped.
     */
    @PostConstruct
    void init() {
        List<RedactionRule> rules = new ArrayList<>(DEFAULT_RULES);
        if (customPatterns.isPresent()) {
            List<String> patterns = customPatterns.get();
            for (int i = 0; i < patterns.size(); i++) {
                String patternStr = patterns.get(i);
                try {
                    Pattern compiled = Pattern.compile(patternStr);
                    rules.add(new RedactionRule("custom-" + i, compiled, REDACTED));
                    LOG.debugf("Registered custom redaction pattern [%d]: %s", i, patternStr);
                } catch (PatternSyntaxException e) {
                    LOG.warnf("Skipping invalid custom redaction pattern [%d] '%s': %s",
                        i, patternStr, e.getMessage());
                }
            }
        }
        activeRules = Collections.unmodifiableList(rules);
    }

    @Override
    public void apply(final ToolOutputContext ctx) {
        if (!enabled) {
            return;
        }
        GuardedResponses.guard(ctx, mapper, GuardedResponses.OnError.FAIL_CLOSED,
            tree -> JsonNodeSanitizer.transformTextNodes(tree, this::applyRedaction));
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
        for (RedactionRule rule : activeRules) {
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
