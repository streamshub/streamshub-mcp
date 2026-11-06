/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.guardrail;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link SensitiveKeys}.
 */
class SensitiveKeysTest {

    SensitiveKeysTest() {
    }

    @Test
    void testSecretKeysAreDetected() {
        for (String key : new String[] {
            "password", "database.password", "ssl.key.password", "ssl.keystore.password",
            "sasl.jaas.config", "client.secret", "oauth.client.secret", "oauth.access.token",
            "api.key", "apiKey", "api_key", "aws.secretAccessKey", "private.key", "privateKey",
            "ssl.truststore.credentials", "passphrase", "DB_PASSWORD", "AccessKeyId", "S3_SECRET",
            "proxy.authorization", "azure.account.key"}) {
            assertTrue(SensitiveKeys.isSensitiveConfigKey(key), key);
        }
        for (String key : new String[] {"storage.key", "storage.auth", "azure.account.key"}) {
            assertTrue(SensitiveKeys.isSensitiveConfigKey(key), key);
        }
    }

    @Test
    void testConnectAndSchemaRegistryCredentialKeysAreDetected() {
        for (String key : new String[] {
            "basic.auth.user.info", "value.converter.basic.auth.user.info",
            "key.converter.basic.auth.user.info", "schema.registry.basic.auth.user.info",
            "basicAuthUserInfo", "proxy.pass"}) {
            assertTrue(SensitiveKeys.isSensitiveConfigKey(key), key);
        }
        assertEquals("[REDACTED]", SensitiveKeys.redactValues(
            Map.of("value.converter.basic.auth.user.info", "admin:hunter2"))
            .get("value.converter.basic.auth.user.info"));
    }

    @Test
    void testAuthAndPassWordsDoNotMakeResponseFieldNamesSensitive() {
        for (String key : new String[] {"auth_type", "pass_count", "authentication"}) {
            assertFalse(SensitiveKeys.isSensitive(key), key);
        }
    }

    @Test
    void testAuthRelatedNonSecretConfigKeysAreKept() {
        for (String key : new String[] {
            "auth.enabled", "auth.type", "authentication.type", "authorizer.class.name",
            "basic.auth.credentials.source", "value.converter.basic.auth.credentials.source",
            "schema.registry.basic.auth.credentials.source", "ssl.client.auth",
            "listener.name.internal.ssl.client.auth", "oauth.client.id", "passes", "bypass.mode"}) {
            assertFalse(SensitiveKeys.isSensitiveConfigKey(key), key);
        }
        assertEquals("USER_INFO", SensitiveKeys.redactValues(
            Map.of("value.converter.basic.auth.credentials.source", "USER_INFO"))
            .get("value.converter.basic.auth.credentials.source"));
        assertEquals("required", SensitiveKeys.redactValues(Map.of("ssl.client.auth", "required"))
            .get("ssl.client.auth"));
    }

    @Test
    void testResponseFieldNamesEndingInKeyAreKept() {
        for (String key : new String[] {"config_map_key", "topology_key", "storage.key"}) {
            assertFalse(SensitiveKeys.isSensitive(key), key);
        }
        assertTrue(SensitiveKeys.isSensitive("sasl.jaas.config"));
        assertTrue(SensitiveKeys.isSensitive("password"));
    }

    @Test
    void testNonSecretKeysAreKept() {
        for (String key : new String[] {
            "key.converter", "value.converter", "delegation.token.max.lifetime.ms", "tasks.max",
            "secret_name", "push_secret", "secretName", "password.file.path", "token.endpoint.uri",
            "ssl.keystore.location", "ssl.key.type", "bootstrap.servers", "topic.name", "partitions",
            "retention.ms", "cleanup.policy", "sasl.login.callback.handler.class", "keystore.location",
            "partition.key", "primary.key", "authentication.type", "oauth.client.id", "group.id"}) {
            assertFalse(SensitiveKeys.isSensitiveConfigKey(key), key);
        }
    }

    @Test
    void testRedactValueKeepsConfigProviderReferences() {
        assertEquals("[REDACTED]", SensitiveKeys.redactValue("db.password", "hunter2"));
        assertEquals("${secrets:ns/db:password}",
            SensitiveKeys.redactValue("db.password", "${secrets:ns/db:password}"));
        assertEquals("hunter2", SensitiveKeys.redactValue("db.user", "hunter2"));
    }

    @Test
    void testRedactValuesCopiesAndRecurses() {
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("client.secret", "abc");
        nested.put("flag", Boolean.TRUE);
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("password", "hunter2");
        config.put("numeric.token", 12345);
        config.put("tasks.max", 2);
        config.put("nested", nested);

        Map<String, Object> redacted = SensitiveKeys.redactValues(config);

        assertEquals("[REDACTED]", redacted.get("password"));
        assertEquals("[REDACTED]", redacted.get("numeric.token"));
        assertEquals(2, redacted.get("tasks.max"));
        @SuppressWarnings("unchecked")
        Map<String, Object> redactedNested = (Map<String, Object>) redacted.get("nested");
        assertEquals("[REDACTED]", redactedNested.get("client.secret"));
        assertEquals(Boolean.TRUE, redactedNested.get("flag"));
        assertEquals("hunter2", config.get("password"), "source map must not be modified");
    }

    @Test
    void testRedactValuesHandlesNullAndEmpty() {
        assertNull(SensitiveKeys.redactValues(null));
        assertTrue(SensitiveKeys.redactValues(Map.of()).isEmpty());
    }
}
