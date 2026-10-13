/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.opensearch.service;

import io.streamshub.mcp.opensearch.config.OpenSearchConfig;
import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.IOException;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.when;

class OpenSearchAuthFilterTest {

    @Mock
    private ClientRequestContext requestContext;

    @Mock
    private OpenSearchConfig config;

    private MultivaluedMap<String, Object> headers;
    private OpenSearchAuthFilter filter;

    OpenSearchAuthFilterTest() {
    }

    @BeforeEach
    void setUp() throws ReflectiveOperationException {
        MockitoAnnotations.openMocks(this);
        headers = new MultivaluedHashMap<>();
        when(requestContext.getHeaders()).thenReturn(headers);

        when(config.authMode()).thenReturn("none");
        when(config.saTokenPath()).thenReturn("/var/run/secrets/kubernetes.io/serviceaccount/token");
        when(config.bearerToken()).thenReturn(Optional.empty());

        filter = new OpenSearchAuthFilter();
        java.lang.reflect.Field configField = OpenSearchAuthFilter.class.getDeclaredField("config");
        configField.setAccessible(true);
        configField.set(filter, config);
    }

    @Test
    void noHeadersAddedInNoneMode() throws IOException {
        filter.filter(requestContext);
        assertNull(headers.getFirst("Authorization"));
    }
}
