/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.opensearch.service;

import io.streamshub.mcp.opensearch.OpenSearchResponse;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

/**
 * REST client for the OpenSearch HTTP API.
 * Configured via {@code quarkus.rest-client.opensearch.*} properties.
 */
@RegisterRestClient(configKey = "opensearch")
@RegisterProvider(OpenSearchAuthFilter.class)
public interface OpenSearchClient {

    /**
     * Executes a search query against the specified index.
     *
     * @param index     the index name to search
     * @param queryJson the search query as a JSON string
     * @return the OpenSearch response containing matching documents
     */
    @POST
    @Path("/{index}/_search")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    OpenSearchResponse search(@PathParam("index") String index, String queryJson);
}
