/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link CompletionHelper} prefix filtering logic.
 */
class CompletionHelperTest {

    private final CompletionHelper helper = new CompletionHelper();

    CompletionHelperTest() {
    }

    @Test
    void testFilterByPrefixMatchesPrefix() {
        List<String> names = List.of("kafka-prod", "kafka-dev", "monitoring", "default");
        List<String> result = helper.filterByPrefix(names, "kafka");
        assertEquals(2, result.size());
        assertTrue(result.contains("kafka-prod"));
        assertTrue(result.contains("kafka-dev"));
    }

    @Test
    void testFilterByPrefixCaseInsensitive() {
        List<String> names = List.of("Kafka-Prod", "kafka-dev", "KAFKA-STAGING");
        List<String> result = helper.filterByPrefix(names, "kafka");
        assertEquals(3, result.size());
    }

    @Test
    void testFilterByPrefixNullReturnsAll() {
        List<String> names = List.of("alpha", "beta", "gamma");
        List<String> result = helper.filterByPrefix(names, null);
        assertEquals(3, result.size());
    }

    @Test
    void testFilterByPrefixEmptyStringReturnsAll() {
        List<String> names = List.of("alpha", "beta", "gamma");
        List<String> result = helper.filterByPrefix(names, "");
        assertEquals(3, result.size());
    }

    @Test
    void testFilterByPrefixNoMatch() {
        List<String> names = List.of("alpha", "beta", "gamma");
        List<String> result = helper.filterByPrefix(names, "delta");
        assertTrue(result.isEmpty());
    }

    @Test
    void testFilterByPrefixLimitsResults() {
        List<String> names = new java.util.ArrayList<>();
        for (int i = 0; i < 100; i++) {
            names.add("ns-" + i);
        }
        List<String> result = helper.filterByPrefix(names, "ns");
        assertEquals(CompletionHelper.MAX_COMPLETIONS, result.size());
    }

    @Test
    void testFilterByPrefixEmptyList() {
        List<String> result = helper.filterByPrefix(List.of(), "any");
        assertTrue(result.isEmpty());
    }
}
