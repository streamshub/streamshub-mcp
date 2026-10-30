/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.auth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link AuthHelper}.
 */
class AuthHelperTest {

    @TempDir
    Path tempDir;

    AuthHelperTest() {
    }

    @Test
    void testReadServiceAccountTokenTrimsWhitespace() throws IOException {
        Path file = tempDir.resolve("token");
        Files.writeString(file, "  abc.def\n");

        assertEquals(Optional.of("abc.def"), AuthHelper.readServiceAccountToken(file.toString()));
    }

    @Test
    void testReadServiceAccountTokenEmptyWhenFileMissing() {
        assertTrue(AuthHelper.readServiceAccountToken(tempDir.resolve("missing").toString()).isEmpty());
    }

    @Test
    void testValidateBearerTokenTrimsConfiguredValue() {
        assertEquals(Optional.of("tok"), AuthHelper.validateBearerToken(Optional.of(" tok "), "p"));
    }

    @Test
    void testValidateBearerTokenEmptyWhenAbsentOrBlank() {
        assertTrue(AuthHelper.validateBearerToken(Optional.empty(), "p").isEmpty());
        assertTrue(AuthHelper.validateBearerToken(Optional.of("   "), "p").isEmpty());
    }
}
