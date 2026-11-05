/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.guardrail;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Decides whether a configuration or JSON key names a secret value, and redacts the
 * value when it does.
 *
 * <p>Keys are split into lower-case words on any non-alphanumeric character and on
 * camelCase boundaries, then matched word by word. This avoids the false positives of
 * plain substring matching: {@code key.converter} and
 * {@code delegation.token.max.lifetime.ms} are not secrets, while {@code ssl.key.password},
 * {@code sasl.jaas.config}, {@code aws.secretAccessKey} and {@code oauth.access.token} are.</p>
 *
 * <p>Values that are only a config-provider reference such as
 * {@code ${secrets:ns/name:password}} are kept, because they name where a secret lives
 * rather than being the secret itself.</p>
 */
public final class SensitiveKeys {

    /** Replacement for redacted values. */
    public static final String REDACTED = "[REDACTED]";

    private static final Pattern WORD_SEPARATOR = Pattern.compile("[^a-z0-9]+");
    private static final Pattern CAMEL_BOUNDARY = Pattern.compile("([a-z0-9])([A-Z])");
    private static final Pattern PROVIDER_REFERENCE = Pattern.compile("\\$\\{[A-Za-z0-9_.\\-]+:[^}]*}");

    /** Words that mark a key as holding a secret. */
    private static final Set<String> SECRET_WORDS = Set.of(
        "password", "passwd", "pwd", "passphrase", "secret", "secrets",
        "credential", "credentials", "token", "jaas", "apikey", "privatekey");

    /** Words that, directly before {@code key}, mark a key as holding a secret. */
    private static final Set<String> SECRET_KEY_QUALIFIERS = Set.of(
        "api", "private", "access", "signing", "encryption", "account", "secret", "master");

    /**
     * Words that mark a key as holding a secret only when they are the last word. Applied to
     * configuration maps only: in arbitrary response JSON a field like {@code config_map_key}
     * or {@code topology_key} names a key, not a secret.
     */
    private static final Set<String> SECRET_LAST_WORDS = Set.of("key", "authorization");

    /**
     * Words that mark a key as holding a secret wherever they appear. Applied to configuration
     * maps only, for the same reason as {@link #SECRET_LAST_WORDS}. They catch keys that end in a
     * descriptive word, such as {@code basic.auth.user.info} (Schema Registry credentials in
     * Connect and Debezium configs) or {@code proxy.pass}. Whole-word matching keeps
     * {@code authentication.type} and {@code oauth.client.id} out.
     */
    private static final Set<String> SECRET_CONFIG_WORDS = Set.of("auth", "pass");

    /** Words that, directly before a trailing {@code key}, mean a record key rather than a secret. */
    private static final Set<String> DATA_KEY_QUALIFIERS = Set.of(
        "primary", "partition", "message", "record", "foreign", "sort", "hash", "row", "routing");

    /**
     * Last words that mean the value describes a secret rather than being one. {@code source}
     * covers {@code basic.auth.credentials.source} (values {@code USER_INFO}, {@code URL},
     * {@code SASL_INHERIT}).
     */
    private static final Set<String> NON_SECRET_SUFFIXES = Set.of(
        "name", "names", "ref", "type", "url", "uri", "endpoint", "location", "path", "file",
        "ms", "seconds", "lifetime", "interval", "enabled", "enable", "size", "count", "class", "provider",
        "source");

    /** Whole keys that name a secret resource without containing a secret. */
    private static final Set<String> SAFE_KEYS = Set.of("push_secret");

    private SensitiveKeys() {
    }

    /**
     * Check whether a JSON field name in a tool response names a secret value.
     *
     * @param key the JSON key (may be null)
     * @return true when the value stored under the key should be redacted
     */
    public static boolean isSensitive(final String key) {
        return isSensitive(key, false);
    }

    /**
     * Check whether a Kubernetes/Kafka configuration key names a secret value. Stricter than
     * {@link #isSensitive(String)}: a trailing {@code key} word such as in {@code storage.key},
     * and an {@code auth} or {@code pass} word anywhere such as in {@code basic.auth.user.info},
     * are also treated as a secret.
     *
     * @param key the configuration key (may be null)
     * @return true when the value stored under the key should be redacted
     */
    public static boolean isSensitiveConfigKey(final String key) {
        return isSensitive(key, true);
    }

    private static boolean isSensitive(final String key, final boolean config) {
        if (key == null || key.isEmpty()) {
            return false;
        }
        String normalized = CAMEL_BOUNDARY.matcher(key).replaceAll("$1_$2").toLowerCase(Locale.ROOT);
        if (SAFE_KEYS.contains(normalized)) {
            return false;
        }
        List<String> words = Arrays.stream(WORD_SEPARATOR.split(normalized))
            .filter(w -> !w.isEmpty())
            .toList();
        if (words.isEmpty() || NON_SECRET_SUFFIXES.contains(words.getLast())) {
            return false;
        }
        String last = words.getLast();
        if (isClientAuthMode(words)) {
            return false;
        }
        if (config && SECRET_LAST_WORDS.contains(last)
                && !(words.size() > 1 && "key".equals(last)
                && DATA_KEY_QUALIFIERS.contains(words.get(words.size() - 2)))) {
            return true;
        }
        for (int i = 0; i < words.size(); i++) {
            String word = words.get(i);
            if (SECRET_WORDS.contains(word) || config && SECRET_CONFIG_WORDS.contains(word)) {
                return true;
            }
            if ("key".equals(word) && i > 0 && SECRET_KEY_QUALIFIERS.contains(words.get(i - 1))) {
                return true;
            }
        }
        return false;
    }

    /** {@code ssl.client.auth} is a mode (none, requested, required), not a credential. */
    private static boolean isClientAuthMode(final List<String> words) {
        int n = words.size();
        return n > 1 && "auth".equals(words.get(n - 1)) && "client".equals(words.get(n - 2));
    }

    /**
     * Redact a text value when its key names a secret.
     *
     * @param key   the key the value is stored under (may be null)
     * @param value the text value
     * @return {@link #REDACTED} when the key is sensitive and the value is a literal,
     *         otherwise the value unchanged
     */
    public static String redactValue(final String key, final String value) {
        return redactValue(key, value, false);
    }

    private static String redactValue(final String key, final String value, final boolean config) {
        if (value == null || value.isEmpty() || !isSensitive(key, config) || isReference(value)) {
            return value;
        }
        return REDACTED;
    }

    /**
     * Copy a configuration map, replacing every value stored under a sensitive key.
     * Nested maps are processed recursively.
     *
     * @param config the configuration map (may be null)
     * @return a redacted copy, or null when the input is null
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> redactValues(final Map<String, ?> config) {
        if (config == null) {
            return null;
        }
        if (config.isEmpty()) {
            return new LinkedHashMap<>();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, ?> entry : config.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof Map<?, ?> nested) {
                result.put(entry.getKey(), redactValues((Map<String, ?>) nested));
            } else if (value instanceof String s) {
                result.put(entry.getKey(), redactValue(entry.getKey(), s, true));
            } else if (value != null && isSensitiveConfigKey(entry.getKey())) {
                result.put(entry.getKey(), REDACTED);
            } else {
                result.put(entry.getKey(), value);
            }
        }
        return result;
    }

    private static boolean isReference(final String value) {
        return PROVIDER_REFERENCE.matcher(value).matches();
    }
}
