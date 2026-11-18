/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.opensearch.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;
import io.streamshub.mcp.common.auth.AuthConfig;
import org.hibernate.validator.constraints.time.DurationMin;

import java.time.Duration;
import java.util.Optional;

/**
 * Configuration for the OpenSearch log provider.
 *
 * <p>Maps to properties under {@code mcp.log.opensearch.*}.
 * Connection settings (URL, TLS) are configured via standard
 * Quarkus REST client properties under {@code quarkus.rest-client.opensearch.*}.</p>
 */
@ConfigMapping(prefix = "mcp.log.opensearch")
public interface OpenSearchConfig extends AuthConfig {

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
     * OpenSearch index pattern to query (supports wildcards).
     *
     * @return the index pattern
     */
    @WithName("index-pattern")
    @WithDefault("kubernetes-*")
    String indexPattern();

    /**
     * How far back a search with a {@code filter} or {@code keywords} but without a time range
     * reaches. The {@code errors}/{@code warnings} patterns start with a wildcard, so OpenSearch
     * checks every term of the message field in each searched index; the window lets it skip older
     * time-based indices. The response warns that only this window was searched. Searches without
     * a filter or keywords have no default window. Must be at least one minute; startup fails otherwise.
     *
     * @return the lookback for filtered searches without a time range
     */
    @WithName("filter-lookback")
    @DurationMin(minutes = 1)
    @WithDefault("1h")
    Duration filterLookback();

    /**
     * Field name mapping for Kubernetes metadata in OpenSearch documents.
     *
     * @return the field configuration
     */
    FieldConfig field();

    /**
     * Field name mapping between Kubernetes metadata and OpenSearch document fields.
     * Defaults match the ECK (Elastic Cloud on Kubernetes) standard field names.
     */
    interface FieldConfig {

        /**
         * OpenSearch field name for Kubernetes namespace.
         *
         * @return the namespace field name
         */
        @WithDefault("kubernetes.namespace_name")
        String namespace();

        /**
         * OpenSearch field name for Kubernetes pod name.
         *
         * @return the pod field name
         */
        @WithDefault("kubernetes.pod_name")
        String pod();

        /**
         * OpenSearch field name for Kubernetes container name.
         *
         * @return the container field name
         */
        @WithDefault("kubernetes.container_name")
        String container();

        /**
         * OpenSearch field name for log message content.
         *
         * @return the message field name
         */
        @WithDefault("message")
        String message();

        /**
         * OpenSearch field name for log timestamp.
         *
         * @return the timestamp field name
         */
        @WithDefault("@timestamp")
        String timestamp();
    }
}
