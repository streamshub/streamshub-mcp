/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.dto.draincleaner;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link DrainCleanerResponse}.
 */
class DrainCleanerResponseTest {

    private static final ObjectMapper MAPPER = new ObjectMapper()
        .registerModule(new JavaTimeModule());

    DrainCleanerResponseTest() {
    }

    @Test
    void jsonHasNoDisplayNameAndOnlySnakeCaseKeys() throws Exception {
        DrainCleanerResponse response = DrainCleanerResponse.of("mcp-cluster", "strimzi-kafka", false,
            null, null, "4.3.1", null, null, null, null, null, null, null, null, null);

        String json = MAPPER.writeValueAsString(response);
        Map<String, Object> map = MAPPER.readValue(json, new TypeReference<>() { });

        assertFalse(map.containsKey("displayName"));
        for (String key : map.keySet()) {
            assertTrue(key.matches("^[a-z0-9_]+$"), "Key is not snake_case: " + key);
        }
    }
}
