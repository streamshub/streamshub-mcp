/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.auth;

import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.client.ClientRequestFilter;
import org.jboss.logging.Logger;

import java.util.Locale;

/**
 * Base JAX-RS client request filter that adds authentication headers to outgoing API calls.
 * Supports service account token, explicit bearer token, basic auth, and no-auth modes.
 * Basic auth and mTLS are handled natively by Quarkus REST client properties.
 *
 * <p>Subclasses provide the provider-specific configuration and can add
 * extra headers (e.g. tenant ID) via {@link #addProviderSpecificHeaders}.</p>
 */
public abstract class AbstractAuthFilter implements ClientRequestFilter {

    private static final Logger LOG = Logger.getLogger(AbstractAuthFilter.class);

    /**
     * Returns the authentication configuration for this provider.
     *
     * @return the auth config
     */
    protected abstract AuthConfig authConfig();

    /**
     * Returns a human-readable provider name used in warning messages (e.g. "Loki", "Prometheus").
     *
     * @return the provider name
     */
    protected abstract String providerName();

    /**
     * Returns the full configuration property name for the bearer token,
     * used in warning messages when the token is missing (e.g. "mcp.log.loki.bearer-token").
     *
     * @return the bearer token property name
     */
    protected abstract String bearerTokenPropertyName();

    /**
     * Hook for subclasses to add provider-specific headers (e.g. tenant ID).
     * Default implementation does nothing.
     *
     * @param requestContext the outgoing request context
     */
    protected void addProviderSpecificHeaders(final ClientRequestContext requestContext) {
        // no-op by default
    }

    @Override
    public void filter(final ClientRequestContext requestContext) {
        addProviderSpecificHeaders(requestContext);
        addAuthentication(requestContext);
    }

    private void addAuthentication(final ClientRequestContext requestContext) {
        String authMode = authConfig().authMode().toLowerCase(Locale.ROOT);

        switch (authMode) {
            case AuthHelper.AUTH_MODE_SA_TOKEN:
                AuthHelper.readServiceAccountToken(authConfig().saTokenPath())
                    .ifPresent(token -> requestContext.getHeaders()
                        .putSingle("Authorization", "Bearer " + token));
                break;
            case AuthHelper.AUTH_MODE_BEARER_TOKEN:
                AuthHelper.validateBearerToken(authConfig().bearerToken(), bearerTokenPropertyName())
                    .ifPresent(token -> requestContext.getHeaders()
                        .putSingle("Authorization", "Bearer " + token));
                break;
            case AuthHelper.AUTH_MODE_BASIC:
                break;
            case AuthHelper.AUTH_MODE_NONE:
                break;
            default:
                LOG.warnf("Unknown %s auth mode: '%s'. No authentication applied.",
                    providerName(), authMode);
                break;
        }
    }
}
