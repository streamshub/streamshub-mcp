/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.metrics.prometheus.service;

import io.streamshub.mcp.common.auth.AbstractAuthFilter;
import io.streamshub.mcp.common.auth.AuthConfig;
import io.streamshub.mcp.metrics.prometheus.config.PrometheusConfig;
import jakarta.inject.Inject;

/**
 * JAX-RS client request filter that adds authentication to Prometheus API calls.
 * Supports service account token, explicit bearer token, basic auth, and no-auth modes.
 * Basic auth and mTLS are handled natively by Quarkus REST client properties.
 *
 * <p>Registered on {@link PrometheusClient} via {@code @RegisterProvider},
 * so it only applies to Prometheus API calls.</p>
 */
public class PrometheusAuthFilter extends AbstractAuthFilter {

    @Inject
    PrometheusConfig config;

    PrometheusAuthFilter() {
        // package-private no-arg constructor for CDI
    }

    @Override
    protected AuthConfig authConfig() {
        return config;
    }

    @Override
    protected String providerName() {
        return "Prometheus";
    }

    @Override
    protected String bearerTokenPropertyName() {
        return "mcp.metrics.prometheus.bearer-token";
    }
}
