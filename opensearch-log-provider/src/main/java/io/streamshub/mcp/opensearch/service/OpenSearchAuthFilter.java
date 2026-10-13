/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.opensearch.service;

import io.streamshub.mcp.common.auth.AbstractAuthFilter;
import io.streamshub.mcp.common.auth.AuthConfig;
import io.streamshub.mcp.opensearch.config.OpenSearchConfig;
import jakarta.inject.Inject;

/**
 * JAX-RS client request filter that adds authentication to OpenSearch API calls.
 * Supports service account token, explicit bearer token (API key), basic auth, and no-auth modes.
 * Basic auth and mTLS are handled natively by Quarkus REST client properties.
 *
 * <p>Registered on {@code OpenSearchClient} via {@code @RegisterProvider},
 * so it only applies to OpenSearch API calls.</p>
 *
 * <p><b>Important:</b> For OpenSearch API keys, the bearer token is sent with the
 * {@code ApiKey} authorization scheme (not {@code Bearer}), as required by OpenSearch.</p>
 */
public class OpenSearchAuthFilter extends AbstractAuthFilter {

    @Inject
    OpenSearchConfig config;

    OpenSearchAuthFilter() {
    }

    @Override
    protected AuthConfig authConfig() {
        return config;
    }

    @Override
    protected String providerName() {
        return "OpenSearch";
    }

    @Override
    protected String bearerTokenPropertyName() {
        return "mcp.log.opensearch.bearer-token";
    }

    @Override
    protected String bearerTokenAuthScheme() {
        return "ApiKey";
    }
}
