/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.dto.kafka;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link KafkaClusterResponse} JSON serialization.
 */
class KafkaClusterResponseTest {

    private static final ObjectMapper MAPPER = new ObjectMapper()
        .registerModule(new JavaTimeModule());

    KafkaClusterResponseTest() {
    }

    /**
     * Verify unauthenticated_listeners is serialized when listeners lack authentication.
     */
    @Test
    void jsonIncludesUnauthenticatedListenersWhenPresent() throws Exception {
        Map<String, Object> map = serialize(List.of("plain"));

        assertEquals(List.of("plain"), map.get("unauthenticated_listeners"));
        assertEquals(true, map.get("authentication_enabled"));
    }

    /**
     * Verify unauthenticated_listeners is omitted from the JSON when every listener has authentication.
     */
    @Test
    void jsonOmitsUnauthenticatedListenersWhenEmpty() throws Exception {
        assertFalse(serialize(List.of()).containsKey("unauthenticated_listeners"));
        assertFalse(serialize(null).containsKey("unauthenticated_listeners"));
    }

    /**
     * Verify no camelCase bean property such as displayName is serialized and every top-level key is snake_case.
     */
    @Test
    void jsonHasNoDisplayNameAndOnlySnakeCaseKeys() throws Exception {
        KafkaClusterResponse response = KafkaClusterResponse.of("mcp-cluster", "strimzi-kafka", null,
            "4.3.1", null, null, null, null, null, null, null, null, null,
            null, null, null, null, null, null, null, null, null, null, null);

        String json = MAPPER.writeValueAsString(response);
        Map<String, Object> map = MAPPER.readValue(json, new TypeReference<>() { });

        assertFalse(map.containsKey("displayName"));
        for (String key : map.keySet()) {
            assertTrue(key.matches("^[a-z0-9_]+$"), "Key is not snake_case: " + key);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> serialize(final List<String> unauthenticatedListeners) throws Exception {
        KafkaClusterResponse response = KafkaClusterResponse.of("c", "kafka", "Kafka", "4.2.0", "4.2.0",
            null, null, null, "Ready", null, null, null, null,
            false, true, unauthenticatedListeners, true, null, 1L, null, null, null, null, List.of());
        return MAPPER.readValue(MAPPER.writeValueAsString(response), Map.class);
    }
}
