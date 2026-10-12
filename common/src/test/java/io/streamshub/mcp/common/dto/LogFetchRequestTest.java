/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.dto;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Tests for {@link LogFetchRequest} mapping from {@link LogCollectionParams}.
 */
class LogFetchRequestTest {

    LogFetchRequestTest() {
    }

    @Test
    void testFromMapsAllFieldsIncludingContainer() {
        final LogCollectionParams options = LogCollectionParams.builder(150)
            .filter("errors")
            .keywords(List.of("OOM"))
            .sinceSeconds(300)
            .startTime("2025-01-01T00:00:00Z")
            .endTime("2025-01-01T01:00:00Z")
            .previous(true)
            .container("broker")
            .build();

        final LogFetchRequest request = LogFetchRequest.from("kafka", "my-cluster-kafka-0", options);

        assertEquals("kafka", request.namespace());
        assertEquals("my-cluster-kafka-0", request.podName());
        assertEquals(150, request.tailLines());
        assertEquals(300, request.sinceSeconds());
        assertEquals(Boolean.TRUE, request.previous());
        assertEquals("errors", request.filter());
        assertEquals(List.of("OOM"), request.keywords());
        assertEquals("2025-01-01T00:00:00Z", request.startTime());
        assertEquals("2025-01-01T01:00:00Z", request.endTime());
        assertEquals("broker", request.container());
    }

    @Test
    void testContainerNullWhenUnset() {
        final LogCollectionParams options = LogCollectionParams.builder(100).build();
        final LogFetchRequest request = LogFetchRequest.from("kafka", "pod-0", options);
        assertNull(request.container());
    }
}
