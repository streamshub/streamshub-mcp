/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.loki.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;
import io.streamshub.mcp.common.auth.AuthConfig;

import java.util.Optional;

/**
 * Configuration for the Loki log provider.
 *
 * <p>Maps to properties under {@code mcp.log.loki.*}.
 * Connection settings (URL, TLS) are configured via standard
 * Quarkus REST client properties under {@code quarkus.rest-client.loki.*}.</p>
 */
@ConfigMapping(prefix = "mcp.log.loki")
public interface LokiConfig extends AuthConfig {

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

    /**
     * Optional tenant ID for multi-tenant Loki deployments.
     * Sent as the {@code X-Scope-OrgID} header.
     *
     * @return the tenant ID, if configured
     */
    @WithName("tenant-id")
    Optional<String> tenantId();

    /**
     * Label name mapping for Kubernetes metadata in Loki.
     *
     * @return the label configuration
     */
    LabelConfig label();

    /**
     * Label name mapping between Kubernetes metadata and Loki stream labels.
     * Defaults match the Grafana Alloy / Promtail standard label names.
     */
    interface LabelConfig {

        /**
         * Loki label name for Kubernetes namespace.
         *
         * @return the namespace label name
         */
        @WithDefault("namespace")
        String namespace();

        /**
         * Loki label name for Kubernetes pod name.
         *
         * @return the pod label name
         */
        @WithDefault("pod")
        String pod();
    }
}
