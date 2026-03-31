/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.metrics.prometheus.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * DTO for deserializing Prometheus HTTP API JSON responses.
 * Covers both instant and range query result formats.
 *
 * @param status the response status (e.g. "success")
 * @param data   the response data payload
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PrometheusResponse(
    @JsonProperty("status") String status,
    @JsonProperty("data") PrometheusData data
) {

    /**
     * The data payload of a Prometheus response.
     *
     * @param resultType the result type ("vector" for instant, "matrix" for range)
     * @param result     the list of result entries
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PrometheusData(
        @JsonProperty("resultType") String resultType,
        @JsonProperty("result") List<PrometheusResult> result
    ) {
    }

    /**
     * A single result entry containing metric labels and value(s).
     * For instant queries, {@code value} is populated.
     * For range queries, {@code values} is populated.
     *
     * @param metric the metric labels as key-value pairs
     * @param value  the single value for instant queries (a two-element array: [timestamp, value])
     * @param values the list of values for range queries (each a two-element array: [timestamp, value])
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PrometheusResult(
        @JsonProperty("metric") Map<String, String> metric,
        @JsonProperty("value") List<Object> value,
        @JsonProperty("values") List<List<Object>> values
    ) {
    }
}
