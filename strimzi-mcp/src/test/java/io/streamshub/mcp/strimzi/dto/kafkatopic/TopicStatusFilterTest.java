/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.dto.kafkatopic;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link TopicStatusFilter} status-matching semantics.
 */
class TopicStatusFilterTest {

    TopicStatusFilterTest() {
    }

    private static KafkaTopicResponse response(final String status, final Boolean managed) {
        return KafkaTopicResponse.of("t", "ns", "c", 1, 1, status,
            null, null, null, null, null, null, managed);
    }

    @Test
    void testEachStatusValueMatchesOnlyItsOwnStatus() {
        assertTrue(TopicStatusFilter.READY.matches(response("Ready", Boolean.TRUE)));
        assertFalse(TopicStatusFilter.READY.matches(response("NotReady", Boolean.TRUE)));
        assertTrue(TopicStatusFilter.NOT_READY.matches(response("NotReady", Boolean.TRUE)));
        assertFalse(TopicStatusFilter.NOT_READY.matches(response("Error", Boolean.TRUE)));
        assertFalse(TopicStatusFilter.NOT_READY.matches(response("Unknown", Boolean.TRUE)));
        assertTrue(TopicStatusFilter.ERROR.matches(response("Error", Boolean.TRUE)));
        assertTrue(TopicStatusFilter.UNKNOWN.matches(response("Unknown", Boolean.TRUE)));
    }

    @Test
    void testUnmanagedIsOrthogonalToReadiness() {
        assertTrue(TopicStatusFilter.UNMANAGED.matches(response("Ready", Boolean.FALSE)));
        assertFalse(TopicStatusFilter.UNMANAGED.matches(response("Ready", Boolean.TRUE)));
        assertFalse(TopicStatusFilter.UNMANAGED.matches(response("Ready", null)));
    }
}
