/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.loki.service;

import io.streamshub.mcp.common.auth.AuthHelper;
import io.streamshub.mcp.loki.config.LokiConfig;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.client.ClientRequestFilter;
import org.jboss.logging.Logger;

import java.util.Locale;

/**
 * JAX-RS client request filter that adds authentication and tenant ID to Loki API calls.
 * Supports service account token, explicit bearer token, basic auth, and no-auth modes.
 * Basic auth and mTLS are handled natively by Quarkus REST client properties.
 *
 * <p>Registered on {@link LokiClient} via {@code @RegisterProvider},
 * so it only applies to Loki API calls.</p>
 */
public class LokiAuthFilter implements ClientRequestFilter {

    private static final Logger LOG = Logger.getLogger(LokiAuthFilter.class);

    @Inject
    LokiConfig config;

    LokiAuthFilter() {
    }

    @Override
    public void filter(final ClientRequestContext requestContext) {
        addTenantId(requestContext);
        addAuthentication(requestContext);
    }

    private void addTenantId(final ClientRequestContext requestContext) {
        config.tenantId()
            .filter(id -> !id.isBlank())
            .ifPresent(id -> requestContext.getHeaders().putSingle("X-Scope-OrgID", id.trim()));
    }

    private void addAuthentication(final ClientRequestContext requestContext) {
        String authMode = config.authMode().toLowerCase(Locale.ROOT);

        switch (authMode) {
            case AuthHelper.AUTH_MODE_SA_TOKEN:
                AuthHelper.readServiceAccountToken(config.saTokenPath())
                    .ifPresent(token -> requestContext.getHeaders()
                        .putSingle("Authorization", "Bearer " + token));
                break;
            case AuthHelper.AUTH_MODE_BEARER_TOKEN:
                AuthHelper.validateBearerToken(config.bearerToken(), "mcp.log.loki.bearer-token")
                    .ifPresent(token -> requestContext.getHeaders()
                        .putSingle("Authorization", "Bearer " + token));
                break;
            case AuthHelper.AUTH_MODE_BASIC:
                break;
            case AuthHelper.AUTH_MODE_NONE:
                break;
            default:
                LOG.warnf("Unknown Loki auth mode: '%s'. No authentication applied.", authMode);
                break;
        }
    }
}
