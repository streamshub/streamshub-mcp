/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.metrics.prometheus.service;

import io.streamshub.mcp.metrics.prometheus.dto.PrometheusResponse;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

/**
 * REST client for the Prometheus HTTP API.
 * Supports both instant and range queries.
 */
@Path("/api/v1")
@RegisterRestClient(configKey = "prometheus")
@RegisterProvider(PrometheusAuthFilter.class)
public interface PrometheusClient {

    /**
     * Executes an instant query at the current time or at a specific timestamp.
     *
     * @param query the PromQL query expression
     * @param time  optional evaluation timestamp (RFC3339 or Unix timestamp)
     * @return the Prometheus response containing query results
     */
    @GET
    @Path("/query")
    PrometheusResponse instantQuery(@QueryParam("query") String query,
                                     @QueryParam("time") String time);

    /**
     * Executes a range query over a time window.
     *
     * @param query the PromQL query expression
     * @param start the range start time (RFC3339 or Unix timestamp)
     * @param end   the range end time (RFC3339 or Unix timestamp)
     * @param step  the query resolution step (e.g. "60s")
     * @return the Prometheus response containing query results
     */
    @GET
    @Path("/query_range")
    PrometheusResponse rangeQuery(@QueryParam("query") String query,
                                   @QueryParam("start") String start,
                                   @QueryParam("end") String end,
                                   @QueryParam("step") String step);
}
