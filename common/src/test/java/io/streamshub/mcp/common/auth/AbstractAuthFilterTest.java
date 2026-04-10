/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.auth;

import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link AbstractAuthFilter} shared authentication logic.
 */
class AbstractAuthFilterTest {

    @Mock
    private ClientRequestContext requestContext;

    @Mock
    private AuthConfig authConfig;

    private MultivaluedMap<String, Object> headers;
    private TestAuthFilter filter;

    @TempDir
    Path tempDir;

    AbstractAuthFilterTest() {
    }

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        headers = new MultivaluedHashMap<>();
        when(requestContext.getHeaders()).thenReturn(headers);
        filter = new TestAuthFilter(authConfig);
    }

    @Test
    void noAuthModeDoesNotAddHeaders() throws IOException {
        when(authConfig.authMode()).thenReturn("none");

        filter.filter(requestContext);

        assertNull(headers.getFirst("Authorization"));
    }

    @Test
    void basicAuthModeDoesNotAddHeaders() throws IOException {
        when(authConfig.authMode()).thenReturn("basic");

        filter.filter(requestContext);

        assertNull(headers.getFirst("Authorization"));
    }

    @Test
    void bearerTokenModeAddsAuthorizationHeader() throws IOException {
        when(authConfig.authMode()).thenReturn("bearer-token");
        when(authConfig.bearerToken()).thenReturn(Optional.of("test-token-123"));

        filter.filter(requestContext);

        assertEquals("Bearer test-token-123", headers.getFirst("Authorization"));
    }

    @Test
    void bearerTokenModeTrimsToken() throws IOException {
        when(authConfig.authMode()).thenReturn("bearer-token");
        when(authConfig.bearerToken()).thenReturn(Optional.of("  test-token-456  "));

        filter.filter(requestContext);

        assertEquals("Bearer test-token-456", headers.getFirst("Authorization"));
    }

    @Test
    void bearerTokenModeWithoutTokenDoesNotAddHeader() throws IOException {
        when(authConfig.authMode()).thenReturn("bearer-token");
        when(authConfig.bearerToken()).thenReturn(Optional.empty());

        filter.filter(requestContext);

        assertNull(headers.getFirst("Authorization"));
    }

    @Test
    void bearerTokenModeWithBlankTokenDoesNotAddHeader() throws IOException {
        when(authConfig.authMode()).thenReturn("bearer-token");
        when(authConfig.bearerToken()).thenReturn(Optional.of("   "));

        filter.filter(requestContext);

        assertNull(headers.getFirst("Authorization"));
    }

    @Test
    void serviceAccountTokenModeReadsTokenFromFile() throws IOException {
        Path tokenFile = tempDir.resolve("token");
        Files.writeString(tokenFile, "sa-token-content");

        when(authConfig.authMode()).thenReturn("sa-token");
        when(authConfig.saTokenPath()).thenReturn(tokenFile.toString());

        filter.filter(requestContext);

        assertEquals("Bearer sa-token-content", headers.getFirst("Authorization"));
    }

    @Test
    void serviceAccountTokenModeTrimsTokenFromFile() throws IOException {
        Path tokenFile = tempDir.resolve("token");
        Files.writeString(tokenFile, "  sa-token-with-whitespace  \n");

        when(authConfig.authMode()).thenReturn("sa-token");
        when(authConfig.saTokenPath()).thenReturn(tokenFile.toString());

        filter.filter(requestContext);

        assertEquals("Bearer sa-token-with-whitespace", headers.getFirst("Authorization"));
    }

    @Test
    void serviceAccountTokenModeWithMissingFileDoesNotAddHeader() throws IOException {
        when(authConfig.authMode()).thenReturn("sa-token");
        when(authConfig.saTokenPath()).thenReturn("/nonexistent/token");

        filter.filter(requestContext);

        assertNull(headers.getFirst("Authorization"));
    }

    @Test
    void unknownAuthModeDoesNotAddHeader() throws IOException {
        when(authConfig.authMode()).thenReturn("unknown-mode");

        filter.filter(requestContext);

        assertNull(headers.getFirst("Authorization"));
    }

    @Test
    void authModeIsCaseInsensitive() throws IOException {
        when(authConfig.authMode()).thenReturn("BEARER-TOKEN");
        when(authConfig.bearerToken()).thenReturn(Optional.of("test-token"));

        filter.filter(requestContext);

        assertEquals("Bearer test-token", headers.getFirst("Authorization"));
    }

    /**
     * Concrete test subclass of {@link AbstractAuthFilter}.
     */
    private static class TestAuthFilter extends AbstractAuthFilter {

        private final AuthConfig config;

        TestAuthFilter(final AuthConfig config) {
            this.config = config;
        }

        @Override
        protected AuthConfig authConfig() {
            return config;
        }

        @Override
        protected String providerName() {
            return "Test";
        }

        @Override
        protected String bearerTokenPropertyName() {
            return "test.bearer-token";
        }
    }
}
