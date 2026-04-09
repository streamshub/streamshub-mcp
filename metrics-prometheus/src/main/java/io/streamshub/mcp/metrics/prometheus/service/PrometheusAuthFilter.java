/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.metrics.prometheus.service;

import io.streamshub.mcp.common.auth.AuthHelper;
import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.client.ClientRequestFilter;
import org.eclipse.microprofile.config.ConfigProvider;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.util.Locale;

/**
 * JAX-RS client request filter that adds authentication to Prometheus API calls.
 * Supports service account token, explicit bearer token, and no-auth modes.
 * Basic auth and mTLS are handled natively by Quarkus REST client properties.
 *
 * <p>Registered on {@link PrometheusClient} via {@code @RegisterProvider},
 * so it only applies to Prometheus API calls.</p>
 */
public class PrometheusAuthFilter implements ClientRequestFilter {

    private static final Logger LOG = Logger.getLogger(PrometheusAuthFilter.class);

    private static final String AUTH_MODE_KEY = "mcp.metrics.prometheus.auth-mode";
    private static final String BEARER_TOKEN_KEY = "mcp.metrics.prometheus.bearer-token";
    private static final String SA_TOKEN_PATH_KEY = "mcp.metrics.prometheus.sa-token-path";

    PrometheusAuthFilter() {
        // package-private no-arg constructor for CDI
    }

    @Override
    public void filter(final ClientRequestContext requestContext) throws IOException {
        String authMode = ConfigProvider.getConfig()
            .getOptionalValue(AUTH_MODE_KEY, String.class)
            .orElse("none")
            .toLowerCase(Locale.ROOT);

        switch (authMode) {
            case AuthHelper.AUTH_MODE_SA_TOKEN:
                String tokenPath = ConfigProvider.getConfig()
                    .getOptionalValue(SA_TOKEN_PATH_KEY, String.class)
                    .orElse(AuthHelper.DEFAULT_SA_TOKEN_PATH);
                AuthHelper.readServiceAccountToken(tokenPath)
                    .ifPresent(token -> requestContext.getHeaders()
                        .putSingle("Authorization", "Bearer " + token));
                break;
            case AuthHelper.AUTH_MODE_BEARER_TOKEN:
                AuthHelper.validateBearerToken(
                        ConfigProvider.getConfig().getOptionalValue(BEARER_TOKEN_KEY, String.class),
                        BEARER_TOKEN_KEY)
                    .ifPresent(token -> requestContext.getHeaders()
                        .putSingle("Authorization", "Bearer " + token));
                break;
            case AuthHelper.AUTH_MODE_BASIC:
                // Basic auth is handled by Quarkus REST client properties:
                // quarkus.rest-client.prometheus.username / password
                break;
            case AuthHelper.AUTH_MODE_NONE:
                break;
            default:
                LOG.warnf("Unknown Prometheus auth mode: '%s'. No authentication applied.", authMode);
                break;
        }
    }
}
