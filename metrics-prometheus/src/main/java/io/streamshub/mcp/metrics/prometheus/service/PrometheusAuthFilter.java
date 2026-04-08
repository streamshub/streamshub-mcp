/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.metrics.prometheus.service;

import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.client.ClientRequestFilter;
import org.eclipse.microprofile.config.ConfigProvider;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;

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
    private static final String DEFAULT_SA_TOKEN_PATH =
        "/var/run/secrets/kubernetes.io/serviceaccount/token";

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
            case "sa-token":
                addServiceAccountToken(requestContext);
                break;
            case "bearer-token":
                addBearerToken(requestContext);
                break;
            case "basic":
                // Basic auth is handled by Quarkus REST client properties:
                // quarkus.rest-client.prometheus.username / password
                break;
            case "none":
                break;
            default:
                LOG.warnf("Unknown Prometheus auth mode: '%s'. No authentication applied.", authMode);
                break;
        }
    }

    private void addServiceAccountToken(final ClientRequestContext requestContext) {
        String tokenPath = ConfigProvider.getConfig()
            .getOptionalValue(SA_TOKEN_PATH_KEY, String.class)
            .orElse(DEFAULT_SA_TOKEN_PATH);

        try {
            String token = Files.readString(Path.of(tokenPath)).trim();
            requestContext.getHeaders().putSingle("Authorization", "Bearer " + token);
        } catch (IOException e) {
            LOG.warnf("Failed to read service account token from '%s': %s", tokenPath, e.getMessage());
        }
    }

    private void addBearerToken(final ClientRequestContext requestContext) {
        Optional<String> token = ConfigProvider.getConfig()
            .getOptionalValue(BEARER_TOKEN_KEY, String.class);

        if (token.isPresent() && !token.get().isBlank()) {
            requestContext.getHeaders().putSingle("Authorization", "Bearer " + token.get().trim());
        } else {
            LOG.warn("Bearer token auth mode selected but no token configured."
                + " Set '" + BEARER_TOKEN_KEY + "' property.");
        }
    }
}
