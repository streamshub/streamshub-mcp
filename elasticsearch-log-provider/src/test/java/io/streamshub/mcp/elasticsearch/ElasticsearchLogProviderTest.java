/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.elasticsearch;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class ElasticsearchLogProviderTest {

    @Inject
    ElasticsearchLogProvider provider;

    ElasticsearchLogProviderTest() {
    }

    @Test
    void testBuildSearchQueryBasic() {
        String query = provider.buildSearchQuery(
            "kafka-prod", "my-cluster-kafka-0", 200, null, null, null, null, null);
        assertTrue(query.contains("\"kubernetes.namespace_name.keyword\""));
        assertTrue(query.contains("\"kafka-prod\""));
        assertTrue(query.contains("\"kubernetes.pod_name.keyword\""));
        assertTrue(query.contains("\"my-cluster-kafka-0\""));
        assertTrue(query.contains("\"size\":201"));
    }

    @Test
    void testBuildSearchQueryWithErrorsFilter() {
        String query = provider.buildSearchQuery(
            "kafka", "pod-0", 200, null, "errors", null, null, null);
        assertTrue(query.contains("regexp"));
        assertTrue(query.contains("(ERROR|EXCEPTION)"));
        assertTrue(query.contains("case_insensitive"));
    }

    @Test
    void testBuildSearchQueryWithWarningsFilter() {
        String query = provider.buildSearchQuery(
            "kafka", "pod-0", 200, null, "warnings", null, null, null);
        assertTrue(query.contains("regexp"));
        assertTrue(query.contains("(ERROR|EXCEPTION|WARN)"));
        assertTrue(query.contains("case_insensitive"));
    }

    @Test
    void testBuildSearchQueryWithCustomRegex() {
        String query = provider.buildSearchQuery(
            "kafka", "pod-0", 200, null, "OOM|timeout", null, null, null);
        assertTrue(query.contains("regexp"));
        assertTrue(query.contains("OOM|timeout"));
    }

    @Test
    void testBuildSearchQueryWithKeywords() {
        String query = provider.buildSearchQuery(
            "kafka", "pod-0", 200, null, null, List.of("OOM", "timeout"), null, null);
        assertTrue(query.contains("should"));
        assertTrue(query.contains("OOM"));
        assertTrue(query.contains("timeout"));
        assertTrue(query.contains("minimum_should_match"));
    }

    @Test
    void testBuildSearchQueryWithSinceSeconds() {
        String query = provider.buildSearchQuery(
            "kafka", "pod-0", 200, 3600, null, null, null, null);
        assertTrue(query.contains("range"));
        assertTrue(query.contains("gte"));
        assertTrue(query.contains("@timestamp"));
    }

    @Test
    void testBuildSearchQueryWithAbsoluteTimeRange() {
        String query = provider.buildSearchQuery(
            "kafka", "pod-0", 200, null, null, null,
            "2026-01-01T00:00:00Z", "2026-01-02T00:00:00Z");
        assertTrue(query.contains("range"));
        assertTrue(query.contains("2026-01-01T00:00:00Z"));
        assertTrue(query.contains("2026-01-02T00:00:00Z"));
    }

    @Test
    void testBuildSearchQueryEscapesSpecialCharacters() {
        String query = provider.buildSearchQuery(
            "ns\"}]}}", "pod-0", 200, null, null, null, null, null);
        assertTrue(query.contains("ns\\\"}]}}"));
    }

    @Test
    void testBuildSearchQueryWithPartialTimeRangeFallsBackToDefault() {
        String query = provider.buildSearchQuery(
            "kafka", "pod-0", 200, null, null, null,
            "2026-01-01T00:00:00Z", null);
        assertTrue(query.contains("range"));
        assertTrue(query.contains("gte"));
        assertTrue(query.contains("@timestamp"));
    }

    @Test
    void testExtractLogLinesFromResponse() {
        ElasticsearchResponse response = new ElasticsearchResponse(
            new ElasticsearchResponse.HitsWrapper(
                new ElasticsearchResponse.TotalHits(3, "eq"),
                List.of(
                    new ElasticsearchResponse.Hit(Map.of(
                        "message", "third line",
                        "@timestamp", "2026-01-01T00:00:03Z"), null),
                    new ElasticsearchResponse.Hit(Map.of(
                        "message", "second line",
                        "@timestamp", "2026-01-01T00:00:02Z"), null),
                    new ElasticsearchResponse.Hit(Map.of(
                        "message", "first line",
                        "@timestamp", "2026-01-01T00:00:01Z"), null)
                )
            )
        );

        String result = provider.extractLogLines(response);
        assertNotNull(result);
        assertEquals("first line\nsecond line\nthird line\n", result);
    }

    @Test
    void testExtractLogLinesReturnsNullForEmptyHits() {
        ElasticsearchResponse response = new ElasticsearchResponse(
            new ElasticsearchResponse.HitsWrapper(
                new ElasticsearchResponse.TotalHits(0, "eq"),
                List.of()
            )
        );
        assertNull(provider.extractLogLines(response));
    }

    @Test
    void testExtractLogLinesReturnsNullForNullResponse() {
        assertNull(provider.extractLogLines(null));
    }

    @Test
    void testExtractLogLinesReturnsNullForNullHits() {
        assertNull(provider.extractLogLines(new ElasticsearchResponse(null)));
    }

    @Test
    void testExtractLogLinesSkipsEntriesWithoutMessage() {
        ElasticsearchResponse response = new ElasticsearchResponse(
            new ElasticsearchResponse.HitsWrapper(
                new ElasticsearchResponse.TotalHits(2, "eq"),
                List.of(
                    new ElasticsearchResponse.Hit(Map.of(
                        "message", "valid line",
                        "@timestamp", "2026-01-01T00:00:01Z"), null),
                    new ElasticsearchResponse.Hit(Map.of(
                        "@timestamp", "2026-01-01T00:00:02Z"), null)
                )
            )
        );

        String result = provider.extractLogLines(response);
        assertEquals("valid line\n", result);
    }
}
