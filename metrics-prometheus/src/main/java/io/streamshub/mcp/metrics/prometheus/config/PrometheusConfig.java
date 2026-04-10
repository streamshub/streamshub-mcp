/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.metrics.prometheus.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;
import io.streamshub.mcp.common.auth.AuthConfig;

import java.util.Optional;

/**
 * Configuration for the Prometheus metrics provider.
 *
 * <p>Maps to properties under {@code mcp.metrics.prometheus.*}.
 * Connection settings (URL, TLS) are configured via standard
 * Quarkus REST client properties under {@code quarkus.rest-client.prometheus.*}.</p>
 */
@ConfigMapping(prefix = "mcp.metrics.prometheus")
public interface PrometheusConfig extends AuthConfig {

    /**
     * Authentication mode: {@code none}, {@code bearer-token}, {@code sa-token}, or {@code basic}.
     *
     * @return the auth mode
     */
    @Override
    @WithName("auth-mode")
    @WithDefault("none")
    String authMode();

    /**
     * Bearer token for {@code bearer-token} authentication.
     *
     * @return the bearer token, if configured
     */
    @Override
    @WithName("bearer-token")
    Optional<String> bearerToken();

    /**
     * Path to the service account token file for {@code sa-token} authentication.
     *
     * @return the token file path
     */
    @Override
    @WithName("sa-token-path")
    @WithDefault("/var/run/secrets/kubernetes.io/serviceaccount/token")
    String saTokenPath();
}
