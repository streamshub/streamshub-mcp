/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.loki.service;

import io.streamshub.mcp.loki.config.LokiConfig;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.client.ClientRequestFilter;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
            case "sa-token":
                addServiceAccountToken(requestContext);
                break;
            case "bearer-token":
                addBearerToken(requestContext);
                break;
            case "basic":
                // Basic auth is handled by Quarkus REST client properties:
                // quarkus.rest-client.loki.username / password
                break;
            case "none":
                break;
            default:
                LOG.warnf("Unknown Loki auth mode: '%s'. No authentication applied.", authMode);
                break;
        }
    }

    private void addServiceAccountToken(final ClientRequestContext requestContext) {
        try {
            String token = Files.readString(Path.of(config.saTokenPath())).trim();
            requestContext.getHeaders().putSingle("Authorization", "Bearer " + token);
        } catch (IOException e) {
            LOG.warnf("Failed to read service account token from '%s': %s",
                config.saTokenPath(), e.getMessage());
        }
    }

    private void addBearerToken(final ClientRequestContext requestContext) {
        config.bearerToken()
            .filter(t -> !t.isBlank())
            .ifPresentOrElse(
                token -> requestContext.getHeaders().putSingle("Authorization",
                    "Bearer " + token.trim()),
                () -> LOG.warn("Bearer-token auth mode selected but no token configured."
                    + " Set 'mcp.log.loki.bearer-token' property."));
    }
}
