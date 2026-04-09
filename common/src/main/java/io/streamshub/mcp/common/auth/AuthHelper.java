/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.auth;

import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Shared authentication constants and helpers.
 */
public final class AuthHelper {

    private static final Logger LOG = Logger.getLogger(AuthHelper.class);

    /** Auth mode: no authentication. */
    public static final String AUTH_MODE_NONE = "none";

    /** Auth mode: read token from Kubernetes service account file. */
    public static final String AUTH_MODE_SA_TOKEN = "sa-token";

    /** Auth mode: use an explicit bearer token from configuration. */
    public static final String AUTH_MODE_BEARER_TOKEN = "bearer-token";

    /** Auth mode: basic auth (handled by Quarkus REST client properties). */
    public static final String AUTH_MODE_BASIC = "basic";

    /** Default path for the Kubernetes service account token. */
    public static final String DEFAULT_SA_TOKEN_PATH =
        "/var/run/secrets/kubernetes.io/serviceaccount/token";

    private AuthHelper() {
    }

    /**
     * Read a bearer token from a Kubernetes service account token file.
     *
     * @param tokenPath the path to the service account token file
     * @return the token value, or empty if the file could not be read
     */
    public static Optional<String> readServiceAccountToken(final String tokenPath) {
        try {
            return Optional.of(Files.readString(Path.of(tokenPath)).trim());
        } catch (IOException e) {
            LOG.warnf("Failed to read service account token from '%s': %s",
                tokenPath, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Validate that a bearer token is present and non-blank.
     * Logs a warning if the token is missing.
     *
     * @param token        the token value (optional)
     * @param propertyName the config property name (for warning messages)
     * @return the trimmed token value, or empty if not configured
     */
    public static Optional<String> validateBearerToken(final Optional<String> token,
                                                       final String propertyName) {
        if (token.isPresent() && !token.get().isBlank()) {
            return Optional.of(token.get().trim());
        }
        LOG.warnf("Bearer-token auth mode selected but no token configured."
            + " Set '%s' property.", propertyName);
        return Optional.empty();
    }
}
