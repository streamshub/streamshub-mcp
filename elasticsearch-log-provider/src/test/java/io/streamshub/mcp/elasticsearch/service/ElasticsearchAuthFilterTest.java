/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.elasticsearch.service;

import io.streamshub.mcp.elasticsearch.config.ElasticsearchConfig;
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

class ElasticsearchAuthFilterTest {

    @Mock
    private ClientRequestContext requestContext;

    @Mock
    private ElasticsearchConfig config;

    private MultivaluedMap<String, Object> headers;
    private ElasticsearchAuthFilter filter;

    ElasticsearchAuthFilterTest() {
    }

    @BeforeEach
    void setUp() throws ReflectiveOperationException {
        MockitoAnnotations.openMocks(this);
        headers = new MultivaluedHashMap<>();
        when(requestContext.getHeaders()).thenReturn(headers);

        when(config.authMode()).thenReturn("none");
        when(config.saTokenPath()).thenReturn("/var/run/secrets/kubernetes.io/serviceaccount/token");
        when(config.bearerToken()).thenReturn(Optional.empty());

        filter = new ElasticsearchAuthFilter();
        java.lang.reflect.Field configField = ElasticsearchAuthFilter.class.getDeclaredField("config");
        configField.setAccessible(true);
        configField.set(filter, config);
    }

    @Test
    void noHeadersAddedInNoneMode() throws IOException {
        filter.filter(requestContext);
        assertNull(headers.getFirst("Authorization"));
    }
}
