/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.elasticsearch;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * DTO for deserializing the Elasticsearch {@code /_search} response.
 *
 * <p>Example response structure:</p>
 * <pre>{@code
 * {
 *   "hits": {
 *     "total": {"value": 42, "relation": "eq"},
 *     "hits": [
 *       {
 *         "_source": {"@timestamp": "2024-01-01T12:00:00Z", "message": "log line"},
 *         "sort": [1704110400000]
 *       }
 *     ]
 *   }
 * }
 * }</pre>
 *
 * @param hits the hits wrapper containing search results
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ElasticsearchResponse(
    HitsWrapper hits
) {

    /**
     * Container for search hits and metadata.
     *
     * @param total the total hit count metadata
     * @param hits  the list of matching documents
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record HitsWrapper(
        TotalHits total,
        @JsonProperty("hits") List<Hit> hits
    ) {
    }

    /**
     * Metadata about the total number of hits.
     *
     * @param value    the total hit count
     * @param relation the relation type (e.g., "eq", "gte")
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TotalHits(
        long value,
        String relation
    ) {
    }

    /**
     * A single search hit containing the document and sort values.
     *
     * @param source the document source fields
     * @param sort   the sort values for search_after pagination
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Hit(
        @JsonProperty("_source") Map<String, Object> source,
        List<Object> sort
    ) {
    }
}
