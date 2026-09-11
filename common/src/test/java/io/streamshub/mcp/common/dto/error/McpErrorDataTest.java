/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.dto.error;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link McpErrorData} JSON serialization contract.
 */
class McpErrorDataTest {

    private final ObjectMapper mapper = new ObjectMapper();

    McpErrorDataTest() {
    }

    @Test
    void testSerializesWithSnakeCaseAndOmitsNulls() throws Exception {
        McpErrorData data = new McpErrorData(
            McpErrorCategory.RESOURCE_NOT_FOUND, "Kafka", "my-cluster", "prod", null, null);

        String json = mapper.writeValueAsString(data);

        assertTrue(json.contains("\"category\":\"RESOURCE_NOT_FOUND\""), json);
        assertTrue(json.contains("\"resource_kind\":\"Kafka\""), json);
        assertTrue(json.contains("\"resource_name\":\"my-cluster\""), json);
        assertTrue(json.contains("\"namespace\":\"prod\""), json);
        assertFalse(json.contains("candidates"), json);
        assertFalse(json.contains("remediation"), json);
    }

    @Test
    void testSerializesCandidatesList() throws Exception {
        McpErrorData data = new McpErrorData(
            McpErrorCategory.AMBIGUOUS, "Kafka", "my-cluster", null, List.of("ns1", "ns2"), null);

        String json = mapper.writeValueAsString(data);

        assertTrue(json.contains("\"candidates\":[\"ns1\",\"ns2\"]"), json);
    }
}
