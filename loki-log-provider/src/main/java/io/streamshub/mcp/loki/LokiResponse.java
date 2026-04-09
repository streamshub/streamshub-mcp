/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.loki;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

/**
 * DTO for deserializing the Loki {@code /loki/api/v1/query_range} response.
 *
 * <p>Example response structure:</p>
 * <pre>{@code
 * {
 *   "status": "success",
 *   "data": {
 *     "resultType": "streams",
 *     "result": [
 *       {
 *         "stream": {"namespace": "kafka", "pod": "my-cluster-kafka-0"},
 *         "values": [["1234567890000000000", "log line"], ...]
 *       }
 *     ]
 *   }
 * }
 * }</pre>
 *
 * @param status the response status
 * @param data   the query result data
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LokiResponse(
    String status,
    LokiData data
) {

    /**
     * Container for query result data.
     *
     * @param resultType the result type (e.g., "streams")
     * @param result     the list of matching log streams
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LokiData(
        String resultType,
        List<LokiStream> result
    ) {
    }

    /**
     * A single log stream with its labels and log entries.
     *
     * @param stream the stream labels
     * @param values the log entries as {@code [timestamp_ns, line]} pairs
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LokiStream(
        Map<String, String> stream,
        List<List<String>> values
    ) {
    }
}
