/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.loki.service;

import io.streamshub.mcp.loki.config.LokiConfig;
import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.IOException;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link LokiAuthFilter} Loki-specific header behavior.
 * Core authentication logic is tested in
 * {@link io.streamshub.mcp.common.auth.AbstractAuthFilterTest}.
 */
class LokiAuthFilterTest {

    @Mock
    private ClientRequestContext requestContext;

    @Mock
    private LokiConfig config;

    private MultivaluedMap<String, Object> headers;
    private LokiAuthFilter filter;

    LokiAuthFilterTest() {
    }

    @BeforeEach
    void setUp() throws ReflectiveOperationException {
        MockitoAnnotations.openMocks(this);
        headers = new MultivaluedHashMap<>();
        when(requestContext.getHeaders()).thenReturn(headers);

        when(config.authMode()).thenReturn("none");
        when(config.saTokenPath()).thenReturn("/var/run/secrets/kubernetes.io/serviceaccount/token");
        when(config.bearerToken()).thenReturn(Optional.empty());

        filter = new LokiAuthFilter();
        java.lang.reflect.Field configField = LokiAuthFilter.class.getDeclaredField("config");
        configField.setAccessible(true);
        configField.set(filter, config);
    }

    @Test
    void tenantIdHeaderIsAddedWhenConfigured() throws IOException {
        when(config.tenantId()).thenReturn(Optional.of("my-tenant"));

        filter.filter(requestContext);

        assertEquals("my-tenant", headers.getFirst("X-Scope-OrgID"));
    }

    @Test
    void tenantIdHeaderIsNotAddedWhenEmpty() throws IOException {
        when(config.tenantId()).thenReturn(Optional.empty());

        filter.filter(requestContext);

        assertNull(headers.getFirst("X-Scope-OrgID"));
    }

    @Test
    void tenantIdHeaderIsNotAddedWhenBlank() throws IOException {
        when(config.tenantId()).thenReturn(Optional.of("   "));

        filter.filter(requestContext);

        assertNull(headers.getFirst("X-Scope-OrgID"));
    }

    @Test
    void tenantIdHeaderIsTrimmed() throws IOException {
        when(config.tenantId()).thenReturn(Optional.of("  my-tenant  "));

        filter.filter(requestContext);

        assertEquals("my-tenant", headers.getFirst("X-Scope-OrgID"));
    }
}
