/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.elasticsearch.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;
import io.streamshub.mcp.common.auth.AuthConfig;

import java.util.Optional;

/**
 * Configuration for the Elasticsearch log provider.
 *
 * <p>Maps to properties under {@code mcp.log.elasticsearch.*}.
 * Connection settings (URL, TLS) are configured via standard
 * Quarkus REST client properties under {@code quarkus.rest-client.elasticsearch.*}.</p>
 */
@ConfigMapping(prefix = "mcp.log.elasticsearch")
public interface ElasticsearchConfig extends AuthConfig {

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
     * Elasticsearch index pattern to query (supports wildcards).
     *
     * @return the index pattern
     */
    @WithName("index-pattern")
    @WithDefault("kubernetes-*")
    String indexPattern();

    /**
     * Field name mapping for Kubernetes metadata in Elasticsearch documents.
     *
     * @return the field configuration
     */
    FieldConfig field();

    /**
     * Field name mapping between Kubernetes metadata and Elasticsearch document fields.
     * Defaults match the ECK (Elastic Cloud on Kubernetes) standard field names.
     */
    interface FieldConfig {

        /**
         * Elasticsearch field name for Kubernetes namespace.
         *
         * @return the namespace field name
         */
        @WithDefault("kubernetes.namespace_name")
        String namespace();

        /**
         * Elasticsearch field name for Kubernetes pod name.
         *
         * @return the pod field name
         */
        @WithDefault("kubernetes.pod_name")
        String pod();

        /**
         * Elasticsearch field name for Kubernetes container name.
         *
         * @return the container field name
         */
        @WithDefault("kubernetes.container_name")
        String container();

        /**
         * Elasticsearch field name for log message content.
         *
         * @return the message field name
         */
        @WithDefault("message")
        String message();

        /**
         * Elasticsearch field name for log timestamp.
         *
         * @return the timestamp field name
         */
        @WithDefault("@timestamp")
        String timestamp();
    }
}
