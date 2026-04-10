/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.auth;

import java.util.Optional;

/**
 * Common authentication configuration contract for external service providers.
 * Implemented by provider-specific {@code @ConfigMapping} interfaces
 * (e.g. {@code LokiConfig}, {@code PrometheusConfig}).
 */
public interface AuthConfig {

    /**
     * Authentication mode: {@code none}, {@code bearer-token}, {@code sa-token}, or {@code basic}.
     *
     * @return the auth mode
     */
    String authMode();

    /**
     * Bearer token for {@code bearer-token} authentication.
     *
     * @return the bearer token, if configured
     */
    Optional<String> bearerToken();

    /**
     * Path to the service account token file for {@code sa-token} authentication.
     *
     * @return the token file path
     */
    String saTokenPath();
}
