/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.metrics.prometheus.service;

import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.MockitoAnnotations;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link PrometheusAuthFilter}.
 */
class PrometheusAuthFilterTest {

    PrometheusAuthFilterTest() {
    }

    @Mock
    private ClientRequestContext requestContext;

    @Mock
    private Config config;

    private MultivaluedMap<String, Object> headers;
    private PrometheusAuthFilter filter;
    private MockedStatic<ConfigProvider> configProviderMock;
    private AutoCloseable mocks;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        mocks = MockitoAnnotations.openMocks(this);
        filter = new PrometheusAuthFilter();
        headers = new MultivaluedHashMap<>();

        when(requestContext.getHeaders()).thenReturn(headers);

        configProviderMock = mockStatic(ConfigProvider.class);
        configProviderMock.when(ConfigProvider::getConfig).thenReturn(config);
    }

    @AfterEach
    void tearDown() throws Exception {
        configProviderMock.close();
        mocks.close();
    }

    @Test
    void noAuthModeDoesNotAddHeaders() throws IOException {
        when(config.getOptionalValue(eq("mcp.metrics.prometheus.auth-mode"), eq(String.class)))
            .thenReturn(Optional.of("none"));

        filter.filter(requestContext);

        assertNull(headers.getFirst("Authorization"));
    }

    @Test
    void defaultAuthModeIsNone() throws IOException {
        when(config.getOptionalValue(eq("mcp.metrics.prometheus.auth-mode"), eq(String.class)))
            .thenReturn(Optional.empty());

        filter.filter(requestContext);

        assertNull(headers.getFirst("Authorization"));
    }

    @Test
    void basicAuthModeDoesNotAddHeaders() throws IOException {
        when(config.getOptionalValue(eq("mcp.metrics.prometheus.auth-mode"), eq(String.class)))
            .thenReturn(Optional.of("basic"));

        filter.filter(requestContext);

        assertNull(headers.getFirst("Authorization"));
    }

    @Test
    void bearerTokenModeAddsAuthorizationHeader() throws IOException {
        when(config.getOptionalValue(eq("mcp.metrics.prometheus.auth-mode"), eq(String.class)))
            .thenReturn(Optional.of("bearer-token"));
        when(config.getOptionalValue(eq("mcp.metrics.prometheus.bearer-token"), eq(String.class)))
            .thenReturn(Optional.of("test-token-123"));

        filter.filter(requestContext);

        assertEquals("Bearer test-token-123", headers.getFirst("Authorization"));
    }

    @Test
    void bearerTokenModeTrimsToken() throws IOException {
        when(config.getOptionalValue(eq("mcp.metrics.prometheus.auth-mode"), eq(String.class)))
            .thenReturn(Optional.of("bearer-token"));
        when(config.getOptionalValue(eq("mcp.metrics.prometheus.bearer-token"), eq(String.class)))
            .thenReturn(Optional.of("  test-token-456  "));

        filter.filter(requestContext);

        assertEquals("Bearer test-token-456", headers.getFirst("Authorization"));
    }

    @Test
    void bearerTokenModeWithoutTokenDoesNotAddHeader() throws IOException {
        when(config.getOptionalValue(eq("mcp.metrics.prometheus.auth-mode"), eq(String.class)))
            .thenReturn(Optional.of("bearer-token"));
        when(config.getOptionalValue(eq("mcp.metrics.prometheus.bearer-token"), eq(String.class)))
            .thenReturn(Optional.empty());

        filter.filter(requestContext);

        assertNull(headers.getFirst("Authorization"));
    }

    @Test
    void bearerTokenModeWithBlankTokenDoesNotAddHeader() throws IOException {
        when(config.getOptionalValue(eq("mcp.metrics.prometheus.auth-mode"), eq(String.class)))
            .thenReturn(Optional.of("bearer-token"));
        when(config.getOptionalValue(eq("mcp.metrics.prometheus.bearer-token"), eq(String.class)))
            .thenReturn(Optional.of("   "));

        filter.filter(requestContext);

        assertNull(headers.getFirst("Authorization"));
    }

    @Test
    void serviceAccountTokenModeReadsTokenFromFile() throws IOException {
        Path tokenFile = tempDir.resolve("token");
        Files.writeString(tokenFile, "sa-token-content");

        when(config.getOptionalValue(eq("mcp.metrics.prometheus.auth-mode"), eq(String.class)))
            .thenReturn(Optional.of("sa-token"));
        when(config.getOptionalValue(eq("mcp.metrics.prometheus.sa-token-path"), eq(String.class)))
            .thenReturn(Optional.of(tokenFile.toString()));

        filter.filter(requestContext);

        assertEquals("Bearer sa-token-content", headers.getFirst("Authorization"));
    }

    @Test
    void serviceAccountTokenModeTrimsTokenFromFile() throws IOException {
        Path tokenFile = tempDir.resolve("token");
        Files.writeString(tokenFile, "  sa-token-with-whitespace  \n");

        when(config.getOptionalValue(eq("mcp.metrics.prometheus.auth-mode"), eq(String.class)))
            .thenReturn(Optional.of("sa-token"));
        when(config.getOptionalValue(eq("mcp.metrics.prometheus.sa-token-path"), eq(String.class)))
            .thenReturn(Optional.of(tokenFile.toString()));

        filter.filter(requestContext);

        assertEquals("Bearer sa-token-with-whitespace", headers.getFirst("Authorization"));
    }

    @Test
    void serviceAccountTokenModeWithMissingFileDoesNotAddHeader() throws IOException {
        when(config.getOptionalValue(eq("mcp.metrics.prometheus.auth-mode"), eq(String.class)))
            .thenReturn(Optional.of("sa-token"));
        when(config.getOptionalValue(eq("mcp.metrics.prometheus.sa-token-path"), eq(String.class)))
            .thenReturn(Optional.of("/nonexistent/token"));

        filter.filter(requestContext);

        assertNull(headers.getFirst("Authorization"));
    }

    @Test
    void serviceAccountTokenModeUsesDefaultPath() throws IOException {
        when(config.getOptionalValue(eq("mcp.metrics.prometheus.auth-mode"), eq(String.class)))
            .thenReturn(Optional.of("sa-token"));
        when(config.getOptionalValue(eq("mcp.metrics.prometheus.sa-token-path"), eq(String.class)))
            .thenReturn(Optional.empty());

        filter.filter(requestContext);

        // Default path won't exist in test environment, so no header should be added
        assertNull(headers.getFirst("Authorization"));
    }

    @Test
    void unknownAuthModeDoesNotAddHeader() throws IOException {
        when(config.getOptionalValue(eq("mcp.metrics.prometheus.auth-mode"), eq(String.class)))
            .thenReturn(Optional.of("unknown-mode"));

        filter.filter(requestContext);

        assertNull(headers.getFirst("Authorization"));
    }

    @Test
    void authModeIsCaseInsensitive() throws IOException {
        when(config.getOptionalValue(eq("mcp.metrics.prometheus.auth-mode"), eq(String.class)))
            .thenReturn(Optional.of("BEARER-TOKEN"));
        when(config.getOptionalValue(eq("mcp.metrics.prometheus.bearer-token"), eq(String.class)))
            .thenReturn(Optional.of("test-token"));

        filter.filter(requestContext);

        assertEquals("Bearer test-token", headers.getFirst("Authorization"));
    }
}
