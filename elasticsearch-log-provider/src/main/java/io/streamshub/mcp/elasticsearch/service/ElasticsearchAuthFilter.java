/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.elasticsearch.service;

import io.streamshub.mcp.common.auth.AbstractAuthFilter;
import io.streamshub.mcp.common.auth.AuthConfig;
import io.streamshub.mcp.elasticsearch.config.ElasticsearchConfig;
import jakarta.inject.Inject;

/**
 * JAX-RS client request filter that adds authentication to Elasticsearch API calls.
 * Supports service account token, explicit bearer token (API key), basic auth, and no-auth modes.
 * Basic auth and mTLS are handled natively by Quarkus REST client properties.
 *
 * <p>Registered on {@code ElasticsearchClient} via {@code @RegisterProvider},
 * so it only applies to Elasticsearch API calls.</p>
 *
 * <p><b>Important:</b> For Elasticsearch API keys, the bearer token is sent with the
 * {@code ApiKey} authorization scheme (not {@code Bearer}), as required by Elasticsearch.</p>
 */
public class ElasticsearchAuthFilter extends AbstractAuthFilter {

    @Inject
    ElasticsearchConfig config;

    ElasticsearchAuthFilter() {
    }

    @Override
    protected AuthConfig authConfig() {
        return config;
    }

    @Override
    protected String providerName() {
        return "Elasticsearch";
    }

    @Override
    protected String bearerTokenPropertyName() {
        return "mcp.log.elasticsearch.bearer-token";
    }

    @Override
    protected String bearerTokenAuthScheme() {
        return "ApiKey";
    }
}
