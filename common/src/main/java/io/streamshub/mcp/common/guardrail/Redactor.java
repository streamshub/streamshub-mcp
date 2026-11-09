/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.guardrail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
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
 * Redacts sensitive data from text and JSON trees.
 *
 * <p>Shared by {@link LogRedactionGuardrail}, which protects tool responses, and by the
 * diagnostic Sampling path, which would otherwise send gathered data to the client's LLM
 * without any redaction. Two mechanisms are applied:</p>
 * <ul>
 *   <li><b>Pattern rules</b> on every text value: bearer tokens, {@code Authorization: Basic}
 *   (also when the value is a bare {@code Basic <credentials>}),
 *   password/API-key/secret/token assignments, credentials embedded in URLs, PEM private keys
 *   and long base64-like strings. Container image digests ({@code sha256:...}) are kept.</li>
 *   <li><b>Key-name rules</b> on JSON objects: a text value stored under a key that names a
 *   secret (see {@link SensitiveKeys}) is replaced even when the value itself has no
 *   recognisable shape, e.g. {@code {"password":"hunter2"}}.</li>
 * </ul>
 *
 * <p>Custom patterns come from {@code mcp.guardrail.log-redaction.custom-patterns[N]};
 * invalid regular expressions are logged and skipped. Everything can be switched off with
 * {@code mcp.guardrail.log-redaction.enabled=false}.</p>
 */
@Singleton
public class Redactor {

    private static final Logger LOG = Logger.getLogger(Redactor.class);
    private static final String REDACTED = SensitiveKeys.REDACTED;

    private static final List<RedactionRule> DEFAULT_RULES = List.of(
        new RedactionRule("bearer-token",
            Pattern.compile("(?i)(bearer\\s+)[a-zA-Z0-9._\\-]+"),
            "$1" + REDACTED),
        new RedactionRule("basic-auth",
            Pattern.compile("(?i)(authorization\\s*[=:]\\s*basic\\s+)\\S+"),
            "$1" + REDACTED),
        // A JSON field such as {"Authorization":"Basic ..."} reaches the rules as the bare value,
        // without the header name. Anchored to the whole value and required to look like base64
        // (a digit, '+', '/' or '=') so prose such as "Basic configuration" is left alone.
        new RedactionRule("basic-auth-value",
            Pattern.compile("^((?i:basic)\\s+)(?=\\S*[0-9+/=])[A-Za-z0-9+/]+={0,2}$"),
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
            Pattern.compile("(?<!sha256:)(?<!sha512:)(?<!sha384:)(?<![a-zA-Z0-9/+])"
                + "[A-Za-z0-9+/]{40,}={0,2}(?![a-zA-Z0-9/+=])"),
            REDACTED)
    );

    @ConfigProperty(name = "mcp.guardrail.log-redaction.enabled", defaultValue = "true")
    boolean enabled;

    @ConfigProperty(name = "mcp.guardrail.log-redaction.custom-patterns")
    Optional<List<String>> customPatterns;

    // Set once in init() (@PostConstruct) and never mutated afterwards; CDI safely
    // publishes the singleton before it is shared across request threads.
    private List<RedactionRule> activeRules;

    /** CDI constructor. */
    Redactor() {
    }

    /**
     * Create a redactor outside of CDI.
     *
     * @param enabled        whether redaction is active
     * @param customPatterns additional regular expressions to redact (may be empty)
     */
    public Redactor(final boolean enabled, final List<String> customPatterns) {
        this.enabled = enabled;
        this.customPatterns = Optional.ofNullable(customPatterns);
        compileRules();
    }

    /**
     * Compiles custom redaction patterns and merges them with the default rules.
     * Invalid patterns are logged as warnings and skipped.
     */
    @PostConstruct
    void init() {
        compileRules();
    }

    private void compileRules() {
        List<RedactionRule> rules = new ArrayList<>(DEFAULT_RULES);
        if (customPatterns.isPresent()) {
            List<String> patterns = customPatterns.get();
            for (int i = 0; i < patterns.size(); i++) {
                String patternStr = patterns.get(i);
                try {
                    rules.add(new RedactionRule("custom-" + i, Pattern.compile(patternStr), REDACTED));
                    LOG.debugf("Registered custom redaction pattern [%d]: %s", i, patternStr);
                } catch (PatternSyntaxException e) {
                    LOG.warnf("Skipping invalid custom redaction pattern [%d] '%s': %s",
                        i, patternStr, e.getMessage());
                }
            }
        }
        activeRules = Collections.unmodifiableList(rules);
    }

    /**
     * Whether redaction is enabled.
     *
     * @return true when redaction is active
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Apply all pattern rules to a text value.
     *
     * @param text the text to redact
     * @return the redacted text, or the input when redaction is disabled
     */
    public String redactText(final String text) {
        if (!enabled || text == null || text.isEmpty()) {
            return text;
        }
        String result = text;
        for (RedactionRule rule : activeRules) {
            result = rule.pattern().matcher(result).replaceAll(rule.replacement());
        }
        return result;
    }

    /**
     * Redact a JSON tree in place, combining key-name and pattern rules.
     *
     * @param node the tree to redact
     * @return true when any value was changed
     */
    public boolean redactTree(final JsonNode node) {
        if (!enabled || node == null) {
            return false;
        }
        return JsonNodeSanitizer.transformTextNodes(node,
            (key, value) -> redactText(SensitiveKeys.redactValue(key, value)));
    }

    /**
     * Convert arbitrary data to a redacted JSON tree.
     *
     * @param mapper the mapper used to build the tree
     * @param data   the data to convert; the original object is never modified
     * @return a redacted copy of the data as a JSON tree
     */
    public JsonNode redactedCopy(final ObjectMapper mapper, final Object data) {
        JsonNode tree = mapper.valueToTree(data);
        redactTree(tree);
        return tree;
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
