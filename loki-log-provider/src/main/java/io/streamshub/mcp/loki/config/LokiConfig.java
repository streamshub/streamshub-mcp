/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.loki.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;
import io.streamshub.mcp.common.auth.AuthConfig;
import org.hibernate.validator.constraints.time.DurationMin;

import java.time.Duration;
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
     * Whether to reduce JSON log records (for example OpenShift Logging ViaQ records) to
     * their message field in LogQL, before line filters run and before lines are returned.
     * Plain-text lines are returned unchanged either way. Enable it for OpenShift Logging.
     * Off by default because it reduces every JSON line, including lines that applications
     * log as JSON themselves: a level kept in another field, such as
     * {@code {"level":"ERROR","message":"..."}}, would be dropped, and the {@code errors}
     * filter and the error count would miss the line.
     *
     * @return true to unwrap the JSON message field
     */
    @WithName("unwrap-json")
    @WithDefault("false")
    boolean unwrapJson();

    /**
     * JSON field that holds the log message when {@link #unwrapJson()} is enabled.
     * Accepts a dotted path such as {@code log.message}.
     *
     * @return the JSON message field
     */
    @WithName("json-message-field")
    @WithDefault("message")
    String jsonMessageField();

    /**
     * How far back a query without a time range and without line filters reaches.
     * Loki stops once the {@code tailLines} limit is filled, so a wide window stays cheap.
     * Keep it at or below the backend's {@code max_query_length} (Loki default 721h),
     * or Loki rejects the query. Must be at least one minute; startup fails otherwise.
     *
     * @return the maximum lookback
     */
    @WithName("max-lookback")
    @DurationMin(minutes = 1)
    @WithDefault("720h")
    Duration maxLookback();

    /**
     * How far back a query with a {@code filter} or {@code keywords} but without a time range
     * reaches. Loki has no index on line content, so a filtered search scans every line in the
     * window; the response warns that only this window was searched. The effective value is
     * capped at {@link #maxLookback()}. Must be at least one minute; startup fails otherwise.
     *
     * @return the lookback for filtered queries without a time range
     */
    @WithName("filter-lookback")
    @DurationMin(minutes = 1)
    @WithDefault("1h")
    Duration filterLookback();

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

        /**
         * Loki label name for Kubernetes container name.
         *
         * @return the container label name
         */
        @WithDefault("container")
        String container();
    }
}
