/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.loki.service;

import io.streamshub.mcp.loki.LokiResponse;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

/**
 * REST client for the Loki HTTP API.
 * Configured via {@code quarkus.rest-client.loki.*} properties.
 */
@Path("/loki/api/v1")
@RegisterRestClient(configKey = "loki")
@RegisterProvider(LokiAuthFilter.class)
public interface LokiClient {

    /**
     * Executes a range query to retrieve log entries within a time window.
     *
     * @param query     the LogQL query expression
     * @param start     the range start time in nanoseconds since epoch
     * @param end       the range end time in nanoseconds since epoch
     * @param limit     the maximum number of log entries to return
     * @param direction the sort direction ({@code forward} or {@code backward})
     * @return the Loki response containing matching log streams
     */
    @GET
    @Path("/query_range")
    LokiResponse queryRange(@QueryParam("query") String query,
                             @QueryParam("start") long start,
                             @QueryParam("end") long end,
                             @QueryParam("limit") int limit,
                             @QueryParam("direction") String direction);
}
