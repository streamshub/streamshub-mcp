/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.loki.service;

import io.streamshub.mcp.common.auth.AbstractAuthFilter;
import io.streamshub.mcp.common.auth.AuthConfig;
import io.streamshub.mcp.loki.config.LokiConfig;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.ClientRequestContext;

/**
 * JAX-RS client request filter that adds authentication and tenant ID to Loki API calls.
 * Supports service account token, explicit bearer token, basic auth, and no-auth modes.
 * Basic auth and mTLS are handled natively by Quarkus REST client properties.
 *
 * <p>Registered on {@link LokiClient} via {@code @RegisterProvider},
 * so it only applies to Loki API calls.</p>
 */
public class LokiAuthFilter extends AbstractAuthFilter {

    @Inject
    LokiConfig config;

    LokiAuthFilter() {
    }

    @Override
    protected AuthConfig authConfig() {
        return config;
    }

    @Override
    protected String providerName() {
        return "Loki";
    }

    @Override
    protected String bearerTokenPropertyName() {
        return "mcp.log.loki.bearer-token";
    }

    @Override
    protected void addProviderSpecificHeaders(final ClientRequestContext requestContext) {
        config.tenantId()
            .filter(id -> !id.isBlank())
            .ifPresent(id -> requestContext.getHeaders().putSingle("X-Scope-OrgID", id.trim()));
    }
}
